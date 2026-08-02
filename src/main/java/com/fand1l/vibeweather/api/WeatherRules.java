package com.fand1l.vibeweather.api;

import com.fand1l.vibeweather.util.MathUtil;

/**
 * Every threshold and rate the weather model needs, in one immutable object.
 *
 * <p>The model is deliberately free of Minecraft and of config-file plumbing: it takes a
 * {@code WeatherRules} and computes. The config layer builds one of these and hands it over, which
 * keeps the model unit-testable and keeps "nothing is hard-coded" honest -- every number below is
 * config-backed, and {@link #defaults()} exists only so tests and first-run have something to use.
 *
 * <p>Thresholds are lower bounds: a float at or above {@code precipRain} classifies as
 * {@link Precipitation#RAIN} unless it also reaches {@code precipDownpour}.
 *
 * @param intensityEpsilon    below this, an intensity snaps to exactly zero. This dead zone is what
 *                            lets a fade terminate instead of trailing off forever.
 * @param overcastFloor       cloud float that counts as fully overcast; precipitation and thunder
 *                            raise cloud cover to at least this.
 * @param cloudFew            lower bound of {@link CloudCover#FEW}.
 * @param cloudScattered      lower bound of {@link CloudCover#SCATTERED}.
 * @param precipDrizzle       lower bound of {@link Precipitation#DRIZZLE}.
 * @param precipRain          lower bound of {@link Precipitation#RAIN}.
 * @param precipDownpour      lower bound of {@link Precipitation#DOWNPOUR}.
 * @param thunderWeak         lower bound of {@link ThunderLevel#WEAK}.
 * @param thunderNormal       lower bound of {@link ThunderLevel#NORMAL}.
 * @param fogLight            lower bound of {@link FogLevel#LIGHT}.
 * @param fogThick            lower bound of {@link FogLevel#THICK}.
 * @param windBreeze          lower bound of {@link WindMode#BREEZE}.
 * @param windGale            lower bound of {@link WindMode#GALE}.
 * @param precipFadeTicks     ticks for precipitation to fall from full to zero when cloud cover is
 *                            dropping. Phase one of the two-phase clearing transition.
 * @param thunderFadeTicks    same, for thunder.
 * @param cloudFadeTicks      ticks for cloud cover to traverse its full range. Phase two: it only
 *                            starts once precipitation and thunder have reached zero.
 * @param fogFadeTicks        ticks for the fog axis to traverse its full range.
 * @param windFadeTicks       ticks for wind strength to traverse its full range.
 * @param windTurnDegreesTick maximum wind direction change per tick, in degrees. Keeps the wind
 *                            swinging continuously rather than snapping to a new bearing.
 */
