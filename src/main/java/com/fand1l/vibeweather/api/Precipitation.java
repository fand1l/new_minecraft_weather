package com.fand1l.vibeweather.api;

/**
 * Precipitation axis. Derived from {@link WeatherState#precip()}; see {@link CloudCover} for why.
 *
 * <p>Relative densities are intentionally not encoded here -- how much weaker {@code DRIZZLE} is
 * than vanilla rain, and how much heavier {@code DOWNPOUR} is, are tuning values and live in the
 * config via {@link WeatherRules}.
 *
 * <p>In cold biomes these same four levels render as snow. The axis does not distinguish them:
 * rain-versus-snow is a property of the biome at a position, not of the weather state.
 */
public enum Precipitation {
	NONE,
	DRIZZLE,
	RAIN,
	DOWNPOUR;

	private static final Precipitation[] VALUES = values();

	public static Precipitation byIndex(int index) {
		return VALUES[Math.clamp(index, 0, VALUES.length - 1)];
	}

	public static int count() {
		return VALUES.length;
	}

	public boolean isWet() {
		return this != NONE;
	}
}
