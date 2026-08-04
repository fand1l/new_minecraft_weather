package com.fand1l.vibeweather.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.renderer.CloudRenderer;

import com.fand1l.vibeweather.client.ClientWeatherState;
import com.fand1l.vibeweather.client.CloudDensityTarget;
import com.fand1l.vibeweather.util.Hashing;

/**
 * M11: cloud cover, as how much of the sky has clouds in it.
 *
 * <p>The first attempt scaled the layer's alpha, and testing said what that really looks like:
 * clouds fading, not thinning. Vanilla draws the same pattern whatever the alpha, so few and
 * overcast differed only in how washed out they were.
 *
 * <p>Reading the renderer showed the honest hook. Clouds are not a sheet: they are a grid of cells
 * built from {@code clouds.png}, and {@code tryBuildCell} skips any cell whose packed data is zero.
 * So cover is a matter of which cells exist -- zero out a share of the array, ask for a rebuild, and
 * the sky genuinely empties out. Still no pipeline, no shader and no geometry of this mod's own.
 *
 * <p>Which cells vanish is decided by hashing their texture coordinates, so a given cloud is either
 * there or not there consistently. Rolling per rebuild would reshuffle the whole sky every twelve
 * blocks the camera moves.
 *
 * <p><b>Known limit.</b> The density is one number for the whole sky, taken where the viewer stands,
 * so you cannot yet see a dense mass over a storm a kilometre away. Making it vary per cell needs
 * each cell's world position, and the cell indices here are wrapped into the texture and offset by
 * the cloud scroll, so that mapping is not something to guess at.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin implements CloudDensityTarget {
	/** Stable across sessions: the same world always thins the same clouds. */
	@Unique
	private static final long VIBEWEATHER_CLOUD_SEED = 0x436C_6F75_6473_0001L;

	@Shadow
	private CloudRenderer.TextureData texture;

	@Shadow
	public abstract void markForRebuild();

	/** The unfiltered cells as the resource loader produced them. */
	@Unique
	private long[] vibeweather$source;

	/** The array this mixin last installed, so a resource reload is told apart from its own work. */
	@Unique
	private long[] vibeweather$installed;

	/** Density already applied, quantised, so an unchanged sky is never rebuilt. */
	@Unique
	private int vibeweather$appliedStep = Integer.MIN_VALUE;

	@Override
	public void vibeweather$setCloudDensity(float density) {
		if (texture == null) {
			return;
		}

		// A fresh texture from a resource reload is whatever this mixin did not install.
		if (texture.cells() != vibeweather$installed) {
			vibeweather$source = texture.cells();
			vibeweather$appliedStep = Integer.MIN_VALUE;
		}

		// Quantised, because rebuilding the mesh on every imperceptible change of a float would
		// rebuild it every frame during a transition.
		int step = Math.clamp(Math.round(density * 16.0F), 0, 16);

		if (step == vibeweather$appliedStep) {
			return;
		}

		vibeweather$appliedStep = step;
		float fraction = step / 16.0F;
		long[] source = vibeweather$source;
		long[] filtered;

		if (fraction >= 1.0F) {
			filtered = source;
		} else {
			filtered = new long[source.length];
			int width = texture.width();

			for (int i = 0; i < source.length; i++) {
				if (source[i] == 0L) {
					continue;
				}

				int x = i % width;
				int y = i / width;
				filtered[i] = Hashing.unitFloat(VIBEWEATHER_CLOUD_SEED, x, y) < fraction ? source[i] : 0L;
			}
		}

		vibeweather$installed = filtered;
		texture = new CloudRenderer.TextureData(filtered, texture.width(), texture.height());
		markForRebuild();
	}

	/**
	 * Sinks the cloud layer as cover fills in.
	 *
	 * <p>Density alone says how much sky is covered; height says what kind of sky it is. A heavy
	 * overcast sits low and close, a few clouds sit high and far. It is the cheapest of the two
	 * dials -- vanilla hands the height over as a plain argument.
	 */
	@ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private float vibeweather$applyCloudHeight(float cloudHeight) {
		ClientWeatherState state = ClientWeatherState.get();

		if (!state.ready()) {
			return cloudHeight;
		}

		float cover = state.rules().cloudDensity(state.cloudCoverAtViewer());
		return cloudHeight - state.params().cloudHeightDrop() * cover;
	}
}
