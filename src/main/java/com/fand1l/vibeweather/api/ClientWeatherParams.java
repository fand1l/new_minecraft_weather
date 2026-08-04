package com.fand1l.vibeweather.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Everything the client needs that is decided by the server: the band thresholds, the cloud
 * height, and the render limits.
 *
 * <p>Sent once on join and on reload, since none of it changes during play. Thresholds travel with
 * it so that a band boundary has exactly one definition -- the server's -- rather than the client
 * carrying a second copy that could drift out of step after a config edit.
 *
 * <p>Serialises itself to a byte array rather than to a field-per-value packet. Two reasons: the
 * field count is well past what the composite stream codecs support, and keeping it a byte array
 * means the payload's codec is a single verified primitive instead of hand-written encode and
 * decode lambdas.
 */
public record ClientWeatherParams(
		WeatherRules rules,
		float cloudBottom,
		float cloudTop,
		float maxTiltTan,
		boolean tiltEnabled,
		float rainVolume,
		boolean windStreaks,
		int windStreakBudget,
		float fogThickDistance,
		WindPhysics wind,
		boolean frozen
) {
	/** Bumped when the field layout changes, so a mismatched client is detected rather than misread. */
	public static final int FORMAT_VERSION = 2;

	public static ClientWeatherParams defaults() {
		return new ClientWeatherParams(WeatherRules.defaults(), 192.0F, 224.0F,
				0.85F, true, 1.0F, true, 48, 24.0F, WindPhysics.defaults(), false);
	}

	public byte[] toBytes() {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);

		try (DataOutputStream out = new DataOutputStream(buffer)) {
			out.writeInt(FORMAT_VERSION);

			out.writeFloat(rules.intensityEpsilon());
			out.writeFloat(rules.overcastFloor());
			out.writeFloat(rules.cloudFew());
			out.writeFloat(rules.cloudScattered());
			out.writeFloat(rules.precipDrizzle());
			out.writeFloat(rules.precipRain());
			out.writeFloat(rules.precipDownpour());
			out.writeFloat(rules.thunderWeak());
			out.writeFloat(rules.thunderNormal());
			out.writeFloat(rules.fogLight());
			out.writeFloat(rules.fogThick());
			out.writeFloat(rules.windBreeze());
			out.writeFloat(rules.windGale());
			out.writeInt(rules.precipFadeTicks());
			out.writeInt(rules.thunderFadeTicks());
			out.writeInt(rules.cloudFadeTicks());
			out.writeInt(rules.fogFadeTicks());
			out.writeInt(rules.windFadeTicks());
			out.writeFloat(rules.windTurnDegreesTick());

			out.writeFloat(cloudBottom);
			out.writeFloat(cloudTop);
			out.writeFloat(maxTiltTan);
			out.writeBoolean(tiltEnabled);
			out.writeFloat(rainVolume);
			out.writeBoolean(windStreaks);
			out.writeInt(windStreakBudget);
			out.writeFloat(fogThickDistance);

			out.writeBoolean(wind.enabled());
			out.writeFloat(wind.minStrength());
			out.writeBoolean(wind.skipSpectators());
			out.writeBoolean(wind.skipCreativeFlight());
			out.writeDouble(wind.standingPush());
			out.writeDouble(wind.movingPush());
			out.writeDouble(wind.arrowPush());
			out.writeDouble(wind.mobPush());
			out.writeDouble(wind.boatPush());
			out.writeDouble(wind.elytraTailwind());
			out.writeDouble(wind.elytraHeadwind());
			out.writeDouble(wind.maxPushPerTick());

			out.writeBoolean(frozen);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return buffer.toByteArray();
	}

	/**
	 * Reads params from the wire, falling back to defaults on anything unreadable.
	 *
	 * <p>A version mismatch means a client and server on different mod versions. Rendering with
	 * defaults looks slightly wrong; throwing here would disconnect the player over cosmetics.
	 */
	public static ClientWeatherParams fromBytes(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return defaults();
		}

		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			if (in.readInt() != FORMAT_VERSION) {
				return defaults();
			}

			WeatherRules rules = new WeatherRules(
					in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
					in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
					in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
					in.readFloat(),
					in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
					in.readFloat());

			float cloudBottom = in.readFloat();
			float cloudTop = in.readFloat();
			float maxTiltTan = in.readFloat();
			boolean tiltEnabled = in.readBoolean();
			float rainVolume = in.readFloat();
			boolean windStreaks = in.readBoolean();
			int windStreakBudget = in.readInt();
			float fogThickDistance = in.readFloat();

			// Argument order is guaranteed left to right, so reading inline here is correct; the
			// locals above exist because a fourteen-argument constructor built entirely from
			// identical-looking read calls is impossible to check against the writer by eye.
			WindPhysics wind = new WindPhysics(
					in.readBoolean(), in.readFloat(), in.readBoolean(), in.readBoolean(),
					in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
					in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
			boolean frozen = in.readBoolean();

			return new ClientWeatherParams(rules, cloudBottom, cloudTop,
					maxTiltTan, tiltEnabled, rainVolume, windStreaks, windStreakBudget,
					fogThickDistance, wind, frozen);
		} catch (IOException | IllegalArgumentException e) {
			// Truncated, or thresholds a WeatherRules refuses. Either way the sender is not one we
			// understand, so fall back rather than render from half-read numbers.
			return defaults();
		}
	}

	/** Vertical falloff at a height, from this params' cloud band. */
	public float altitudeFactor(double y) {
		return WeatherSample.altitudeFactor(y, cloudBottom, cloudTop);
	}

	public ClientWeatherParams withFrozen(boolean value) {
		return new ClientWeatherParams(rules, cloudBottom, cloudTop,
				maxTiltTan, tiltEnabled, rainVolume, windStreaks, windStreakBudget,
				fogThickDistance, wind, value);
	}
}
