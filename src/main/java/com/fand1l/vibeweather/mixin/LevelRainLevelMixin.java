package com.fand1l.vibeweather.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.Level;

import com.fand1l.vibeweather.server.WeatherHooks;

/**
 * M3: reports our rain and thunder intensity instead of vanilla's.
 *
 * <p>A great deal follows from these two numbers for free: the precipitation renderer reads the rain
 * level as its intensity, AtmosphericFogEnvironment shrinks fog distance from it, and sky darkening
 * and sound volume come from it too. None of that has to be written.
 *
 * <p>Neither method takes a position, so the two sides answer differently. On the client it is the
 * value at the camera, which is exact. On the server it means "somewhere in this dimension" -- a
 * maximum, deliberately, because after these mixins vanilla's rain checks survive only as a gate in
 * front of the position-aware ones, and an average would randomly close that gate.
 *
 * <p>Thunder is returned without vanilla's multiplication by rain. That multiplication is precisely
 * what makes a dry thunderstorm impossible, and dry storms are a requirement.
 */
@Mixin(Level.class)
public abstract class LevelRainLevelMixin {
	@Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
	private void vibeweather$rainLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
		Float level = WeatherHooks.rainLevel((Level) (Object) this, partialTick);

		if (level != null) {
			cir.setReturnValue(level);
		}
	}

	@Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
	private void vibeweather$thunderLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
		Float level = WeatherHooks.thunderLevel((Level) (Object) this, partialTick);

		if (level != null) {
			cir.setReturnValue(level);
		}
	}
}
