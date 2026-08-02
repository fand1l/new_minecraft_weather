package com.fand1l.vibeweather.api;

import com.fand1l.vibeweather.util.MathUtil;

/**
 * The full weather tuple at a point: six independent axes, stored as floats.
 *
 * <p>Immutable. The discrete axes ({@link CloudCover}, {@link Precipitation}, {@link ThunderLevel},
 * {@link FogLevel}, {@link WindMode}) are <em>derived</em> from these floats against
 * {@link WeatherRules}, never stored alongside them. One source of truth removes an entire class of
 * bug where the discrete value and its float drift apart.
 *
 * @param clouds        cloud cover, 0..1
 * @param precip        precipitation intensity, 0..1
 * @param thunder       thunder intensity, 0..1, independent of precipitation
 * @param fog           standalone fog density, 0..1
 * @param windStrength  wind strength, 0..1
 * @param windDirection bearing the wind blows towards, degrees in [0, 360)
 */
public record WeatherState(
		float clouds,
		float precip,
		float thunder,
		float fog,
		float windStrength,
		float windDirection
) {
	/** Calm, clear, dry. The ambient state everywhere no zone reaches. */
	public static final WeatherState CLEAR = new WeatherState(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

	/**
	 * Projects this tuple onto the set of legal states.
	 *
	 * <p><b>Pure and idempotent</b>: {@code s.sanitize(r).sanitize(r)} equals {@code s.sanitize(r)}.
	 * It clamps, applies the dead zone, and <em>only ever raises cloud cover</em>. It never lowers
	 * precipitation or thunder.
	 *
	 * <p>That last restriction is the whole design. An earlier version damped precipitation here by
	 * a cloud factor while also raising cloud cover to overcast whenever precipitation was non-zero,
	 * which formed a feedback loop: any surviving drop restored full cloud cover, which restored the
	 * damping factor to one, which stopped the fade. Clearing could never complete.
	 *
	 * <p>The fix was to separate two things that were conflated. Enforcing the invariants is a
	 * timeless projection and belongs here; fading precipitation out as cloud cover drops is a
	 * process over time and belongs in the zone simulation, which sequences it explicitly.
	 *
	 * <p>Invariants enforced:
	 * <ol>
	 *   <li>precipitation above none requires overcast;</li>
	 *   <li>thunder above off requires overcast, but <em>not</em> precipitation -- dry thunder is
	 *       legal and expected;</li>
	 *   <li>overcast with neither precipitation nor thunder is legal and common;</li>
	 *   <li>cloud cover falling below overcast extinguishes precipitation and thunder smoothly --
	 *       enforced by the simulation, not here (see the class note above);</li>
	 *   <li>fog is independent of every other axis and is never touched by the rules above.</li>
	 * </ol>
	 */
	public WeatherState sanitize(WeatherRules rules) {
		float c = MathUtil.clamp01(clouds);
		float p = MathUtil.clamp01(precip);
		float t = MathUtil.clamp01(thunder);
		float f = MathUtil.clamp01(fog);
		float ws = MathUtil.clamp01(windStrength);
		float wd = MathUtil.wrapDegrees(windDirection);

		// Dead zone. A fade converges on zero but only ever gets close; snapping the tail to exactly
		// zero is what lets the simulation observe "this axis is finished" and move on.
		float eps = rules.intensityEpsilon();

		if (p < eps) {
			p = 0.0F;
		}

		if (t < eps) {
			t = 0.0F;
		}

		if (f < eps) {
			f = 0.0F;
		}

		// Invariants 1 and 2. Raise the cloud axis to meet precipitation or thunder; never the
		// reverse. Thunder alone is enough -- it does not require precipitation.
		if (p > 0.0F || t > 0.0F) {
			c = Math.max(c, rules.overcastFloor());
		}

		// Invariant 3 needs no action: overcast with neither is already legal.
		// Invariant 5 needs no action: fog was not touched by anything above.
		return new WeatherState(c, p, t, f, ws, wd);
	}

	/**
	 * Blends two states. Used both for the transition between a zone's current and target state and
	 * for overlapping zones.
	 *
	 * <p>The caller is responsible for calling {@link #sanitize} afterwards: interpolating two legal
	 * states can land between them on a tuple that is not itself legal.
	 */
	public static WeatherState lerp(float t, WeatherState a, WeatherState b) {
		return new WeatherState(
				MathUtil.lerp(t, a.clouds, b.clouds),
				MathUtil.lerp(t, a.precip, b.precip),
				MathUtil.lerp(t, a.thunder, b.thunder),
				MathUtil.lerp(t, a.fog, b.fog),
				MathUtil.lerp(t, a.windStrength, b.windStrength),
				MathUtil.lerpAngle(t, a.windDirection, b.windDirection)
		);
	}

	/** Scales every intensity by {@code factor}, leaving wind direction alone. */
	public WeatherState scaleIntensity(float factor) {
		float k = MathUtil.clamp01(factor);
		return new WeatherState(clouds * k, precip * k, thunder * k, fog * k, windStrength * k, windDirection);
	}

	public CloudCover cloudCover(WeatherRules rules) {
		return rules.classifyClouds(clouds);
	}

	public Precipitation precipitation(WeatherRules rules) {
		return rules.classifyPrecip(precip);
	}

	public ThunderLevel thunderLevel(WeatherRules rules) {
		return rules.classifyThunder(thunder);
	}

	public FogLevel fogLevel(WeatherRules rules) {
		return rules.classifyFog(fog);
	}

	public WindMode windMode(WeatherRules rules) {
		return rules.classifyWind(windStrength);
	}

	/** True when nothing is falling and no storm is active -- the cheap early-out for consumers. */
	public boolean isQuiet() {
		return precip <= 0.0F && thunder <= 0.0F && fog <= 0.0F;
	}

	public WeatherState withClouds(float value) {
		return new WeatherState(value, precip, thunder, fog, windStrength, windDirection);
	}

	public WeatherState withPrecip(float value) {
		return new WeatherState(clouds, value, thunder, fog, windStrength, windDirection);
	}

	public WeatherState withThunder(float value) {
		return new WeatherState(clouds, precip, value, fog, windStrength, windDirection);
	}

	public WeatherState withFog(float value) {
		return new WeatherState(clouds, precip, thunder, value, windStrength, windDirection);
	}

	public WeatherState withWind(float strength, float direction) {
		return new WeatherState(clouds, precip, thunder, fog, strength, direction);
	}
}
