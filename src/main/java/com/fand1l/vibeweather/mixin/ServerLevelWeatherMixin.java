package com.fand1l.vibeweather.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;

import com.fand1l.vibeweather.VibeWeather;

/**
 * M1: silences vanilla's weather cycle.
 *
 * <p>Vanilla advances rain and thunder timers here and broadcasts the resulting levels to every
 * client. Both must stop, or two systems would be driving the same numbers and the client would see
 * whichever spoke last.
 *
 * <p>There is no Fabric event for this, and the ADVANCE_WEATHER game rule is not a substitute: it
 * freezes the vanilla state rather than handing it over, and players can see and change it.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
	@Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
	private void vibeweather$suppressVanillaWeather(CallbackInfo ci) {
		if (VibeWeather.managerFor((ServerLevel) (Object) this) != null) {
			ci.cancel();
		}
	}
}
