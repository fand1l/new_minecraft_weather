package com.fand1l.vibeweather.weather;

import java.util.random.RandomGenerator;

import com.fand1l.vibeweather.api.CloudCover;
import com.fand1l.vibeweather.api.Precipitation;
import com.fand1l.vibeweather.api.ThunderLevel;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * Rolls a zone's next target state and how long it lasts. Weighted randomness, no pressure or
 * temperature simulation.
 *
 * <p>Every probability and duration here comes from the config. The record fields are the tuning
 * surface; nothing is hard-coded except the structure of the walk itself.
 *
 * @param neighbourWeight     relative weight of moving precipitation one level
 * @param stayWeight          relative weight of precipitation holding its level
 * @param skipLevelChance     probability of jumping two levels at once, e.g. downpour straight to
 *                            none. Rare by design, but reachable.
 * @param stayOvercastChance  after precipitation ends, probability the sky stays overcast rather
 *                            than clearing immediately
 * @param instantClearChance  probability of going straight to clear skies when precipitation ends
 * @param dryThunderChance    probability of thunder starting with no precipitation at all
 * @param thunderWithRain     probability of thunder accompanying active precipitation
 * @param thunderWeakBias     given thunder, probability it is weak rather than normal
 * @param durationBaseTicks   median state duration; a full Minecraft day is 24000
 * @param durationSigma       log-normal spread. Larger means more very long and very short states.
 * @param durationMinTicks    hard floor on a rolled duration
 * @param durationMaxTicks    hard ceiling on a rolled duration
 * @param windChangeChance    probability the wind re-rolls when the state changes
 * @param windTurnMaxDegrees  largest bearing change a single re-roll may pick
 */
public record WeatherTransitions(
		RandomGenerator random,
		float neighbourWeight,
		float stayWeight,
		float skipLevelChance,
		float stayOvercastChance,
		float instantClearChance,
		float dryThunderChance,
		float thunderWithRain,
		float thunderWeakBias,
		int durationBaseTicks,
		float durationSigma,
		int durationMinTicks,
		int durationMaxTicks,
		float windChangeChance,
		float windTurnMaxDegrees
) {
	public static WeatherTransitions defaults(RandomGenerator random) {
		return new WeatherTransitions(
				random,
				1.0F,
				0.6F,
				0.05F,
				0.75F,
				0.10F,
				0.12F,
				0.35F,
				0.55F,
				24000,
				0.6F,
				4000,
				96000,
				0.5F,
				120.0F
		);
	}

	/**
	 * Duration of the next state.
	 *
	 * <p>Log-normal rather than uniform: the brief says roughly one game day on average with a wide
	 * spread and no hard range, which is exactly what {@code base * exp(sigma * gauss)} gives --
	 * a median at the base, a long right tail, and no possibility of a negative draw.
	 */
	public int rollDurationTicks() {
		double scaled = durationBaseTicks * Math.exp(durationSigma * random.nextGaussian());
		return (int) MathUtil.clamp(scaled, durationMinTicks, durationMaxTicks);
	}

	/** Rolls the complete next target for a zone. */
	public WeatherState nextState(WeatherState current, WeatherRules rules) {
		Precipitation currentPrecip = current.precipitation(rules);
		Precipitation nextPrecip = nextPrecipitation(currentPrecip);
		ThunderLevel nextThunder = nextThunder(nextPrecip);
		CloudCover nextClouds = nextClouds(current, rules, currentPrecip, nextPrecip, nextThunder);

		float windStrength = current.windStrength();
		float windDirection = current.windDirection();

		if (random.nextFloat() < windChangeChance) {
			windStrength = random.nextFloat();
			windDirection = MathUtil.wrapDegrees(
					windDirection + (random.nextFloat() * 2.0F - 1.0F) * windTurnMaxDegrees);
		}

		return new WeatherState(
				rules.cloudValue(nextClouds),
				rules.precipValue(nextPrecip),
				rules.thunderValue(nextThunder),
				current.fog(),
				windStrength,
				windDirection
		);
	}

	/**
	 * Precipitation moves mostly to an adjacent level. A two-level jump is possible but rare, so
	 * downpour normally eases off through rain rather than stopping dead.
	 */
	public Precipitation nextPrecipitation(Precipitation current) {
		int index = current.ordinal();

		if (random.nextFloat() < skipLevelChance) {
			int direction = random.nextBoolean() ? 2 : -2;
			return Precipitation.byIndex(index + direction);
		}

		float down = index > 0 ? neighbourWeight : 0.0F;
		float up = index < Precipitation.count() - 1 ? neighbourWeight : 0.0F;
		float roll = random.nextFloat() * (down + stayWeight + up);

		if (roll < down) {
			return Precipitation.byIndex(index - 1);
		}

		return roll < down + stayWeight ? current : Precipitation.byIndex(index + 1);
	}

	/**
	 * Thunder is rolled from precipitation but is never <em>required</em> to accompany it, and can
	 * occur entirely without it. Dry thunder is a supported state, which is why the mod overrides
	 * vanilla's thunder level instead of reusing it -- vanilla multiplies thunder by rain, making
	 * a dry storm unrepresentable.
	 */
	public ThunderLevel nextThunder(Precipitation precipitation) {
		float chance = precipitation.isWet() ? thunderWithRain : dryThunderChance;

		if (random.nextFloat() >= chance) {
			return ThunderLevel.OFF;
		}

		return random.nextFloat() < thunderWeakBias ? ThunderLevel.WEAK : ThunderLevel.NORMAL;
	}

	/**
	 * Cloud cover follows from the other axes.
	 *
	 * <p>Precipitation or thunder force overcast. When precipitation has just ended the sky usually
	 * stays overcast for a while first, and only sometimes clears outright -- an immediate jump to
	 * blue sky is possible but uncommon.
	 */
	public CloudCover nextClouds(
			WeatherState current,
			WeatherRules rules,
			Precipitation currentPrecip,
			Precipitation nextPrecip,
			ThunderLevel nextThunder
	) {
		if (nextPrecip.isWet() || nextThunder.isActive()) {
			return CloudCover.OVERCAST;
		}

		boolean precipitationJustEnded = currentPrecip.isWet();

		if (precipitationJustEnded) {
			if (random.nextFloat() < instantClearChance) {
				return CloudCover.CLEAR;
			}

			if (random.nextFloat() < stayOvercastChance) {
				return CloudCover.OVERCAST;
			}

			return CloudCover.SCATTERED;
		}

		// Nothing falling and nothing just ended: drift one step in either direction.
		int index = current.cloudCover(rules).ordinal();
		int step = random.nextInt(3) - 1;
		return CloudCover.byIndex(index + step);
	}
}
