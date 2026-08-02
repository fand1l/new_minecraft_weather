package com.fand1l.vibeweather.weather;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherState;

/**
 * Owns the live zone list for one dimension: spawning, ticking, and retiring.
 *
 * <p>Kept free of Minecraft types like everything else in this package. All it needs from the
 * server is where the players are, which is a pair of coordinates and a view distance -- so the
 * spawn and cull rules, the part most likely to go subtly wrong, stay testable without a world.
 *
 * <p>Nothing here reads or loads a chunk. Zones live in dimension-level saved data and are simulated
 * purely from coordinates, which is what keeps the promise that weather neither is stored per chunk
 * nor forces chunks to load.
 */
public final class ZoneManager {
	/** A player's position and how far they can see, in blocks. */
	public record Anchor(double x, double z, double viewRadius) {
	}

	/**
	 * @param targetZonesPerAnchor how many zones to keep near each player
	 * @param maxZones             hard cap on stored zones, so saved data cannot grow without bound
	 * @param keepRadius           a zone with no player inside this radius is a candidate for retirement
	 * @param unloadedTtlTicks     how long it may stay a candidate before being dropped
	 */
	public record Settings(int targetZonesPerAnchor, int maxZones, double keepRadius, long unloadedTtlTicks) {
		public static Settings defaults() {
			return new Settings(3, 512, 2048.0, 72000L);
		}

		public Settings {
			if (targetZonesPerAnchor < 0) {
				throw new IllegalArgumentException(
						"zones.target_zones_per_player must be >= 0, got " + targetZonesPerAnchor);
			}

			if (maxZones < 1) {
				throw new IllegalArgumentException("zones.max_persisted_zones must be >= 1, got " + maxZones);
			}

			if (keepRadius <= 0.0) {
				throw new IllegalArgumentException("zones.keep_radius must be positive, got " + keepRadius);
			}
		}
	}

	private final List<WeatherZone> zones = new ArrayList<>();
	private long nextId = 1L;

	public List<WeatherZone> zones() {
		return Collections.unmodifiableList(zones);
	}

	public int size() {
		return zones.size();
	}

	public long nextId() {
		return nextId;
	}

	/** Restores state loaded from disk, continuing ids so a reload cannot reuse one. */
	public void restore(List<WeatherZone> loaded, long nextId) {
		zones.clear();
		zones.addAll(loaded);
		this.nextId = Math.max(nextId, 1L);

		for (WeatherZone zone : loaded) {
			this.nextId = Math.max(this.nextId, zone.id() + 1L);
		}
	}

	public void add(WeatherZone zone) {
		zones.add(zone);
	}

	/** Advances every zone, retires the finished ones, and tops the population back up. */
	public void tick(
			long gameTick,
			List<Anchor> anchors,
			WeatherRules rules,
			WeatherTransitions transitions,
			ZoneSpawnParams params,
			Settings settings,
			boolean frozen,
			RandomGenerator random
	) {
		for (int i = 0; i < zones.size(); i++) {
			WeatherZone zone = zones.get(i);
			zone.tick(gameTick, rules, transitions, frozen);

			if (isNearAnyAnchor(zone, anchors, settings.keepRadius())) {
				zone.markSeen(gameTick);
			}
		}

		retire(gameTick, settings);

		if (!frozen) {
			topUp(gameTick, anchors, rules, transitions, params, settings, random);
		}
	}

	private static boolean isNearAnyAnchor(WeatherZone zone, List<Anchor> anchors, double keepRadius) {
		for (int i = 0; i < anchors.size(); i++) {
			Anchor anchor = anchors.get(i);
			double dx = zone.centerX() - anchor.x();
			double dz = zone.centerZ() - anchor.z();
			double reach = keepRadius + zone.radius();

			if (dx * dx + dz * dz <= reach * reach) {
				return true;
			}
		}

		return false;
	}

