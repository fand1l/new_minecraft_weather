package com.fand1l.vibeweather.client;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;

import com.fand1l.vibeweather.api.ClientWeatherParams;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.net.VibeWeatherPayloads;
import com.fand1l.vibeweather.server.WeatherHooks;
import com.fand1l.vibeweather.util.MathUtil;
import com.fand1l.vibeweather.weather.GridCodec;
import com.fand1l.vibeweather.weather.GridInterpolator;
import com.fand1l.vibeweather.weather.WeatherGridBuilder;

/**
 * Everything the client knows about the weather: the last two grids it was sent, and the parameters
 * that say how to read them.
 *
 * <p>Kept as one object with no rendering in it, because it is read from three places that must
 * agree -- the precipitation renderer, the fog, and the wind physics. A second copy of "what is the
 * weather here" would be a second answer.
 *
 * <h2>Why two grids</h2>
 * Updates arrive every couple of seconds. Holding only the newest would make the wind swing and the
 * rain thicken in visible steps. Keeping the previous one and blending across the gap that was
 * actually observed -- rather than a gap agreed in the protocol -- means the smoothing self-corrects
 * when the connection stutters, and costs nothing on the wire.
 */
public final class ClientWeatherState implements WeatherHooks.ClientSource {
	private static final ClientWeatherState INSTANCE = new ClientWeatherState();

	private ClientWeatherParams params = ClientWeatherParams.defaults();
	private WeatherGridBuilder.Grid current;
	private WeatherGridBuilder.Grid previous;

	/** Ticks since the last packet, and the gap the blend is spread across. */
	private int ticksSincePacket;
	private int blendTicks = 1;

	/** Identity of the level these grids belong to, so a dimension change cannot show stale weather. */
	private ClientLevel level;

	/** Where the viewer stood at the last tick, for the two level-wide methods with no position. */
	private double viewerX;
	private double viewerY;
	private double viewerZ;

	private ClientWeatherState() {
	}

	public static ClientWeatherState get() {
		return INSTANCE;
	}

	public ClientWeatherParams params() {
		return params;
	}

	public WeatherRules rules() {
		return params.rules();
	}

	// ------------------------------------------------------------------------------ incoming

	public void acceptParams(byte[] bytes) {
		// fromBytes falls back to defaults on a version mismatch rather than throwing: a client that
		// renders slightly wrong thresholds is better than one that drops the connection.
		params = ClientWeatherParams.fromBytes(bytes);
	}

	public void acceptGrid(VibeWeatherPayloads.GridPayload payload, ClientLevel activeLevel) {
		if (level != activeLevel) {
			clear();
			level = activeLevel;
		}

		// A batched full send arrives as several packets in one tick. Only the first of a burst
		// starts a new blend; the rest patch the grid that burst is building.
		if (ticksSincePacket > 0 || current == null) {
			previous = payload.reset() ? null : current;
			blendTicks = Math.max(1, ticksSincePacket);
			ticksSincePacket = 0;
		}

		WeatherGridBuilder.Grid base = payload.reset() ? null : current;
		WeatherGridBuilder.Grid shell = WeatherGridBuilder.empty(
				payload.originNodeX(), payload.originNodeZ(), payload.halfExtent(), payload.step());
		List<WeatherGridBuilder.Change> changes = WeatherGridBuilder.changesFromBytes(payload.nodes());

		current = WeatherGridBuilder.apply(base, new WeatherGridBuilder.Delta(shell, changes, false));
	}

	/** Called once per client tick with the level and viewer the client is currently showing. */
	public void tick(ClientLevel activeLevel, double x, double y, double z) {
		if (activeLevel == null || level != activeLevel) {
			clear();
			level = activeLevel;
			return;
		}

		viewerX = x;
		viewerY = y;
		viewerZ = z;

		if (ticksSincePacket < Integer.MAX_VALUE) {
			ticksSincePacket++;
		}
	}

	public void clear() {
		current = null;
		previous = null;
		ticksSincePacket = 0;
		blendTicks = 1;
	}

