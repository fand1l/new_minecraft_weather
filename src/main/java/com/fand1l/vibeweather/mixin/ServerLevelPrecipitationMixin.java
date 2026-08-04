package com.fand1l.vibeweather.mixin;

import java.util.random.RandomGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

import com.fand1l.vibeweather.VibeWeather;
import com.fand1l.vibeweather.api.Precipitation;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.config.VibeWeatherConfig;
import com.fand1l.vibeweather.server.ServerWeatherManager;
import com.fand1l.vibeweather.server.WeatherHooks;
import com.fand1l.vibeweather.server.effects.PrecipitationEffects;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * M9: cauldrons and snow follow local weather, and follow how hard it is falling.
 *
 * <p>Needed because this method asks the biome directly instead of going through
 * {@code precipitationAt}, so M2 does not reach it. Left alone, cauldrons would fill and snow would
 * pile up across the whole dimension for as long as it rained anywhere in it.
 *
 * <p><b>Why the body is reproduced rather than cancelled.</b> Ice formation sits <em>above</em>
 * vanilla's own {@code isRaining} check in this method -- it is a temperature effect, not a weather
 * one. An earlier version of this mixin cancelled the whole method when nothing was falling locally,
 * which silently stopped water freezing anywhere it was not raining. Reading the real 26.2 body is
 * what found that. So: the freeze runs exactly as vanilla wrote it, and only the precipitation half
 * below is gated and scaled.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPrecipitationMixin {
	@Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
	private void vibeweather$localAccumulation(BlockPos pos, CallbackInfo ci) {
		ServerLevel level = (ServerLevel) (Object) this;
		ServerWeatherManager manager = VibeWeather.managerFor(level);

		if (manager == null) {
			return;
		}

		ci.cancel();

		BlockPos topPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
		BlockPos belowPos = topPos.below();
		Biome biome = level.getBiome(topPos).value();

		// Vanilla's line, in vanilla's place: before any rain check, and unconditional.
		if (biome.shouldFreeze(level, belowPos)) {
			level.setBlockAndUpdate(belowPos, Blocks.ICE.defaultBlockState());
		}

		if (WeatherHooks.precipitationAt((Level) (Object) this, topPos) == Biome.Precipitation.NONE) {
			return;
		}

		VibeWeatherConfig config = VibeWeather.config().get();
		WeatherSample sample = manager.sampleAt(topPos.getX(), topPos.getY(), topPos.getZ());
		Precipitation band = config.toRules().classifyPrecip(sample.effectivePrecip());
		RandomGenerator random = manager.random();

		int snowTicks = MathUtil.stochasticCount(
				PrecipitationEffects.snowScale(band, config), random.nextFloat());
		int cauldronTicks = MathUtil.stochasticCount(
				PrecipitationEffects.cauldronScale(band, config), random.nextFloat());

		for (int i = 0; i < snowTicks; i++) {
			accumulateSnow(level, biome, topPos);
		}

		for (int i = 0; i < cauldronTicks; i++) {
			fillCauldron(level, biome, belowPos);
		}
	}

	/** Vanilla's snow block, unchanged except for being callable more than once a tick. */
	private static void accumulateSnow(ServerLevel level, Biome biome, BlockPos topPos) {
		int maxHeight = level.getGameRules().get(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);

		if (maxHeight <= 0 || !biome.shouldSnow(level, topPos)) {
			return;
		}

		BlockState state = level.getBlockState(topPos);

		if (!state.is(Blocks.SNOW)) {
			level.setBlockAndUpdate(topPos, Blocks.SNOW.defaultBlockState());
			return;
		}

		int currentLayers = state.getValue(SnowLayerBlock.LAYERS);

		if (currentLayers < Math.min(maxHeight, 8)) {
			BlockState newState = state.setValue(SnowLayerBlock.LAYERS, currentLayers + 1);
			Block.pushEntitiesUp(state, newState, level, topPos);
			level.setBlockAndUpdate(topPos, newState);
		}
	}

	/**
	 * Vanilla's cauldron block, likewise.
	 *
	 * <p>The biome still decides rain against snow: that is a property of the place, not of the
	 * weather, and it is what makes a cauldron collect powder snow where it is cold enough.
	 */
	private static void fillCauldron(ServerLevel level, Biome biome, BlockPos belowPos) {
		Biome.Precipitation precipitation = biome.getPrecipitationAt(belowPos, level.getSeaLevel());

		if (precipitation == Biome.Precipitation.NONE) {
			return;
		}

		BlockState belowState = level.getBlockState(belowPos);
		belowState.getBlock().handlePrecipitation(belowState, level, belowPos, precipitation);
	}
}
