package com.fand1l.vibeweather.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.renderer.CloudRenderer;

import com.fand1l.vibeweather.client.ClientWeatherState;

/**
 * M11: the cloud cover axis, expressed as the opacity of vanilla's cloud layer.
 *
 * <p>Vanilla clouds are a single layer with no notion of how much sky they take up, so there is
 * nothing to turn up or down -- except that {@code LevelRenderer} already gates the whole cloud pass
 * on {@code ARGB.alpha(cloudColor) > 0}. That makes the colour's alpha the one dial vanilla already
 * respects, and scaling it is enough to take the sky from empty to fully covered.
 *
 * <p>So this mixin adds no geometry and no pass of its own. It rewrites one integer argument on the
 * way in, which is why it is a {@code @ModifyVariable} and not a replacement: nothing here needs to
 * know how clouds are drawn, only how solid they should look.
 *
 * <p>Scaling rather than overwriting matters. The incoming colour already carries vanilla's own
 * darkening for time of day and for rain, and rain darkening is local because the rain level is.
 * Overwriting the alpha outright would keep the cover but throw away the storm's gloom.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	@ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int vibeweather$applyCloudCover(int cloudColor) {
		ClientWeatherState state = ClientWeatherState.get();

		if (!state.ready()) {
			return cloudColor;
		}

		float opacity = state.rules().cloudOpacity(state.cloudCoverAtViewer());
		int alpha = (cloudColor >>> 24) & 0xFF;
		int scaled = Math.clamp(Math.round(alpha * opacity), 0, 255);

		return (scaled << 24) | (cloudColor & 0x00FFFFFF);
	}
}
