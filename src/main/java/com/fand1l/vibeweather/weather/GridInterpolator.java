package com.fand1l.vibeweather.weather;

import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * Reads weather at an arbitrary point out of the sample grid the client receives.
 *
 * <p>Two interpolations, for two different kinds of gap:
 *
 * <ul>
 *   <li><b>Space.</b> Nodes are tens of blocks apart, so a nearest-node lookup would turn the zone
 *       edge into a staircase with treads the size of the grid step. Bilinear over the four
 *       surrounding nodes restores the smooth ramp the server actually computed.</li>
 *   <li><b>Time.</b> Updates arrive every couple of seconds. Snapping to each one would make wind
 *       direction and rain intensity visibly step. Blending from the previous grid to the current
 *       one across the observed gap turns that into a slow drift.</li>
 * </ul>
 *
 * <h2>Two ways in, on purpose</h2>
 * {@link #sample} returns a full {@link WeatherSample} and allocates; it is for the handful of calls
 * per tick that want every axis -- sound, wind physics, the query command. The {@code unitAt} and
 * {@code angleAt} readers return a single float and allocate nothing; they are for the column sweep
 * in the renderer, which runs tens of thousands of times a frame and where the brief forbids
 * allocating at all.
 *
 * <p>Pure, like the rest of this package, so the spatial term can be checked without a game -- a bug
 * there is a shimmering zone edge, which is hard to catch by eye and impossible to catch in a
 * debugger.
 */
public final class GridInterpolator {
	private GridInterpolator() {
	}

	/**
	 * Weather at a world position.
	 *
	 * @param altitudeFactor vertical falloff at the sampled height; applied to the result rather than
	 *                       interpolated, since it depends on the viewer's height and not on the grid
	 * @return the blended sample, or {@link WeatherSample#CLEAR} when there is no grid
	 */
	public static WeatherSample sample(
			WeatherGridBuilder.Grid grid,
			double x,
			double z,
			float altitudeFactor,
			WeatherRules rules
	) {
		if (grid == null) {
			return WeatherSample.CLEAR;
		}

		int nodeX = floorNode(x, grid.step());
		int nodeZ = floorNode(z, grid.step());
		float tx = fraction(x, grid.step());
		float tz = fraction(z, grid.step());

		WeatherState state = new WeatherState(
				bilinear(grid, nodeX, nodeZ, tx, tz, GridCodec.FIELD_CLOUDS),
				bilinear(grid, nodeX, nodeZ, tx, tz, GridCodec.FIELD_PRECIP),
				bilinear(grid, nodeX, nodeZ, tx, tz, GridCodec.FIELD_THUNDER),
				bilinear(grid, nodeX, nodeZ, tx, tz, GridCodec.FIELD_FOG),
				bilinear(grid, nodeX, nodeZ, tx, tz, GridCodec.FIELD_WIND),
				bilinearAngle(grid, nodeX, nodeZ, tx, tz));

		return new WeatherSample(
				state.sanitize(rules),
				bilinear(grid, nodeX, nodeZ, tx, tz, GridCodec.FIELD_COVERAGE),
				altitudeFactor);
	}

	/**
	 * Blends two samples.
	 *
	 * <p>Public because the client uses it for the time term. Doing space and time through one
	 * function is what stops the two from disagreeing about how coverage or a bearing combine.
	 */
	public static WeatherSample blend(WeatherSample a, WeatherSample b, float t, WeatherRules rules) {
		if (t <= 0.0F) {
			return a;
		}

		if (t >= 1.0F) {
			return b;
		}

		return new WeatherSample(
				WeatherState.lerp(t, a.state(), b.state()).sanitize(rules),
				MathUtil.lerp(t, a.coverage(), b.coverage()),
				MathUtil.lerp(t, a.altitudeFactor(), b.altitudeFactor()));
	}

	/** One 0..1 axis at a world position, allocation-free. Zero when there is no grid. */
	public static float unitAt(WeatherGridBuilder.Grid grid, double x, double z, int field) {
		if (grid == null) {
			return 0.0F;
		}

		return bilinear(grid, floorNode(x, grid.step()), floorNode(z, grid.step()),
				fraction(x, grid.step()), fraction(z, grid.step()), field);
	}

	/** The wind bearing at a world position, allocation-free. */
	public static float angleAt(WeatherGridBuilder.Grid grid, double x, double z) {
		if (grid == null) {
			return 0.0F;
		}

		return bilinearAngle(grid, floorNode(x, grid.step()), floorNode(z, grid.step()),
				fraction(x, grid.step()), fraction(z, grid.step()));
	}

	private static int floorNode(double world, double step) {
		return (int) Math.floor(world / step);
	}

	private static float fraction(double world, double step) {
		double scaled = world / step;
		return (float) (scaled - Math.floor(scaled));
	}

	private static float bilinear(
			WeatherGridBuilder.Grid grid,
			int nodeX,
			int nodeZ,
			float tx,
			float tz,
			int field
	) {
		float low = MathUtil.lerp(tx, unit(grid, nodeX, nodeZ, field), unit(grid, nodeX + 1, nodeZ, field));
		float high = MathUtil.lerp(tx, unit(grid, nodeX, nodeZ + 1, field), unit(grid, nodeX + 1, nodeZ + 1, field));
		return MathUtil.lerp(tz, low, high);
	}

	private static float bilinearAngle(WeatherGridBuilder.Grid grid, int nodeX, int nodeZ, float tx, float tz) {
		float low = MathUtil.lerpAngle(tx, angle(grid, nodeX, nodeZ), angle(grid, nodeX + 1, nodeZ));
		float high = MathUtil.lerpAngle(tx, angle(grid, nodeX, nodeZ + 1), angle(grid, nodeX + 1, nodeZ + 1));
		return MathUtil.lerpAngle(tz, low, high);
	}

	/**
	 * One axis of one node. Off the grid reads as zero -- deliberately not as the nearest edge node,
	 * because clamping would smear the outermost ring of weather outwards and make a storm sitting at
	 * the edge of what the server sent appear to run to the horizon.
	 */
	private static float unit(WeatherGridBuilder.Grid grid, int nodeX, int nodeZ, int field) {
		int index = grid.indexOf(nodeX, nodeZ);
		return index < 0 ? 0.0F : GridCodec.unit(grid.data(), index * GridCodec.NODE_BYTES, field);
	}

	/**
	 * One node's bearing. Here the coordinates <em>are</em> clamped to the grid, unlike the axes
	 * above: a bearing has no zero that means "nothing", so an off-grid reading of 0 would swing the
	 * interpolated wind towards north near the edge. Clamping it is harmless, because wind strength
	 * out there has already faded to nothing.
	 */
	private static float angle(WeatherGridBuilder.Grid grid, int nodeX, int nodeZ) {
		int clampedX = Math.clamp(nodeX, grid.originNodeX(), grid.originNodeX() + grid.side() - 1);
		int clampedZ = Math.clamp(nodeZ, grid.originNodeZ(), grid.originNodeZ() + grid.side() - 1);
		return GridCodec.angle(grid.data(), grid.indexOf(clampedX, clampedZ) * GridCodec.NODE_BYTES);
	}
}
