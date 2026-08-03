package com.fand1l.vibeweather.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import com.fand1l.vibeweather.server.WeatherHooks;

/**
 * M2: makes vanilla's weather-driven gameplay local.
 *
 * <p>This is the single position-aware entry point in vanilla -- isRainingAt delegates straight to
 * it, and from there follow fire extinguishing, crop growth, cauldron filling, the fishing bonus and
 * spawn checks. Hooking it once is both cheaper and safer than patching each of those separately,
 * and there is no event for any of them.
 */
@Mixin(Level.class)
public abstract class LevelPrecipitationMixin {
	@Inject(method = "precipitationAt", at = @At("HEAD"), cancellable = true)
	private void vibeweather$localPrecipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
		Biome.Precipitation local = WeatherHooks.precipitationAt((Level) (Object) this, pos);

		if (local != null) {
			cir.setReturnValue(local);
		}
	}
}
