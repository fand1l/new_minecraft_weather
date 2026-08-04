package com.fand1l.vibeweather.weather;

import java.util.List;

import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * Resolves a list of zones into a single {@link WeatherSample} at a point.
 *
 * <p>Allocation-free apart from the returned sample: this runs once per grid node when building a
 * player's weather grid, and once per query for every gameplay hook, so it must not churn.
 */
public final class ZoneBlender {
	/**
	 * Smallest share a zone gets when its bearing is combined with others.
	 *
	 * <p>Bearings are accumulated as vectors scaled by wind strength, so that a gale outvotes a
	 * breeze instead of being averaged into a compromise nobody asked for. Taken literally that has a
	 * hole: a zone with zero strength contributes a zero-length vector, so a field of calm zones
	 * collapses to a bearing of zero no matter what direction they actually store. That is not
	 * academic -- {@code /vibeweather set wind_direction 270} in calm weather stored 270 and read
	 * back as 0, and the rain leaned the wrong way or not at all.
	 *
	 * <p>The floor keeps a calm zone's bearing alive without letting it steer a windy one.
	 */
	private static final float BEARING_FLOOR = 0.05F;

	private ZoneBlender() {
	}

	/**
	 * Blends every zone covering {@code (x, z)}, weighted by how deep the point sits in each.
	 *
	 * <p>Where no zone reaches, the result is the ambient clear state with zero coverage -- which is
	 * what lets a player fly sideways out of a storm into open sky.
	 *
	 * <p>Coverage is the <em>maximum</em> weight rather than the sum. Summing would let two zones
	 * that each half-cover a point add up to full coverage, hardening a boundary that should stay
	 * soft; the max keeps the softest edge intact and stays within 0..1 without normalisation.
	 *
	 * <h2>Commands win outright</h2>
	 * If any zone covering the point came from a command, the natural zones are dropped entirely and
	 * only overrides are blended. Without this, an override is one voice in an average: with fifteen
	 * zones around a player, {@code /vibeweather set thunder normal} produced 0.33 and
	 * {@code set wind gale} produced 0.09 -- below the gale threshold, so neither the tilt nor the
	 * physics ever ran. "Set" has to mean set.
	 */
	public static WeatherSample sample(
			List<WeatherZone> zones,
			double x,
			double y,
			double z,
			float cloudBottom,
			float cloudTop,
			WeatherRules rules
	) {
		float totalWeight = 0.0F;
		float maxWeight = 0.0F;

		float clouds = 0.0F;
		float precip = 0.0F;
		float thunder = 0.0F;
		float fog = 0.0F;
		float windStrength = 0.0F;

		// Wind direction is an angle, so it cannot be averaged directly -- 350 and 10 degrees would
		// average to 180, the exact opposite. Accumulate as a vector and take the bearing at the end.
		float windX = 0.0F;
		float windZ = 0.0F;

		boolean overrideMode = false;

		for (int i = 0; i < zones.size(); i++) {
			WeatherZone zone = zones.get(i);

			if (!zone.couldAffect(x, z)) {
				continue;
			}

			float weight = zone.weightAt(x, z);

			if (weight <= 0.0F) {
				continue;
			}

			boolean fromCommand = zone.isCommandOverride();

			if (fromCommand && !overrideMode) {
				// First command zone found: everything natural gathered so far stops counting.
				overrideMode = true;
				totalWeight = 0.0F;
				maxWeight = 0.0F;
				clouds = 0.0F;
				precip = 0.0F;
				thunder = 0.0F;
				fog = 0.0F;
				windStrength = 0.0F;
				windX = 0.0F;
				windZ = 0.0F;
			} else if (overrideMode && !fromCommand) {
				continue;
			}

			WeatherState state = zone.state();
			totalWeight += weight;
			maxWeight = Math.max(maxWeight, weight);

			clouds += state.clouds() * weight;
			precip += state.precip() * weight;
			thunder += state.thunder() * weight;
			fog += state.fog() * weight;
			windStrength += state.windStrength() * weight;

			double radians = Math.toRadians(state.windDirection());
			float bearingWeight = Math.max(state.windStrength(), BEARING_FLOOR) * weight;
			windX += (float) Math.sin(radians) * bearingWeight;
			windZ += (float) Math.cos(radians) * bearingWeight;
		}

		float altitude = WeatherSample.altitudeFactor(y, cloudBottom, cloudTop);

		if (totalWeight <= 0.0F) {
			return new WeatherSample(WeatherState.CLEAR, 0.0F, altitude);
		}

		float inverse = 1.0F / totalWeight;
		float direction = windX == 0.0F && windZ == 0.0F
				? 0.0F
				: MathUtil.wrapDegrees((float) Math.toDegrees(Math.atan2(windX, windZ)));

		WeatherState blended = new WeatherState(
				clouds * inverse,
				precip * inverse,
				thunder * inverse,
				fog * inverse,
				windStrength * inverse,
				direction
		).sanitize(rules);

		return new WeatherSample(blended, MathUtil.clamp01(maxWeight), altitude);
	}

	/**
	 * Level-wide maximum precipitation intensity.
	 *
	 * <p>This backs the server-side rain level, which has no position argument and therefore cannot
	 * mean "at your location" when several players stand in different weather. It means "there is
	 * precipitation somewhere in this dimension", and it is a maximum rather than an average
	 * deliberately: after the mixins, vanilla's {@code isRaining} survives only as a permission gate
	 * upstream of the position-aware checks. An average would randomly close that gate and silence
	 * the positional logic that should be deciding; a maximum leaves it open.
	 */
	public static float maxPrecip(List<WeatherZone> zones) {
		float max = 0.0F;

		for (int i = 0; i < zones.size(); i++) {
			max = Math.max(max, zones.get(i).state().precip());
		}

		return max;
	}

	/** Level-wide maximum thunder intensity, with the same "somewhere in this dimension" meaning. */
	public static float maxThunder(List<WeatherZone> zones) {
		float max = 0.0F;

		for (int i = 0; i < zones.size(); i++) {
			max = Math.max(max, zones.get(i).state().thunder());
		}

		return max;
	}
}
