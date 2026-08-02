package com.fand1l.vibeweather.api;

/**
 * Cloud cover axis, ordered from clear to fully overcast.
 *
 * <p>This is a <em>derived</em> view of {@link WeatherState#clouds()}: the float is the source of
 * truth and the enum is computed from it against thresholds in {@link WeatherRules}. Storing both
 * independently is what allowed "discrete says one thing, float says another" desynchronisation,
 * so the enum deliberately carries no state of its own.
 */
public enum CloudCover {
	CLEAR,
	FEW,
	SCATTERED,
	OVERCAST;

	private static final CloudCover[] VALUES = values();

	public static CloudCover byIndex(int index) {
		return VALUES[Math.clamp(index, 0, VALUES.length - 1)];
	}

	public static int count() {
		return VALUES.length;
	}

	public boolean atLeast(CloudCover other) {
		return ordinal() >= other.ordinal();
	}
}
