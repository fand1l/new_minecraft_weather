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

		for (int i = 0; i < zones.size(); i++) {
			WeatherZone zone = zones.get(i);

			if (!zone.couldAffect(x, z)) {
				continue;
			}

			float weight = zone.weightAt(x, z);

			if (weight <= 0.0F) {
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
			windX += (float) Math.sin(radians) * state.windStrength() * weight;
			windZ += (float) Math.cos(radians) * state.windStrength() * weight;
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
