package com.fand1l.vibeweather.api;

/**
 * Thunder axis. Derived from {@link WeatherState#thunder()}.
 *
 * <p>Independent of precipitation on purpose. Vanilla ties the two together twice over -- its
 * {@code getThunderLevel} multiplies by the rain level, and its per-chunk thunder tick refuses to
 * strike unless it is raining at the target position -- which makes a dry thunderstorm impossible.
 * Both are overridden precisely so that thunder without rain is a reachable, and reasonably
 * common, state.
 *
 * <p>{@code WEAK} uses the same mechanics as {@code NORMAL}; only the strike interval differs, and
 * by a large factor.
 */
public enum ThunderLevel {
	OFF,
	WEAK,
	NORMAL;

	private static final ThunderLevel[] VALUES = values();

	public static ThunderLevel byIndex(int index) {
		return VALUES[Math.clamp(index, 0, VALUES.length - 1)];
	}

	public static int count() {
		return VALUES.length;
	}

	public boolean isActive() {
		return this != OFF;
	}
}
