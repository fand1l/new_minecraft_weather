package com.fand1l.vibeweather.server.effects;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.api.WindPhysics;
import com.fand1l.vibeweather.config.VibeWeatherConfig;
import com.fand1l.vibeweather.server.ServerWeatherManager;

/**
 * Wind on everything the server owns: mobs, projectiles, and crewed boats.
 *
 * <p>Players are absent on purpose. Their movement is simulated by their own client, so a push
 * applied here would be overwritten and then corrected, which is what rubber-banding is. The client
 * pushes them with the same constants instead.
 *
 * <p>Entities are gathered around each player rather than across the dimension: wind that nobody can
 * see does not need computing, and this keeps the cost proportional to players rather than to the
 * size of the world. The visited set is what stops an entity standing between two players from being
 * pushed twice as hard as one standing between none.
 */
public final class WindDriver {
	/** Reused between ticks so a busy server does not allocate a set sixty times a second. */
	private final Set<Entity> visited = new HashSet<>();

	public void tick(ServerLevel level, ServerWeatherManager manager, VibeWeatherConfig config) {
		WindPhysics wind = config.toWindPhysics();

		if (!wind.enabled()) {
			return;
		}

		float gale = config.toRules().windGale();
		double reach = config.wind.entityRadius;
		visited.clear();

		for (ServerPlayer player : level.players()) {
			Vec3 centre = player.position();
			AABB box = new AABB(
					centre.x - reach, centre.y - reach, centre.z - reach,
					centre.x + reach, centre.y + reach, centre.z + reach);

			for (Entity entity : level.getEntitiesOfClass(Entity.class, box, WindDriver::affected)) {
				if (visited.add(entity)) {
					push(level, manager, entity, wind, gale);
				}
			}
		}
	}

	/**
	 * Whether the wind touches this entity at all.
	 *
	 * <p>A boat is only pushed with someone aboard. An empty boat drifting off a shoreline is a lost
	 * boat, and the brief calls for exactly this exception.
	 */
	private static boolean affected(Entity entity) {
		if (entity.isSpectator() || entity instanceof Player) {
			return false;
		}

		if (entity instanceof AbstractBoat) {
			return entity.getControllingPassenger() instanceof Player;
		}

		return entity instanceof LivingEntity || entity instanceof Projectile;
	}

	private static void push(
			ServerLevel level,
			ServerWeatherManager manager,
			Entity entity,
			WindPhysics wind,
			float gale
	) {
		// Under a roof there is no wind, the same rule the player gets. Checked before sampling
		// because it rejects most indoor entities without touching the weather at all.
		if (!level.canSeeSky(entity.blockPosition())) {
			return;
		}

		Vec3 position = entity.position();
		WeatherSample sample = manager.sampleAt(position.x, position.y, position.z);
		float strength = sample.state().windStrength() * sample.altitudeFactor();

		WindPhysics.Target target;

		if (entity instanceof AbstractBoat) {
			target = WindPhysics.Target.BOAT;
		} else if (entity instanceof Projectile) {
			target = WindPhysics.Target.ARROW;
		} else {
			target = WindPhysics.Target.MOB;
		}

		double magnitude = wind.magnitude(target, strength, gale);

		if (magnitude <= 0.0) {
			return;
		}

		float bearing = sample.state().windDirection();
		Vec3 velocity = entity.getDeltaMovement();
		entity.setDeltaMovement(
				velocity.x + WindPhysics.pushX(magnitude, bearing),
				velocity.y,
				velocity.z + WindPhysics.pushZ(magnitude, bearing));
	}
}
