package com.fand1l.vibeweather.weather;

import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * One live weather zone: a moving disc carrying a complete weather tuple.
 *
 * <p>A zone owns all six axes as a unit -- there are no nested zones of differing size per axis.
 * That is a deliberate cost/benefit call: it makes sampling a single pass over a small list.
 *
 * <p>Deliberately free of Minecraft types. A zone is coordinates, floats and ticks, which keeps the
 * simulation testable on its own and means it never touches, or forces loading of, a chunk.
 *
 * <h2>The two-phase transition</h2>
 * Clearing up is ordered, not simultaneous. When a new target has less cloud cover than the current
 * state while precipitation or thunder are still running, the zone enters {@link Phase#FADING}:
 * cloud cover is held still while precipitation and thunder ramp to zero. Only once the dead zone
 * in {@link WeatherState#sanitize} has snapped them to exactly zero does {@link Phase#SHIFTING}
 * begin and cloud cover move.
 *
 * <p>This is where "a drop in cloud cover extinguishes precipitation smoothly" lives. It cannot
 * live in {@code sanitize}, which is a timeless projection -- putting it there produced a feedback
 * loop in which the fade could never finish.
 */
public final class WeatherZone {
	/** Where a zone is in its transition. */
	public enum Phase {
		/** Holding the current state until it expires. */
		STEADY,
		/** Ramping precipitation and thunder to zero while cloud cover waits. */
		FADING,
		/** Moving every axis towards the target. */
		SHIFTING
	}

	private final long id;
	private final double radius;
	private final double blendBand;
	private final double driftX;
	private final double driftZ;

	private double centerX;
	private double centerZ;
	private WeatherState current;
	private WeatherState target;
	private Phase phase;
	private long stateExpiryTick;
	private long deathTick;
	private long lastSeenTick;

	public WeatherZone(
			long id,
			double centerX,
			double centerZ,
			double radius,
			double blendBand,
			double driftX,
			double driftZ,
			WeatherState initial,
			long stateExpiryTick,
			long deathTick,
			WeatherRules rules
	) {
		this.id = id;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.radius = radius;
		this.blendBand = blendBand;
		this.driftX = driftX;
		this.driftZ = driftZ;
		this.current = initial.sanitize(rules);
		this.target = this.current;
		this.phase = Phase.STEADY;
		this.stateExpiryTick = stateExpiryTick;
		this.deathTick = deathTick;
	}

	/**
	 * Rebuilds a zone from persisted fields, including the ones the normal constructor derives.
	 *
	 * <p>A separate factory rather than setters: a zone loaded mid-transition has to come back with
	 * its phase and target intact, and exposing those as setters would invite changing them on a
	 * live zone, where only {@code tick} is allowed to.
	 */
	public static WeatherZone restore(
			long id,
			double centerX,
			double centerZ,
			double radius,
			double blendBand,
			double driftX,
			double driftZ,
			WeatherState current,
			WeatherState target,
			Phase phase,
			long stateExpiryTick,
			long deathTick,
			long lastSeenTick,
			WeatherRules rules
	) {
		WeatherZone zone = new WeatherZone(id, centerX, centerZ, radius, blendBand, driftX, driftZ,
				current, stateExpiryTick, deathTick, rules);
		zone.target = target.sanitize(rules);
		zone.phase = phase;
		zone.lastSeenTick = lastSeenTick;
		return zone;
	}

	/**
	 * Advances the zone by one tick.
	 *
	 * <p>Drift happens even when frozen: freezing stops weather from <em>changing</em>, not the wind
	 * from blowing. Stopping zone movement too would contradict the wind axis still reporting a
	 * direction and strength.
	 */
	public void tick(long gameTick, WeatherRules rules, WeatherTransitions transitions, boolean frozen) {
		centerX += driftX;
		centerZ += driftZ;

		if (frozen) {
			return;
		}

		if (gameTick >= stateExpiryTick) {
			retarget(gameTick, rules, transitions);
		}

		advance(rules);
	}

	private void retarget(long gameTick, WeatherRules rules, WeatherTransitions transitions) {
		target = transitions.nextState(current, rules).sanitize(rules);
		stateExpiryTick = gameTick + transitions.rollDurationTicks();

		boolean cloudsFalling = target.clouds() < current.clouds();
		boolean somethingActive = current.precip() > 0.0F || current.thunder() > 0.0F;

		// Phase one only exists when there is actually something to extinguish first.
		phase = cloudsFalling && somethingActive ? Phase.FADING : Phase.SHIFTING;
	}

	private void advance(WeatherRules rules) {
		switch (phase) {
			case STEADY -> {
			}
			case FADING -> {
				float p = MathUtil.approach(current.precip(), 0.0F, MathUtil.ratePerTick(rules.precipFadeTicks()));
				float t = MathUtil.approach(current.thunder(), 0.0F, MathUtil.ratePerTick(rules.thunderFadeTicks()));

				// Cloud cover is untouched here: holding it up is what makes the world stay overcast
				// while the rain dies out, instead of clearing and raining at the same time.
				current = current.withPrecip(p).withThunder(t).sanitize(rules);

				if (current.precip() == 0.0F && current.thunder() == 0.0F) {
					phase = Phase.SHIFTING;
				}
			}
			case SHIFTING -> {
				WeatherState next = new WeatherState(
						MathUtil.approach(current.clouds(), target.clouds(), MathUtil.ratePerTick(rules.cloudFadeTicks())),
						MathUtil.approach(current.precip(), target.precip(), MathUtil.ratePerTick(rules.precipFadeTicks())),
						MathUtil.approach(current.thunder(), target.thunder(), MathUtil.ratePerTick(rules.thunderFadeTicks())),
						MathUtil.approach(current.fog(), target.fog(), MathUtil.ratePerTick(rules.fogFadeTicks())),
						MathUtil.approach(current.windStrength(), target.windStrength(), MathUtil.ratePerTick(rules.windFadeTicks())),
						MathUtil.approachAngle(current.windDirection(), target.windDirection(), rules.windTurnDegreesTick())
				);
				current = next.sanitize(rules);

				if (arrived(rules)) {
					// Snap exactly onto the target. `arrived` uses a tolerance, and STEADY stops
					// advancing, so without this every axis would park a fraction short of its
					// target and carry that residue until the next retarget -- the same
					// "approaches but never lands" problem the dead zone exists to prevent.
					current = target.sanitize(rules);
					phase = Phase.STEADY;
				}
			}
		}
	}

	private boolean arrived(WeatherRules rules) {
		float eps = rules.intensityEpsilon();
		return Math.abs(current.clouds() - target.clouds()) < eps
				&& Math.abs(current.precip() - target.precip()) < eps
				&& Math.abs(current.thunder() - target.thunder()) < eps
				&& Math.abs(current.fog() - target.fog()) < eps
				&& Math.abs(current.windStrength() - target.windStrength()) < eps
				&& Math.abs(MathUtil.angleDelta(current.windDirection(), target.windDirection())) < 1.0F;
	}

	/**
	 * Spatial weight of this zone at a point, in 0..1: 1 inside the core, ramping to 0 across the
	 * blend band, 0 beyond it. Squared distance is used to keep the hot path free of {@code sqrt}
	 * until the band is actually reached.
	 */
	public float weightAt(double x, double z) {
		double dx = x - centerX;
		double dz = z - centerZ;
		double distanceSq = dx * dx + dz * dz;
		double outer = radius + blendBand;

		if (distanceSq >= outer * outer) {
			return 0.0F;
		}

		if (distanceSq <= radius * radius) {
			return 1.0F;
		}

		double distance = Math.sqrt(distanceSq);
		return 1.0F - MathUtil.smoothstep((float) radius, (float) outer, (float) distance);
	}

	/** Cheap rejection test for the sampler: is this point anywhere near the zone at all. */
	public boolean couldAffect(double x, double z) {
		double dx = x - centerX;
		double dz = z - centerZ;
		double outer = radius + blendBand;
		return dx * dx + dz * dz < outer * outer;
	}

	public boolean isDead(long gameTick) {
		return gameTick >= deathTick;
	}

	/**
	 * Records that a player was within range this tick.
	 *
	 * <p>Drives retirement of zones in regions nobody visits any more. Without it, saved data would
	 * accumulate a zone for every place a player has ever been.
	 */
	public void markSeen(long gameTick) {
		lastSeenTick = Math.max(lastSeenTick, gameTick);
	}

	public long lastSeenTick() {
		return lastSeenTick;
	}

	/** Restores the last-seen tick when loading from disk. */
	public void setLastSeenTick(long tick) {
		lastSeenTick = tick;
	}

	/** Extends the zone's life, used when a command override should outlive its natural span. */
	public void extendLife(long newDeathTick) {
		deathTick = Math.max(deathTick, newDeathTick);
	}

	/** Replaces the state outright. Used by commands, which set weather rather than nudge it. */
	public void forceState(WeatherState state, WeatherRules rules) {
		current = state.sanitize(rules);
		target = current;
		phase = Phase.STEADY;
	}

	public long id() {
		return id;
	}

	public double centerX() {
		return centerX;
	}

	public double centerZ() {
		return centerZ;
	}

	public double radius() {
		return radius;
	}

	public double blendBand() {
		return blendBand;
	}

	public double driftX() {
		return driftX;
	}

	public double driftZ() {
		return driftZ;
	}

	public WeatherState state() {
		return current;
	}

	public WeatherState target() {
		return target;
	}

	public Phase phase() {
		return phase;
	}

	public long stateExpiryTick() {
		return stateExpiryTick;
	}

	public long deathTick() {
		return deathTick;
	}
}