	/**
	 * How far the blend from the previous grid to the current one has come.
	 *
	 * <p>Clamped at 1 rather than extrapolating: when an update is late, holding the newest known
	 * weather is right, and guessing past it would make a stalled connection look like the storm is
	 * accelerating.
	 */
	private float progress(float partialTick) {
		return MathUtil.clamp01((ticksSincePacket + partialTick) / blendTicks);
	}

	// -------------------------------------------------------------------- WeatherHooks.ClientSource

	@Override
	public boolean ready() {
		return current != null;
	}

	@Override
	public float rainLevel(float partialTick) {
		return atViewer(GridCodec.FIELD_PRECIP, partialTick);
	}

	@Override
	public float thunderLevel(float partialTick) {
		return atViewer(GridCodec.FIELD_THUNDER, partialTick);
	}

	@Override
	public WeatherSample sampleAt(double x, double y, double z) {
		if (current == null) {
			return WeatherSample.CLEAR;
		}

		float altitude = params.altitudeFactor(y);
		WeatherSample now = GridInterpolator.sample(current, x, z, altitude, params.rules());

		if (previous == null) {
			return now;
		}

		return GridInterpolator.blend(
				GridInterpolator.sample(previous, x, z, altitude, params.rules()),
				now,
				progress(0.0F),
				params.rules());
	}

	@Override
	public boolean precipitates(int x, int y, int z) {
		if (current == null) {
			return false;
		}

		float altitude = params.altitudeFactor(y);

		if (unit(x, z, GridCodec.FIELD_PRECIP, 0.0F) * altitude <= 0.0F) {
			return false;
		}

		return WeatherSample.precipitatesAt(WeatherSample.DITHER_SEED, x, z,
				unit(x, z, GridCodec.FIELD_COVERAGE, 0.0F), altitude);
	}

	// ------------------------------------------------------------------- allocation-free readers

	/** One axis at a world position, blended in time. No allocation: safe inside the column sweep. */
	public float unit(double x, double z, int field, float partialTick) {
		float now = GridInterpolator.unitAt(current, x, z, field);

		if (previous == null) {
			return now;
		}

		return MathUtil.lerp(progress(partialTick), GridInterpolator.unitAt(previous, x, z, field), now);
	}

	/** The wind bearing at a world position, blended the short way around. */
	public float windDirection(double x, double z, float partialTick) {
		float now = GridInterpolator.angleAt(current, x, z);

		if (previous == null) {
			return now;
		}

		return MathUtil.lerpAngle(progress(partialTick), GridInterpolator.angleAt(previous, x, z), now);
	}

	/** Fog thickness where the viewer is, for the fog hook. */
	public float fogAtViewer() {
		return atViewer(GridCodec.FIELD_FOG, 0.0F);
	}

	/**
	 * Wind strength where the viewer is, for the precipitation tilt.
	 *
	 * <p>One reading per frame rather than one per column. Wind varies over hundreds of blocks and
	 * the rain curtain is fifteen deep, so a per-column reading would cost a thousand lookups a frame
	 * to produce the same number.
	 */
	public float windStrengthAtViewer() {
		return atViewer(GridCodec.FIELD_WIND, 0.0F);
	}

	/** Wind bearing where the viewer is. */
	public float windBearingAtViewer() {
		return current == null ? 0.0F : windDirection(viewerX, viewerZ, 0.0F);
	}

	/**
	 * The value at whoever is looking, for the two level-wide methods that have no position argument.
	 *
	 * <p>Those exist in vanilla because its weather is global. With local weather the only defensible
	 * answer on the client is "here", which is also what every one of their callers means: the sky
	 * darkening, the fog, and the intensity the renderer scales by.
	 */
	private float atViewer(int field, float partialTick) {
		if (current == null || level == null) {
			return 0.0F;
		}

		return unit(viewerX, viewerZ, field, partialTick) * params.altitudeFactor(viewerY);
	}
}
