package com.fand1l.vibeweather.weather;

import java.util.random.RandomGenerator;

import com.fand1l.vibeweather.util.MathUtil;

/**
 * Shape and movement parameters for newly spawned zones.
 *
 * @param minRadius        smallest zone radius in blocks
 * @param maxRadius        largest zone radius in blocks
 * @param sizeBiasSamples  how many uniform draws are averaged for the radius. 1 is a flat random;
 *                         higher values concentrate the distribution towards the middle.
 * @param blendFraction    blend band as a fraction of radius
 * @param gridStep         sample-grid spacing in blocks; sets the floor on the blend band
 * @param driftScale       zone movement speed as a fraction of wind strength
 * @param maxDriftPerTick  hard cap on zone movement, blocks per tick
 * @param spawnMargin      how far beyond a player's view zones are born, in blocks
 */
public record ZoneSpawnParams(
		double minRadius,
		double maxRadius,
		int sizeBiasSamples,
		double blendFraction,
		double gridStep,
		double driftScale,
		double maxDriftPerTick,
		double spawnMargin
) {
	public static ZoneSpawnParams defaults() {
		return new ZoneSpawnParams(64.0, 1024.0, 3, 0.35, 16.0, 0.02, 0.02, 768.0);
	}

	/**
	 * The one config invariant that survived review.
	 *
	 * <p>The blend band has two competing lower and upper bounds: it must span at least two grid
	 * steps or the sample grid cannot resolve the gradient, and it must not exceed half the radius
	 * or the zone has no full-intensity core at all. Both can only hold at once when
	 * {@code minRadius >= 4 * gridStep}.
	 *
	 * <p>An earlier draft instead required the band to be at least two grid steps outright, which
	 * with a 48-block grid meant 96 blocks -- larger than the entire 64-block minimum zone. Such a
	 * zone could never reach full intensity, and nothing in the config would have said so.
	 */
	public ZoneSpawnParams {
		if (minRadius <= 0.0 || maxRadius < minRadius) {
			throw new IllegalArgumentException(
					"zone radius range must be positive and ordered, got " + minRadius + ".." + maxRadius);
		}

		if (gridStep <= 0.0) {
			throw new IllegalArgumentException("grid step must be positive, got " + gridStep);
		}

		if (minRadius < 4.0 * gridStep) {
			throw new IllegalArgumentException(
					"zones.min_radius must be at least 4 x grid.step so the blend band fits: min_radius="
							+ minRadius + ", grid.step=" + gridStep + ", required >= " + (4.0 * gridStep));
		}

		if (sizeBiasSamples < 1) {
			throw new IllegalArgumentException("zones.size_bias_samples must be >= 1, got " + sizeBiasSamples);
		}

		if (blendFraction <= 0.0 || blendFraction > 0.5) {
			throw new IllegalArgumentException(
					"zones.blend_fraction must lie in (0, 0.5], got " + blendFraction);
		}
	}

	/**
	 * Rolls a zone radius, biased towards the middle of the range.
	 *
	 * <p>Averaging several uniform draws gives an Irwin-Hall distribution: a bell peaked at the
	 * centre with thin tails, so radii near the middle are common while both extremes stay rare.
	 * A flat random would make 64-block and 1024-block zones equally likely, which is not what
	 * "skewed towards the middle" means.
	 */
	public double rollRadius(RandomGenerator random) {
		double sum = 0.0;

		for (int i = 0; i < sizeBiasSamples; i++) {
			sum += random.nextDouble();
		}

		return minRadius + (maxRadius - minRadius) * (sum / sizeBiasSamples);
	}

	/**
	 * Blend band width for a zone of the given radius, satisfying both bounds described on the
	 * constructor. The constructor guarantees the clamp has a solution.
	 */
	public double blendBand(double radius) {
		return MathUtil.clamp(radius * blendFraction, 2.0 * gridStep, radius * 0.5);
	}

	/**
	 * Zone movement per tick along the wind bearing.
	 *
	 * <p>Deliberately far slower than the wind the player feels. Zones are advected across game
	 * days, so at visible wind speed a zone would cross kilometres within its own lifetime and the
	 * spawn ring would have to be enormous to compensate. {@code driftScale} decouples the two.
	 *
	 * @param windDirection bearing in degrees the wind blows towards
	 * @param windStrength  wind strength in 0..1
	 * @return two-element {x, z} displacement per tick
	 */
	public double[] drift(float windDirection, float windStrength) {
		double speed = Math.min(windStrength * driftScale, maxDriftPerTick);
		double radians = Math.toRadians(windDirection);
		return new double[] {Math.sin(radians) * speed, Math.cos(radians) * speed};
	}

	/** Largest distance a zone can travel over a lifetime, used to size the simulation ring. */
	public double maxDriftOverTicks(long ticks) {
		return maxDriftPerTick * ticks;
	}
}
