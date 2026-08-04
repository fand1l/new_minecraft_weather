package com.fand1l.vibeweather.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;

import com.fand1l.vibeweather.client.FogController;

/**
 * M7: the fog axis, applied to the finished fog values.
 *
 * <p>Injected at RETURN and mutating the result, rather than registering a fog environment: 26.2
 * keeps {@code FOG_ENVIRONMENTS} in a private static list with no registration API, so there is no
 * supported way to add one. Every field of {@code FogData} is public, so this needs no accessors and
 * stays four lines.
 *
 * <p>Fog is the one axis that is genuinely independent -- it can sit over a clear sky or a downpour
 * alike -- so it gets its own hook rather than riding on the precipitation one.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void vibeweather$localFog(
			Camera camera,
			int renderDistanceInChunks,
			DeltaTracker deltaTracker,
			float darkenWorldAmount,
			ClientLevel level,
			CallbackInfoReturnable<FogData> cir
	) {
		// The viewer position comes from the client state rather than from the camera: it is already
		// tracked there each tick for the two level-wide weather methods, and one source for "where
		// is the player" is one thing that cannot disagree with itself.
		FogController.apply(cir.getReturnValue());
	}
}
