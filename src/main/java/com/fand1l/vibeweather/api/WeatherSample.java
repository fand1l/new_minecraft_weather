package com.fand1l.vibeweather.api;

import com.fand1l.vibeweather.util.Hashing;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * Weather resolved at one point in the world: the blended state plus the two spatial factors that
 * shaped it.
 *
 * <p>Coverage and intensity are kept apart on purpose, because they answer different questions and
 * are rendered by different means:
 *
 * <ul>
 *   <li><b>coverage</b> is spatial -- how deep inside a zone this point is, ramping across the
 *       blend band from 0 outside to 1 in the core. It draws the <em>edge</em>.</li>
 *   <li><b>state intensity</b> is temporal -- how hard it is currently raining. It draws the
 *       <em>strength</em>.</li>
 * </ul>
 *
 * <p>The split exists because 26.2 gives precipitation no per-column intensity: the renderer asks
 * for a precipitation enum per column and applies a single intensity scalar to the whole frame. A
 * soft edge therefore cannot be drawn with transparency. It is drawn with column <em>density</em>
 * instead -- see {@link #shouldDrawColumn}.
 *
 * @param state          the blended, sanitized tuple
 * @param coverage       spatial blend weight in 0..1
 * @param altitudeFactor vertical falloff in 0..1; 1 below the clouds, 0 above them
 */
public record WeatherSample(WeatherState state, float coverage, float altitudeFactor) {
	public static final WeatherSample CLEAR = new WeatherSample(WeatherState.CLEAR, 0.0F, 1.0F);

	/**
	 * Seed for the edge dither.
	 *
	 * <p>A constant, not the world seed. The pattern only has to be identical on client and server
	 * and stable over time; the client has no reliable access to the world seed anyway, and a
	 * mismatch there would put rain on different blocks than the ones the server considers wet.
	 */
	public static final long DITHER_SEED = 0x5669_6265_5765_6174L;

	/**
	 * Decides whether precipitation is present in a single block column, dithering the zone edge.
	 *
	 * <p>Used by both the renderer and the gameplay hooks, deliberately. Because the decision is a
	 * function of position alone, a given block is consistently wet or consistently dry -- so the
	 * block you can see rain falling on is the same block whose fire goes out.
	 *
	 * <p>The random source is a hash of the block column, not a per-frame RNG. That is the whole
	 * point: the set of drawn columns must be fixed in world space, otherwise every column would
	 * re-roll each frame and the boundary would shimmer. With a positional hash the fraction of
	 * drawn columns rises smoothly from 0 to 1 across the blend band, which reads as a dissolving
	 * curtain of rain -- close to how a real squall line looks.
	 *
	 * <p>Known limit: the dither cell is one block, so at long range the pattern falls below one
	 * pixel and reads as uniform translucency rather than as grain. For a distant wall of rain that
	 * is arguably the better outcome, but there is no visible grain far away.
	 */
	public boolean precipitatesAt(long seed, int x, int z) {
		float chance = coverage * altitudeFactor;

		if (chance <= 0.0F) {
			return false;
		}

		return chance >= 1.0F || Hashing.unitFloat(seed, x, z) < chance;
	}

	/** Precipitation strength actually applied, after the vertical cutoff. */
	public float effectivePrecip() {
		return state.precip() * altitudeFactor;
	}

	/** Thunder strength actually applied. Never multiplied by precipitation -- dry storms are legal. */
	public float effectiveThunder() {
		return state.thunder() * altitudeFactor;
	}

	public float effectiveFog() {
		return state.fog() * altitudeFactor;
	}

	/**
	 * Vertical falloff for a height, from the configured cloud band.
	 *
	 * <p>Cloud height is config-backed rather than hard-coded because clouds are slated for rework;
	 * above {@code cloudTop} the sky is clear, which is what lets a player climb out of a storm.
	 */
	public static float altitudeFactor(double y, float cloudBottom, float cloudTop) {
		return 1.0F - MathUtil.smoothstep(cloudBottom, cloudTop, (float) y);
	}
}
