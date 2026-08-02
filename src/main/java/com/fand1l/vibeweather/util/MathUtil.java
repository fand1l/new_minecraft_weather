package com.fand1l.vibeweather.util;

/**
 * Allocation-free scalar helpers shared by the weather model and the renderer.
 *
 * <p>Everything here is a static method on primitives: these run inside the render loop and
 * inside per-column loops, where allocating even a small object per call is not acceptable.
 */
public final class MathUtil {
	private MathUtil() {
	}

	public static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}

	public static float clamp01(float value) {
		return clamp(value, 0.0F, 1.0F);
	}

	public static double clamp(double value, double min, double max) {
		return value < min ? min : Math.min(value, max);
	}

	public static float lerp(float t, float a, float b) {
		return a + (b - a) * t;
	}

	/**
	 * Hermite interpolation between {@code edge0} and {@code edge1}, clamped outside them.
	 * Used for every soft boundary in the mod: zone edges, the cloud-height cutoff, and the
	 * cloud factor that gates precipitation.
	 */
	public static float smoothstep(float edge0, float edge1, float x) {
		if (edge0 == edge1) {
			return x < edge0 ? 0.0F : 1.0F;
		}

		float t = clamp01((x - edge0) / (edge1 - edge0));
		return t * t * (3.0F - 2.0F * t);
	}

	/** Normalises any angle into {@code [0, 360)}. Negative inputs wrap correctly. */
	public static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
	}

	/**
	 * Shortest signed angular difference from {@code from} to {@code to}, in {@code (-180, 180]}.
	 * Wind direction is a continuous axis, so every comparison has to go the short way round --
	 * otherwise a gust crossing north spins the whole sky the long way.
	 */
	public static float angleDelta(float from, float to) {
		float delta = wrapDegrees(to - from);
		return delta > 180.0F ? delta - 360.0F : delta;
	}

	/** Interpolates angles along the shortest arc. */
	public static float lerpAngle(float t, float from, float to) {
		return wrapDegrees(from + angleDelta(from, to) * t);
	}

	/**
	 * Moves {@code current} towards {@code target} by at most {@code maxStep}, landing exactly on
	 * the target rather than approaching it asymptotically.
	 *
	 * <p>The exactness matters: the precipitation fade terminates only because this returns the
	 * target itself once it is within reach. An exponential approach would leave a residue that
	 * never reaches the dead zone.
	 */
	public static float approach(float current, float target, float maxStep) {
		float delta = target - current;

		if (Math.abs(delta) <= maxStep) {
			return target;
		}

		return current + Math.copySign(maxStep, delta);
	}

	/** Same as {@link #approach} but along the shortest arc, for the wind direction axis. */
	public static float approachAngle(float current, float target, float maxStep) {
		float delta = angleDelta(current, target);

		if (Math.abs(delta) <= maxStep) {
			return wrapDegrees(target);
		}

		return wrapDegrees(current + Math.copySign(maxStep, delta));
	}

	/** Rate in units-per-tick for a transition that should cover the full 0..1 range in {@code ticks}. */
	public static float ratePerTick(int ticks) {
		return ticks <= 0 ? 1.0F : 1.0F / ticks;
	}
}
