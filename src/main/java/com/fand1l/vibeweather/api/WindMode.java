package com.fand1l.vibeweather.api;

/**
 * Wind strength bands. Unlike the other axes these are ranges over a continuous strength value
 * rather than steps: wind strength is a float first and a mode second.
 *
 * <ul>
 *   <li>{@code CALM} -- no effect at all.</li>
 *   <li>{@code BREEZE} -- visual only: sky streaks and drifting leaf particles.</li>
 *   <li>{@code GALE} -- the same, stronger, plus entity physics.</li>
 * </ul>
 */
public enum WindMode {
	CALM,
	BREEZE,
	GALE;

	private static final WindMode[] VALUES = values();

	public static WindMode byIndex(int index) {
		return VALUES[Math.clamp(index, 0, VALUES.length - 1)];
	}

	/** Whether this mode applies force to entities. Only {@code GALE} does. */
	public boolean hasPhysics() {
		return this == GALE;
	}

	/** Whether sky streak particles should spawn. Suppressed under overcast by the spawner. */
	public boolean hasVisuals() {
		return this != CALM;
	}
}
