package com.fand1l.vibeweather.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import com.fand1l.vibeweather.server.WeatherHooks;

/**
 * M9: gates cauldron filling and snow accumulation on local weather.
 *
 * <p>Needed because this method asks the biome directly rather than going through precipitationAt,
 * so M2 does not cover it. Without this gate, cauldrons would fill and snow would pile up across the
 * entire dimension for as long as it rained anywhere in it -- locality silently failing in the one
 * place hardest to notice.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPrecipitationMixin {
	@Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
	private void vibeweather$gateAccumulation(BlockPos pos, CallbackInfo ci) {
		Level level = (Level) (Object) this;

		if (!WeatherHooks.handles(level)) {
			return;
		}

		// Ice formation sits outside vanilla's own rain check in this method and is a temperature
		// effect rather than a weather one, so it is left alone: only the precipitation half is
		// gated, by cancelling when nothing is falling here.
		if (WeatherHooks.precipitationAt(level, pos) == Biome.Precipitation.NONE) {
			ci.cancel();
		}
	}
}