public record WeatherRules(
		float intensityEpsilon,
		float overcastFloor,
		float cloudFew,
		float cloudScattered,
		float precipDrizzle,
		float precipRain,
		float precipDownpour,
		float thunderWeak,
		float thunderNormal,
		float fogLight,
		float fogThick,
		float windBreeze,
		float windGale,
		int precipFadeTicks,
		int thunderFadeTicks,
		int cloudFadeTicks,
		int fogFadeTicks,
		int windFadeTicks,
		float windTurnDegreesTick
) {
	public static WeatherRules defaults() {
		return new WeatherRules(
				0.01F,
				0.75F,
				0.20F,
				0.45F,
				0.05F,
				0.35F,
				0.75F,
				0.05F,
				0.50F,
				0.05F,
				0.55F,
				0.25F,
				0.65F,
				600,
				400,
				1200,
				800,
				900,
				0.35F
		);
	}

	/**
	 * Fails fast on a config that cannot express a coherent model, rather than letting an
	 * out-of-order threshold silently produce weather that never leaves one band.
	 */
	public WeatherRules {
		requireAscending("clouds", cloudFew, cloudScattered, overcastFloor);
		requireAscending("precipitation", precipDrizzle, precipRain, precipDownpour);
		requireAscending("thunder", thunderWeak, thunderNormal);
		requireAscending("fog", fogLight, fogThick);
		requireAscending("wind", windBreeze, windGale);

		if (intensityEpsilon <= 0.0F || intensityEpsilon >= precipDrizzle) {
			throw new IllegalArgumentException(
					"intensity_epsilon must be >0 and below the drizzle threshold, got " + intensityEpsilon);
		}
	}

	private static void requireAscending(String axis, float... thresholds) {
		for (int i = 0; i < thresholds.length; i++) {
			if (thresholds[i] <= 0.0F || thresholds[i] >= 1.0F) {
				throw new IllegalArgumentException(axis + " thresholds must lie in (0,1), got " + thresholds[i]);
			}

			if (i > 0 && thresholds[i] <= thresholds[i - 1]) {
				throw new IllegalArgumentException(axis + " thresholds must ascend, got "
						+ thresholds[i - 1] + " then " + thresholds[i]);
			}
		}
	}

	public CloudCover classifyClouds(float value) {
		if (value >= overcastFloor) {
			return CloudCover.OVERCAST;
		}

		if (value >= cloudScattered) {
			return CloudCover.SCATTERED;
		}

		return value >= cloudFew ? CloudCover.FEW : CloudCover.CLEAR;
	}

	public Precipitation classifyPrecip(float value) {
		if (value <= 0.0F) {
			return Precipitation.NONE;
		}

		if (value >= precipDownpour) {
			return Precipitation.DOWNPOUR;
		}

		if (value >= precipRain) {
			return Precipitation.RAIN;
		}

		return value >= precipDrizzle ? Precipitation.DRIZZLE : Precipitation.NONE;
	}

	public ThunderLevel classifyThunder(float value) {
		if (value <= 0.0F) {
			return ThunderLevel.OFF;
		}

		if (value >= thunderNormal) {
			return ThunderLevel.NORMAL;
		}

		return value >= thunderWeak ? ThunderLevel.WEAK : ThunderLevel.OFF;
	}

	public FogLevel classifyFog(float value) {
		if (value <= 0.0F) {
			return FogLevel.NONE;
		}

		if (value >= fogThick) {
			return FogLevel.THICK;
		}

		return value >= fogLight ? FogLevel.LIGHT : FogLevel.NONE;
	}

	public WindMode classifyWind(float value) {
		if (value >= windGale) {
			return WindMode.GALE;
		}

		return value >= windBreeze ? WindMode.BREEZE : WindMode.CALM;
	}

	/** Representative float for a discrete level, used when a command or transition names a band. */
	public float cloudValue(CloudCover cover) {
		return switch (cover) {
			case CLEAR -> 0.0F;
			case FEW -> MathUtil.lerp(0.5F, cloudFew, cloudScattered);
			case SCATTERED -> MathUtil.lerp(0.5F, cloudScattered, overcastFloor);
			case OVERCAST -> 1.0F;
		};
	}

	public float precipValue(Precipitation precipitation) {
		return switch (precipitation) {
			case NONE -> 0.0F;
			case DRIZZLE -> MathUtil.lerp(0.5F, precipDrizzle, precipRain);
			case RAIN -> MathUtil.lerp(0.5F, precipRain, precipDownpour);
			case DOWNPOUR -> 1.0F;
		};
	}

	public float thunderValue(ThunderLevel level) {
		return switch (level) {
			case OFF -> 0.0F;
			case WEAK -> MathUtil.lerp(0.5F, thunderWeak, thunderNormal);
			case NORMAL -> 1.0F;
		};
	}

	public float fogValue(FogLevel level) {
		return switch (level) {
			case NONE -> 0.0F;
			case LIGHT -> MathUtil.lerp(0.5F, fogLight, fogThick);
			case THICK -> 1.0F;
		};
	}

	public float windValue(WindMode mode) {
		return switch (mode) {
			case CALM -> 0.0F;
			case BREEZE -> MathUtil.lerp(0.5F, windBreeze, windGale);
			case GALE -> 1.0F;
		};
	}
}
