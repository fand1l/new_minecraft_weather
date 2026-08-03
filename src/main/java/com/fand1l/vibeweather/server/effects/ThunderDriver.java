package com.fand1l.vibeweather.server.effects;

import java.util.random.RandomGenerator;

import com.fand1l.vibeweather.api.ThunderLevel;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.config.VibeWeatherConfig;

/**
 * Decides whether lightning strikes, without touching Minecraft.
 *
 * <p>Split out from the mixin so the frequency rules can be read and reasoned about. The mixin keeps
 * only what genuinely needs the game: finding the strike target and spawning the entity.
 */
public final class ThunderDriver {
	private ThunderDriver() {
	}

	/**
	 * Rolls for a strike this tick in one chunk.
	 *
	 * <p>The gate is the thunder axis alone, never precipitation. Vanilla ties the two together
	 * twice over -- its thunder level is multiplied by rain, and its strike check requires rain at
	 * the target -- which makes a dry thunderstorm dark clouds and rumble with no lightning at all.
	 * Both are bypassed here, which is the entire reason this replaces the vanilla method rather
	 * than merely adjusting its odds.
	 *
	 * @param sample weather at the chunk
	 * @return true when a bolt should be attempted
	 */
	public static boolean shouldStrike(
			WeatherSample sample,
			WeatherRules rules,
			VibeWeatherConfig config,
			RandomGenerator random
	) {
		float thunder = sample.effectiveThunder();

		if (thunder <= 0.0F) {
			return false;
		}

		float multiplier = switch (rules.classifyThunder(thunder)) {
			case OFF -> 0.0F;
			case WEAK -> config.gameplay.thunderWeakMultiplier;
			case NORMAL -> config.gameplay.thunderNormalMultiplier;
		};

		if (multiplier <= 0.0F) {
			return false;
		}

		// Vanilla rolls one in a hundred thousand per chunk per tick. Dividing that interval by the
		// multiplier keeps NORMAL at roughly the vanilla rate while WEAK strikes far more rarely,
		// which is what "the same mechanic, much less often" asks for.
		int interval = Math.max(1, (int) (config.gameplay.thunderStrikeInterval / multiplier));
		return random.nextInt(interval) == 0;
	}

	/**
	 * Whether a strike here should also spawn a skeleton trap, matching vanilla's rule.
	 *
	 * <p>The roll comes before the lightning rod check, in that order, because that is the order
	 * vanilla writes it in and {@code &&} short-circuits: a strike on a rod still consumes a number
	 * from the generator. Swapping the two would change every subsequent roll in the stream.
	 *
	 * <p>Vanilla's mob-spawning game rule is checked by the caller, ahead of this, for the same
	 * reason -- with the rule off, no number is drawn at all.
	 */
	public static boolean isTrapStrike(double effectiveDifficulty, boolean overRod, RandomGenerator random) {
		return random.nextDouble() < effectiveDifficulty * 0.01 && !overRod;
	}

	/** Thunder band at a sample, for the query command and logging. */
	public static ThunderLevel bandAt(WeatherSample sample, WeatherRules rules) {
		return rules.classifyThunder(sample.effectiveThunder());
	}
}