	private void retire(long gameTick, Settings settings) {
		zones.removeIf(zone -> zone.isDead(gameTick)
				|| gameTick - zone.lastSeenTick() > settings.unloadedTtlTicks());

		// The cap is a guard against unbounded saved data, so if it is ever exceeded the oldest
		// zones go first -- they are the ones closest to expiring anyway.
		while (zones.size() > settings.maxZones()) {
			int oldest = 0;

			for (int i = 1; i < zones.size(); i++) {
				if (zones.get(i).deathTick() < zones.get(oldest).deathTick()) {
					oldest = i;
				}
			}

			zones.remove(oldest);
		}
	}

	private void topUp(
			long gameTick,
			List<Anchor> anchors,
			WeatherRules rules,
			WeatherTransitions transitions,
			ZoneSpawnParams params,
			Settings settings,
			RandomGenerator random
	) {
		if (anchors.isEmpty()) {
			return;
		}

		int wanted = settings.targetZonesPerAnchor() * anchors.size();

		// One spawn per tick at most. The population converges within seconds either way, and a
		// burst would put several zones on the same bearing at the same moment.
		if (countNear(anchors, params) >= Math.min(wanted, settings.maxZones())
				|| zones.size() >= settings.maxZones()) {
			return;
		}

		Anchor anchor = anchors.get(random.nextInt(anchors.size()));
		zones.add(spawnNear(anchor, gameTick, rules, transitions, params, random));
	}

	private int countNear(List<Anchor> anchors, ZoneSpawnParams params) {
		int count = 0;

		for (int i = 0; i < zones.size(); i++) {
			WeatherZone zone = zones.get(i);

			for (int j = 0; j < anchors.size(); j++) {
				Anchor anchor = anchors.get(j);
				double dx = zone.centerX() - anchor.x();
				double dz = zone.centerZ() - anchor.z();
				double reach = anchor.viewRadius() + params.spawnMargin() + zone.radius();

				if (dx * dx + dz * dz <= reach * reach) {
					count++;
					break;
				}
			}
		}

		return count;
	}

	/**
	 * Creates a zone outside the player's view so weather drifts in rather than switching on.
	 *
	 * <p>The centre is placed at least {@code viewRadius + radius} away, which puts the zone's near
	 * <em>edge</em> beyond the horizon rather than just its centre. Placing the centre in a ring and
	 * leaving it there would still let a 1000-block zone spawned 800 blocks away swallow the player
	 * on the very first tick.
	 */
	public WeatherZone spawnNear(
			Anchor anchor,
			long gameTick,
			WeatherRules rules,
			WeatherTransitions transitions,
			ZoneSpawnParams params,
			RandomGenerator random
	) {
		double radius = params.rollRadius(random);
		double band = params.blendBand(radius);
		double bearing = random.nextDouble() * (Math.PI * 2.0);
		double distance = anchor.viewRadius() + radius + band + random.nextDouble() * params.spawnMargin();

		WeatherState initial = transitions.nextState(WeatherState.CLEAR, rules).sanitize(rules);
		double[] drift = params.drift(initial.windDirection(), initial.windStrength());

		long lifetime = (long) transitions.rollDurationTicks() * 3L;
		WeatherZone zone = new WeatherZone(
				nextId++,
				anchor.x() + Math.cos(bearing) * distance,
				anchor.z() + Math.sin(bearing) * distance,
				radius,
				band,
				drift[0],
				drift[1],
				initial,
				gameTick + transitions.rollDurationTicks(),
				gameTick + lifetime,
				rules);
		zone.markSeen(gameTick);
		return zone;
	}

	/**
	 * Inserts a command-driven zone at the front, where the blender's maximum-weight rule lets it
	 * dominate whatever natural weather it overlaps.
	 */
	public WeatherZone addOverride(
			double x,
			double z,
			double radius,
			double band,
			WeatherState state,
			long gameTick,
			long durationTicks,
			WeatherRules rules
	) {
		WeatherZone zone = new WeatherZone(
				nextId++, x, z, radius, band, 0.0, 0.0, state, Long.MAX_VALUE, gameTick + durationTicks, rules);
		zone.forceState(state, rules);
		zone.markSeen(gameTick);
		zones.add(0, zone);
		return zone;
	}

	public void clear() {
		zones.clear();
	}
}
