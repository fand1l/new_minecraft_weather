package com.fand1l.vibeweather.mixin.client;

import java.util.List;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;

import com.fand1l.vibeweather.api.ClientWeatherParams;
import com.fand1l.vibeweather.api.WindPhysics;
import com.fand1l.vibeweather.client.ClientWeatherState;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * M6: precipitation leans with the wind.
 *
 * <p>The record vanilla builds per column has no tilt field -- a {@code ColumnInstance} is an x, a z,
 * two heights and a texture offset, and the quad is assembled from those with the top pair directly
 * above the bottom pair. Tilt therefore has to happen where the vertices are written, which is this
 * method and nowhere else.
 *
 * <p><b>Only the geometry is replaced.</b> An earlier plan replaced {@code render} outright, which
 * would have meant reproducing the pipeline selection, the render target, the buffer upload, the
 * uniforms and the two draw calls -- every one of them a 26.2 rendering API this project would then
 * own forever, on a backend that is still experimental. Injecting into the private helper that
 * writes vertices leaves all of that vanilla and makes this mixin one loop. There is no custom
 * pipeline, no shader and no raw graphics call anywhere in this mod.
 *
 * <p>The loop is otherwise vanilla's, line for line: same alpha falloff with distance, same texture
 * coordinates, same column sizes, same vertex order. The single change is that the two top vertices
 * are displaced upwind.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
	/**
	 * Billboard orientation per column offset, not a size, despite the name.
	 *
	 * <p>Vanilla fills a 32 by 32 table once with {@code (-dz/d, dx/d)} -- the unit vector
	 * perpendicular to the line from the camera to that offset. Halved and added to either side of
	 * the column centre, it turns each column into a one-block-wide quad facing the viewer.
	 *
	 * <p>Shadowed rather than recomputed so the quads stay exactly the ones vanilla would have built;
	 * recomputing would be duplicating a lookup table to get the same numbers.
	 */
	@Shadow
	@Final
	private float[] columnSizeX;

	@Shadow
	@Final
	private float[] columnSizeZ;

	@Inject(method = "renderInstances", at = @At("HEAD"), cancellable = true)
	private void vibeweather$tiltedInstances(
			VertexConsumer builder,
			List<WeatherEffectRenderer.ColumnInstance> columns,
			Vec3 cameraPos,
			float maxAlpha,
			int radius,
			float intensity,
			CallbackInfo ci
	) {
		ClientWeatherState state = ClientWeatherState.get();

		if (!state.ready()) {
			return;
		}

		ClientWeatherParams params = state.params();

		if (!params.tiltEnabled()) {
			return;
		}

		// Read once per call, not per column: the curtain is fifteen blocks deep and the wind field
		// changes over hundreds, so a per-column lookup would buy nothing for a thousand reads.
		float strength = state.windStrengthAtViewer();
		float tangent = params.maxTiltTan() * strength;

		if (tangent <= 0.0F) {
			// Dead calm draws exactly what vanilla would, so let vanilla draw it.
			return;
		}

		ci.cancel();

		if (columns.isEmpty()) {
			return;
		}

		float bearing = state.windBearingAtViewer();
		float radiusSq = (float) radius * radius;
		int cameraBlockX = (int) Math.floor(cameraPos.x);
		int cameraBlockZ = (int) Math.floor(cameraPos.z);

		for (WeatherEffectRenderer.ColumnInstance column : columns) {
			float relativeX = (float) (column.x() + 0.5 - cameraPos.x);
			float relativeZ = (float) (column.z() + 0.5 - cameraPos.z);
			float distanceSq = relativeX * relativeX + relativeZ * relativeZ;

			// Vanilla's own fade: full alpha underfoot, half at the edge of the radius.
			float alpha = MathUtil.lerp(Math.min(distanceSq / radiusSq, 1.0F), maxAlpha, 0.5F) * intensity;

			// Same packing as ARGB.white(alpha): opaque white, alpha in the top byte.
			int color = ((int) (alpha * 255.0F) << 24) | 0x00FFFFFF;

			int index = (column.z() - cameraBlockZ + 16) * 32 + column.x() - cameraBlockX + 16;
			float halfSizeX = halfSize(columnSizeX, index);
			float halfSizeZ = halfSize(columnSizeZ, index);

			float y1 = (float) (column.topY() - cameraPos.y);
			float y0 = (float) (column.bottomY() - cameraPos.y);

			// The lean. Rain drifts downwind as it falls, so the older, higher end of a streak is
			// upwind of where it lands -- hence the negative sign, and hence the bottom staying put:
			// the splash belongs on the block the column is over.
			float lean = tangent * (y1 - y0);
			float leanX = (float) -WindPhysics.pushX(lean, bearing);
			float leanZ = (float) -WindPhysics.pushZ(lean, bearing);

			float x0 = relativeX - halfSizeX;
			float x1 = relativeX + halfSizeX;
			float z0 = relativeZ - halfSizeZ;
			float z1 = relativeZ + halfSizeZ;

			float u0 = column.uOffset();
			float u1 = column.uOffset() + 1.0F;
			float v0 = column.bottomY() * 0.25F + column.vOffset();
			float v1 = column.topY() * 0.25F + column.vOffset();
			int light = column.lightCoords();

			builder.addVertex(x0 + leanX, y1, z0 + leanZ).setUv(u0, v0).setColor(color).setLight(light);
			builder.addVertex(x1 + leanX, y1, z1 + leanZ).setUv(u1, v0).setColor(color).setLight(light);
			builder.addVertex(x1, y0, z1).setUv(u1, v1).setColor(color).setLight(light);
			builder.addVertex(x0, y0, z0).setUv(u0, v1).setColor(color).setLight(light);
		}
	}

	/**
	 * Half the billboard vector for a column, or a fixed diagonal when the index is off the table.
	 *
	 * <p>The table only covers offsets within fifteen blocks of the camera and vanilla clamps
	 * nothing, so a weather radius above that would index out of bounds. Rather than depend on the
	 * option never allowing it, an out-of-range column gets a fixed orientation: a quad that does not
	 * turn to face the viewer is worse-looking than one that does, and better than a crashed render
	 * thread.
	 */
	private static float halfSize(float[] sizes, int index) {
		return index < 0 || index >= sizes.length ? 0.5F : sizes[index] / 2.0F;
	}
}
