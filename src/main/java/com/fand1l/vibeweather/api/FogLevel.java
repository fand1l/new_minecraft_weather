package com.fand1l.vibeweather.api;

/**
 * Fog axis. Derived from {@link WeatherState#fog()}.
 *
 * <p>Fully independent of every other axis: fog may occur under any cloud cover, with or without
 * precipitation. Nothing in {@link WeatherState#sanitize} touches it.
 *
 * <p>Note that this is only the <em>standalone</em> fog axis. Reduced visibility during heavy
 * precipitation arrives separately, through the rain level, because vanilla already shrinks fog
 * distance as rain intensifies.
 */
public enum FogLevel {
	NONE,
	LIGHT,
	THICK;

	private static final FogLevel[] VALUES = values();

	public static FogLevel byIndex(int index) {
		return VALUES[Math.clamp(index, 0, VALUES.length - 1)];
	}

	public static int count() {
		return VALUES.length;
	}
}
