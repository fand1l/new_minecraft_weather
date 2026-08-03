package com.fand1l.vibeweather.mixin;

import java.util.random.RandomGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import com.fand1l.vibeweather.VibeWeather;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.config.VibeWeatherConfig;
import com.fand1l.vibeweather.server.ServerWeatherManager;
import com.fand1l.vibeweather.server.effects.ThunderDriver;

/**
 * M10: lightning driven by the thunder axis rather than by rain.
 *
 * <p>Vanilla's version has three rain gates -- a global isRaining, an isThundering that is itself
 * multiplied by rain, and an isRainingAt on the strike target. After M2 the last of those reads
 * false wherever nothing is falling, so a dry thunderstorm would produce dark clouds, rumble, and
 * not one bolt. Invariant 2 says dry storms are legal and should happen, so the body is replaced.
 *
 * <p>Everything downstream of those gates is reproduced from the real 26.2 body -- the skeleton trap
 * horse, its game rule, its difficulty roll, the lightning rod exemption, and the bolt -- because a
 * second, divergent lightning path would drift from vanilla over time, and the brief requires
 * vanilla mechanics to stay as they are. Two things are deliberately not copied: the profiler
 * section, which would name a package this project has not read for no behavioural gain, and the
 * random source, which is the mod's seeded generator so that a world replays the same way.
 *
 * <p>What stays vanilla for free is the restriction to loaded chunks: this method is called from
 * chunk ticking, so replacing its body changes nothing about where lightning can occur.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelThunderMixin {
	/**
	 * Walks the heightmap to a strike point, avoiding water and preferring a nearby lightning rod.
	 * Protected in vanilla, so it needs a shadow rather than a cast.
	 */
	@Shadow
	protected abstract BlockPos findLightningTargetAround(BlockPos pos);

	@Inject(method = "tickThunder", at = @At("HEAD"), cancellable = true)
	private void vibeweather$localThunder(LevelChunk chunk, CallbackInfo ci) {
		ServerLevel level = (ServerLevel) (Object) this;
		ServerWeatherManager manager = VibeWeather.managerFor(level);

		if (manager == null) {
			return;
		}

		ci.cancel();

		if (manager.frozen()) {
			return;
		}

		VibeWeatherConfig config = VibeWeather.config().get();
		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();

		WeatherSample sample = manager.sampleAt(minX + 8.0, level.getSeaLevel(), minZ + 8.0);
		RandomGenerator random = manager.random();

		if (!ThunderDriver.shouldStrike(sample, config.toRules(), config, random)) {
			return;
		}

		// Vanilla seeds this with getBlockRandomPos(minX, 0, minZ, 15), which draws a uniform point in
		// the chunk from the level's own LCG. This draws the same uniform point from the mod's seeded
		// generator instead, keeping every roll in this method on one stream. The y is discarded
		// either way -- findLightningTargetAround walks the heightmap from there.
		BlockPos target = findLightningTargetAround(
				new BlockPos(minX + random.nextInt(16), 0, minZ + random.nextInt(16)));

		// Gated on thunder at the target, not on rain there. That single substitution is what makes
		// a dry storm strike at all.
		if (manager.sampleAt(target.getX(), target.getY(), target.getZ()).effectiveThunder() <= 0.0F) {
			return;
		}

		// From here down this is vanilla's tail, line for line, read out of 26.2 rather than recalled:
		// the game rule first, then the difficulty roll, then the rod check, then the horse and the
		// bolt. The order matters beyond taste -- && short-circuits, so moving the rod check ahead of
		// the roll would consume a different number of values from the generator.
		DifficultyInstance difficulty = level.getCurrentDifficultyAt(target);
		boolean trap = level.getGameRules().get(GameRules.SPAWN_MOBS)
				&& ThunderDriver.isTrapStrike(
						difficulty.getEffectiveDifficulty(),
						level.getBlockState(target.below()).is(BlockTags.LIGHTNING_RODS),
						random);

		if (trap) {
			SkeletonHorse horse = EntityTypes.SKELETON_HORSE.create(level, EntitySpawnReason.EVENT);

			if (horse != null) {
				horse.setTrap(true);
				horse.setAge(0);
				horse.setPos(target.getX(), target.getY(), target.getZ());
				level.addFreshEntity(horse);
			}
		}

		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);

		if (bolt != null) {
			bolt.snapTo(Vec3.atBottomCenterOf(target));
			bolt.setVisualOnly(trap);
			level.addFreshEntity(bolt);
		}
	}
}
