package com.fand1l.vibeweather.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import com.fand1l.vibeweather.server.WeatherHooks;

/**
 * M4: the client's own precipitation question, answered from the received grid.
 *
 * <p>{@code ClientLevel} does not inherit this from {@code Level} -- it is a separate method with
 * different conditions, because its callers have already checked the rain level. It drives the
 * column sweep in the precipitation renderer, the splash particles and the rain sound, so this one
 * hook is what makes a zone boundary <em>visible</em>: vanilla's sweep already walks the whole
 * weather radius, and now each column in it gets a local answer instead of a global one.
 *
 * <p>Held to one call into shared logic. That logic is allocation-free on purpose: at the configured
 * radius this runs tens of thousands of times per frame.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelPrecipitationMixin {
	@Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
	private void vibeweather$localPrecipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
		Biome.Precipitation local = WeatherHooks.clientPrecipitationAt((Level) (Object) this, pos);

		// Null means this mod is not driving weather here -- another dimension, or the grid has not
		// arrived yet. Then vanilla answers, and the world behaves exactly as it did before.
		if (local != null) {
			cir.setReturnValue(local);
		}
	}
}
