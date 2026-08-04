package com.fand1l.vibeweather.api;

import com.fand1l.vibeweather.util.MathUtil;

/**
 * How hard the wind pushes, and on what.
 *
 * <p>Separate from the weather model because the client needs every one of these numbers: the local
 * player's movement is simulated client-side, so a wind that pushed players from the server would
 * either arrive as a stream of velocity packets or rubber-band. Sending the constants once and
 * applying them locally is the only version of this that feels right, and it keeps the promise that
 * nothing is hard-coded.
 *
 * <h2>The bearing convention, in one place</h2>
 * A wind direction is the bearing it blows <em>towards</em>, and the unit vector for a bearing is
 * {@code (sin, cos)} -- so 0 pushes towards +z and 90 towards +x. Zone drift already uses this, and
 * the whole model would silently reverse if any caller assumed "the direction it comes from".
 *
 * @param enabled            master switch for wind physics
 * @param minStrength        below this the wind does nothing at all, not even visually
 * @param skipSpectators     never push a spectator
 * @param skipCreativeFlight never push a creative player who is flying
 * @param standingPush       per-tick push on a player who is not moving under their own power
 * @param movingPush         per-tick push on a moving player
 * @param arrowPush          per-tick push on arrows and other projectiles
 * @param mobPush            per-tick push on other entities
 * @param boatPush           per-tick push on a boat, applied only with a player aboard
 * @param elytraTailwind     along-track push while gliding with the wind
 * @param elytraHeadwind     along-track push while gliding into it
 * @param maxPushPerTick     hard ceiling on any single push, so no combination can launch anything
 */
public record WindPhysics(
		boolean enabled,
		float minStrength,
		boolean skipSpectators,
		boolean skipCreativeFlight,
		double standingPush,
		double movingPush,
		double arrowPush,
		double mobPush,
		double boatPush,
		double elytraTailwind,
		double elytraHeadwind,
		double maxPushPerTick
) {
	/** What is being pushed. Kept as an enum so the caller cannot pass the wrong constant by mistake. */
	public enum Target {
		PLAYER_STANDING,
		PLAYER_MOVING,
		MOB,
		ARROW,
		BOAT
	}

	public static WindPhysics defaults() {
		return new WindPhysics(true, 0.05F, true, true,
				0.0015, 0.006, 0.004, 0.003, 0.008, 0.020, 0.014, 0.05);
	}

	/**
	 * Push magnitude for one entity this tick, already clamped. Zero means leave it alone.
	 *
	 * <p>Physics starts at gale, not at the first breath of wind: the brief has calm doing nothing,
	 * breeze doing visuals only, and gale being the one that moves things. Passing the gale threshold
	 * in rather than storing it keeps a single source of truth for where that band begins.
	 */
	public double magnitude(Target target, float strength, float galeThreshold) {
		if (!enabled || strength < minStrength || strength < galeThreshold) {
			return 0.0;
		}

		double base = switch (target) {
			case PLAYER_STANDING -> standingPush;
			case PLAYER_MOVING -> movingPush;
			case MOB -> mobPush;
			case ARROW -> arrowPush;
			case BOAT -> boatPush;
		};

		return Math.min(base * strength, maxPushPerTick);
	}

	/**
	 * Along-track push for a gliding player: positive with the wind, negative into it.
	 *
	 * <p>Scaled by how aligned the glide is with the wind, so a crosswind neither helps nor hinders --
	 * which is what makes flying a detour downwind a real choice rather than a fixed tax.
	 *
	 * @param facing the bearing the player is gliding along, in the same convention as the wind
	 */
	public double elytraPush(float strength, float windDirection, float facing, float galeThreshold) {
		if (!enabled || strength < galeThreshold) {
			return 0.0;
		}

		double alignment = Math.cos(Math.toRadians(MathUtil.angleDelta(windDirection, facing)));
		double scale = alignment >= 0.0 ? elytraTailwind : elytraHeadwind;
		return Math.clamp(alignment * scale * strength, -maxPushPerTick, maxPushPerTick);
	}

	/** X component of a push along a bearing. */
	public static double pushX(double magnitude, float bearing) {
		return Math.sin(Math.toRadians(bearing)) * magnitude;
	}

	/** Z component of a push along a bearing. */
	public static double pushZ(double magnitude, float bearing) {
		return Math.cos(Math.toRadians(bearing)) * magnitude;
	}

	/**
	 * A Minecraft entity yaw as a wind bearing.
	 *
	 * <p>Minecraft's yaw 0 faces +z and grows clockwise from above, so its direction vector is
	 * {@code (-sin, cos)} while a bearing here is {@code (sin, cos)}. The two agree only after
	 * negating. This is the single place that conversion happens; getting its sign wrong would make
	 * every tailwind a headwind, which is why it is one named function rather than a minus sign
	 * scattered through the callers.
	 */
	public static float bearingFromYaw(float yRot) {
		return MathUtil.wrapDegrees(-yRot);
	}
}
