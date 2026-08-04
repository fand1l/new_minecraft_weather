package com.fand1l.vibeweather.server.effects;

import com.fand1l.vibeweather.api.Precipitation;
import com.fand1l.vibeweather.config.VibeWeatherConfig;

/**
 * How much faster water collects and snow piles up, per precipitation band.
 *
 * <p>Vanilla has one rate for "it is raining". The brief asks for both to scale with how hard it is
 * coming down, which is only meaningful once precipitation has levels -- so the multiplier lives
 * here, and the mixin that applies it stays a reproduction of vanilla's own body.
 */
public final class PrecipitationEffects {
	private PrecipitationEffects() {
	}

	/**
	 * Multiplier on vanilla's cauldron fill rate.
	 *
	 * <p>Drizzle is deliberately below one: a fine drizzle filling a cauldron as fast as a downpour
	 * is exactly the flattening the level axis exists to undo.
	 */
	public static float cauldronScale(Precipitation band, VibeWeatherConfig config) {
		if (!config.gameplay.fasterCauldronFill) {
			return 1.0F;
		}

		return switch (band) {
			case NONE -> 0.0F;
			case DRIZZLE -> config.gameplay.cauldronDrizzleScale;
			case RAIN -> config.gameplay.cauldronRainScale;
			case DOWNPOUR -> config.gameplay.cauldronDownpourScale;
		};
	}

	/**
	 * Multiplier on vanilla's snow accumulation rate.
	 *
	 * <p>Only a downpour is configured to differ. Snow depth is capped by a game rule and each layer
	 * is a visible block change, so accelerating the lighter bands as well would mostly produce more
	 * block updates for the same final depth.
	 */
	public static float snowScale(Precipitation band, VibeWeatherConfig config) {
		if (!config.gameplay.fasterSnowAccumulation) {
			return 1.0F;
		}

		return switch (band) {
			case NONE -> 0.0F;
			case DRIZZLE, RAIN -> 1.0F;
			case DOWNPOUR -> config.gameplay.snowDownpourScale;
		};
	}
}
