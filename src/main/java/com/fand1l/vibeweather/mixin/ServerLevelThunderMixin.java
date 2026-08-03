package com.fand1l.vibeweather.mixin;

import java.util.random.RandomGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
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
 * <p>The intent is to reproduce the rest exactly -- the bolt, and the skeleton trap horse with its
 * difficulty roll and lightning rod exemption -- because a second, divergent lightning path would
 * drift from vanilla over time and the brief requires vanilla mechanics to stay as they are. Right
 * now only the bolt is here; see the note at the trap site for why, and what closes it.
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

		BlockPos target = findLightningTargetAround(
				new BlockPos(minX + random.nextInt(16), 0, minZ + random.nextInt(16)));

		// Gated on thunder at the target, not on rain there. That single substitution is what makes
		// a dry storm strike at all.
		if (manager.sampleAt(target.getX(), target.getY(), target.getZ()).effectiveThunder() <= 0.0F) {
			return;
		}

		// MISSING ON PURPOSE, NOT FORGOTTEN: vanilla also rolls here for a skeleton trap horse --
		// difficulty-scaled, skipped over a lightning rod, and gated on the mob-spawning game rule.
		// Reproducing it means writing three names I have not read in 26.2 source (the horse class,
		// its trap setters, and the rule constant), and two guesses at this spot have already cost a
		// build round. It goes back in verbatim once ./tools/show-source.sh has printed the real
		// tickThunder body; ThunderDriver.isTrapStrike already holds the roll, unchanged.
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);

		if (bolt != null) {
			bolt.snapTo(Vec3.atBottomCenterOf(target));
			level.addFreshEntity(bolt);
		}
	}
}
