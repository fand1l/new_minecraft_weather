package com.fand1l.vibeweather.weather;

import java.util.random.RandomGenerator;

import com.fand1l.vibeweather.api.FogLevel;
import com.fand1l.vibeweather.api.WeatherRules;

/**
 * Decides when fog appears, from context rather than from the other weather axes.
 *
 * <p>Fog is an independent axis: it may occur under any cloud cover, with or without precipitation.
 * What this class adds is that fog is also <em>contextual</em> -- it is markedly more likely just
 * after rain has stopped, around dawn, near large bodies of water, and during a downpour, where it
 * exists specifically to cut visibility.
 *
 * <p>Pure by design: the context arrives as plain numbers and booleans, so this needs no Minecraft
 * types and can be checked without a world.
 *
 * @param baseChance         probability of fog with no context at all
 * @param afterRainChance    added probability shortly after precipitation ends
 * @param afterRainWindow    how long, in ticks, the post-rain window lasts
 * @param dawnChance         added probability during the dawn window
 * @param dawnStartTick      start of the dawn window in day time ticks (day time runs 0..23999)
 * @param dawnEndTick        end of the dawn window in day time ticks
 * @param nearWaterChance    added probability near a large body of water
 * @param downpourChance     added probability while precipitation is at its heaviest
 * @param thickBias          given fog, probability it is thick rather than light
 */
public record FogRules(
		float baseChance,
		float afterRainChance,
		int afterRainWindow,
		float dawnChance,
		int dawnStartTick,
		int dawnEndTick,
		float nearWaterChance,
		float downpourChance,
		float thickBias
) {
	/** Everything the fog decision needs to know about a place and a moment. */
	public record Context(
			long ticksSincePrecipEnded,
			long dayTimeTicks,
			boolean nearLargeWater,
			boolean downpour
	) {
		/**
		 * A context in which no contextual modifier applies, leaving only the base chance.
		 *
		 * <p>The day time is midday, not zero. Tick 0 is sunrise and falls inside the default dawn
		 * window, so a "no context" constant built with 0 would quietly carry the dawn bonus -- and
		 * did, until a harness check reported roughly eight times the expected base chance.
		 */
		public static final Context NONE = new Context(Long.MAX_VALUE, 6000L, false, false);
	}

	public static FogRules defaults() {
		return new FogRules(0.05F, 0.45F, 2400, 0.35F, 22000, 1000, 0.25F, 0.60F, 0.35F);
	}

	public FogRules {
		requireProbability("base_chance", baseChance);
		requireProbability("after_rain_chance", afterRainChance);
		requireProbability("dawn_chance", dawnChance);
		requireProbability("near_water_chance", nearWaterChance);
		requireProbability("downpour_chance", downpourChance);
		requireProbability("thick_bias", thickBias);

		if (afterRainWindow < 0) {
			throw new IllegalArgumentException("fog.after_rain_window must be >= 0, got " + afterRainWindow);
		}
	}

	private static void requireProbability(String name, float value) {
		if (value < 0.0F || value > 1.0F) {
			throw new IllegalArgumentException("fog." + name + " must lie in [0,1], got " + value);
		}
	}

	/**
	 * Combined probability of fog for a context.
	 *
	 * <p>Contexts are treated as independent events rather than summed, so overlapping ones
	 * accumulate towards certainty without ever exceeding it. Summing would let three modest
	 * chances add past 1 and make fog unavoidable whenever a few conditions happened to coincide.
	 */
	public float fogChance(Context context) {
		float miss = 1.0F - baseChance;

		if (context.ticksSincePrecipEnded() >= 0 && context.ticksSincePrecipEnded() < afterRainWindow) {
			miss *= 1.0F - afterRainChance;
		}

		if (isDawn(context.dayTimeTicks())) {
			miss *= 1.0F - dawnChance;
		}

		if (context.nearLargeWater()) {
			miss *= 1.0F - nearWaterChance;
		}

		if (context.downpour()) {
			miss *= 1.0F - downpourChance;
		}

		return 1.0F - miss;
	}

	/**
	 * The dawn window, which may wrap past midnight -- the default runs from tick 22000 to tick
	 * 1000, straddling the day boundary, so a plain range test would never match.
	 */
	public boolean isDawn(long dayTimeTicks) {
		long time = Math.floorMod(dayTimeTicks, 24000L);

		if (dawnStartTick <= dawnEndTick) {
			return time >= dawnStartTick && time <= dawnEndTick;
		}

		return time >= dawnStartTick || time <= dawnEndTick;
	}

	/** Rolls a fog target intensity for a context: zero when no fog forms. */
	public float rollFogTarget(RandomGenerator random, Context context, WeatherRules rules) {
		if (random.nextFloat() >= fogChance(context)) {
			return 0.0F;
		}

		FogLevel level = random.nextFloat() < thickBias ? FogLevel.THICK : FogLevel.LIGHT;
		return rules.fogValue(level);
	}
}
