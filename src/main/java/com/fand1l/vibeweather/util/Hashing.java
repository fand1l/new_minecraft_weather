package com.fand1l.vibeweather.util;

/**
 * Deterministic integer hashing.
 *
 * <p>Two independent parts of the mod need randomness that is <em>stable for a point in space</em>
 * rather than fresh per call:
 *
 * <ul>
 *   <li>the soft zone edge, which is drawn by switching whole precipitation columns on and off --
 *       a per-frame RNG there would make the boundary flicker every tick;</li>
 *   <li>zone identity, so a zone's rolled parameters can be reproduced from its id.</li>
 * </ul>
 *
 * <p>Only integer mixing is used. {@code String.hashCode}, {@code Object.hashCode} and
 * {@code HashMap} iteration order are all forbidden in deterministic paths -- they are stable
 * within a run but not necessarily across JVMs, which is exactly the guarantee we need.
 */
public final class Hashing {
	private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

	private Hashing() {
	}

	/** SplitMix64 finaliser: strong avalanche, no state, no allocation. */
	public static long mix64(long value) {
		long z = value + GOLDEN_GAMMA;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** Mixes a world seed and a pair of block coordinates into a stable 64-bit value. */
	public static long hashPos(long seed, int x, int z) {
		return mix64(seed ^ mix64((long) x * 0x2545F4914F6CDD1DL ^ ((long) z << 32) ^ z));
	}

	/**
	 * Stable value in {@code [0, 1)} for a block column.
	 *
	 * <p>This is the dither source for the soft zone edge: a column is drawn when this is below
	 * the local coverage. Because it depends only on position and seed, the set of drawn columns
	 * is fixed in world space, so the boundary dissolves smoothly instead of shimmering.
	 */
	public static float unitFloat(long seed, int x, int z) {
		return toUnitFloat(hashPos(seed, x, z));
	}

	/** Maps any 64-bit value into {@code [0, 1)} using the high bits, which mix best. */
	public static float toUnitFloat(long hash) {
		return (hash >>> 40) * 0x1.0p-24F;
	}
}
