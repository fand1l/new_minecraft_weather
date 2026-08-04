package com.fand1l.vibeweather.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import com.fand1l.vibeweather.api.ClientWeatherParams;
import com.fand1l.vibeweather.api.WindPhysics;
import com.fand1l.vibeweather.weather.GridCodec;

/**
 * Wind on the player who is holding the keyboard.
 *
 * <p>Client-side because the client simulates its own player's movement and the server only checks
 * the result. A wind applied from the server would arrive as a velocity packet every tick, and the
 * player would spend the whole gale being snapped backwards. The constants still come from the
 * server -- see {@link ClientWeatherParams#wind()} -- so the file remains the only place they live.
 *
 * <p>Three exclusions, all deliberate: spectators, creative flight, and being under a roof. The first
 * two are in the brief; the third is not, but wind that shoves you around inside your own base is
 * the kind of thing that gets a mod uninstalled.
 */
public final class WindPhysicsClient {
	/** Squared horizontal speed above which a player counts as moving under their own power. */
	private static final double MOVING_SPEED_SQUARED = 1.0E-4;

	private WindPhysicsClient() {
	}

	public static void tick(LocalPlayer player) {
		ClientWeatherState state = ClientWeatherState.get();

		if (!state.ready()) {
			return;
		}

		ClientWeatherParams params = state.params();
		WindPhysics wind = params.wind();

		if (!wind.enabled()) {
			return;
		}

		if (wind.skipSpectators() && player.isSpectator()) {
			return;
		}

		if (wind.skipCreativeFlight() && player.getAbilities().flying) {
			return;
		}

		Vec3 position = player.position();

		if (!player.level().canSeeSky(player.blockPosition())) {
			return;
		}

		float strength = state.unit(position.x, position.z, GridCodec.FIELD_WIND, 0.0F)
				* params.altitudeFactor(position.y);
		float bearing = state.windDirection(position.x, position.z, 0.0F);
		float gale = params.rules().windGale();

		if (player.isFallFlying()) {
			glide(player, wind, strength, bearing, gale);
			return;
		}

		Vec3 velocity = player.getDeltaMovement();
		boolean moving = velocity.x * velocity.x + velocity.z * velocity.z > MOVING_SPEED_SQUARED;
		double push = wind.magnitude(
				moving ? WindPhysics.Target.PLAYER_MOVING : WindPhysics.Target.PLAYER_STANDING,
				strength, gale);

		if (push <= 0.0) {
			return;
		}

		apply(player, WindPhysics.pushX(push, bearing), WindPhysics.pushZ(push, bearing));
	}

	/**
	 * Elytra flight: pushed along the glide, not sideways.
	 *
	 * <p>A crosswind does nothing, so flying a detour to put the wind behind you is a real decision
	 * rather than a fixed tax on every journey.
	 */
	private static void glide(LocalPlayer player, WindPhysics wind, float strength, float bearing, float gale) {
		float facing = WindPhysics.bearingFromYaw(player.getYRot());
		double along = wind.elytraPush(strength, bearing, facing, gale);

		if (along == 0.0) {
			return;
		}

		apply(player, WindPhysics.pushX(along, facing), WindPhysics.pushZ(along, facing));
	}

	/** Adds to the horizontal velocity without touching the vertical one. */
	private static void apply(LocalPlayer player, double x, double z) {
		Vec3 velocity = player.getDeltaMovement();
		// The three-double setter rather than addDeltaMovement: no vector is allocated, and this runs
		// every tick for as long as the wind blows.
		player.setDeltaMovement(velocity.x + x, velocity.y, velocity.z + z);
	}
}
