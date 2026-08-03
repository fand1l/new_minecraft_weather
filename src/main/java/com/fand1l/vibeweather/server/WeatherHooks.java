package com.fand1l.vibeweather.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import com.fand1l.vibeweather.VibeWeather;
import com.fand1l.vibeweather.api.WeatherSample;

/**
 * The logic behind the mixins, kept out of them.
 *
 * <p>Mixin classes are hard to read, impossible to unit test and easy to break by accident, so each
 * one is reduced to a call into here. This also means the client and the server can share the same
 * decision -- which matters, because the block a player sees rain falling on has to be the block
 * whose fire goes out.
 */
public final class WeatherHooks {
	/**
	 * How the client answers weather questions. Registered by the client initialiser.
	 *
	 * <p>The mixins live in common code because {@code Level} is shared, but the client's answers
	 * come from a received grid rather than from a simulation. Rather than have common code reach
	 * into the client source set, the client hands over an implementation of this.
	 */
	public interface ClientSource {
		boolean ready();

		float rainLevel(float partialTick);

		float thunderLevel(float partialTick);

		/** Weather at a world position, from the received grid. */
		WeatherSample sampleAt(double x, double y, double z);
	}

	private static ClientSource clientSource;

	private WeatherHooks() {
	}

	public static void setClientSource(ClientSource source) {
		clientSource = source;
	}

	/** Whether this mod is driving weather for a level at all. */
	public static boolean handles(Level level) {
		if (!level.canHaveWeather()) {
			return false;
		}

		return level.isClientSide()
				? clientSource != null && clientSource.ready()
				: VibeWeather.managerFor(level) != null;
	}

	/** Weather at a position, from whichever side is asking. Null when this mod is not driving it. */
	public static WeatherSample sampleAt(Level level, double x, double y, double z) {
		if (!level.canHaveWeather()) {
			return null;
		}

		if (level.isClientSide()) {
			return clientSource != null && clientSource.ready()
					? clientSource.sampleAt(x, y, z)
					: null;
		}

		ServerWeatherManager manager = VibeWeather.managerFor(level);
		return manager == null ? null : manager.sampleAt(x, y, z);
	}

	public static Float rainLevel(Level level, float partialTick) {
		if (!level.canHaveWeather()) {
			return null;
		}

		if (level.isClientSide()) {
			return clientSource != null && clientSource.ready()
					? clientSource.rainLevel(partialTick)
					: null;
		}

		ServerWeatherManager manager = VibeWeather.managerFor(level);
		return manager == null ? null : manager.levelRainLevel();
	}

	/**
	 * Thunder intensity, <em>not</em> multiplied by rain.
	 *
	 * <p>Vanilla's own getThunderLevel multiplies by the rain level, which makes a dry thunderstorm
	 * unrepresentable. Returning thunder on its own is the entire reason that method is overridden.
	 */
	public static Float thunderLevel(Level level, float partialTick) {
		if (!level.canHaveWeather()) {
			return null;
		}

		if (level.isClientSide()) {
			return clientSource != null && clientSource.ready()
					? clientSource.thunderLevel(partialTick)
					: null;
		}

		ServerWeatherManager manager = VibeWeather.managerFor(level);
		return manager == null ? null : manager.levelThunderLevel();
	}

	/**
	 * Precipitation at a block position, replacing the vanilla answer.
	 *
	 * <p>Keeps vanilla's own spatial gates: nothing falls where the sky is not visible, or below the
	 * surface. What changes is that "is it raining" becomes a question about this position rather
	 * than about the whole dimension, and that the zone edge is dithered per column so a soft
	 * boundary is possible at all -- the return type is an enum with no room for a partial value.
	 *
	 * @return the precipitation here, or null to let vanilla answer
	 */
	public static Biome.Precipitation precipitationAt(Level level, BlockPos pos) {
		WeatherSample sample = sampleAt(level, pos.getX(), pos.getY(), pos.getZ());

		if (sample == null) {
			return null;
		}

		if (sample.effectivePrecip() <= 0.0F
				|| !sample.precipitatesAt(WeatherSample.DITHER_SEED, pos.getX(), pos.getZ())) {
			return Biome.Precipitation.NONE;
		}

		if (!level.canSeeSky(pos)) {
			return Biome.Precipitation.NONE;
		}

		if (level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) {
			return Biome.Precipitation.NONE;
		}

		// The biome still decides rain versus snow: that is a property of the place, not of the
		// weather, and the brief asks for the same four intensity levels to fall as snow when cold.
		Biome biome = level.getBiome(pos).value();
		Biome.Precipitation biomeAnswer = biome.getPrecipitationAt(pos, level.getSeaLevel());

		return biomeAnswer == Biome.Precipitation.NONE ? Biome.Precipitation.NONE : biomeAnswer;
	}

	/**
	 * Client-side precipitation, which vanilla answers with different conditions.
	 *
	 * <p>{@code ClientLevel.getPrecipitationAt} checks only that the chunk is loaded, because its
	 * caller has already gated on the rain level. So this variant skips the sky and heightmap tests
	 * and applies only the zone.
	 */
	public static Biome.Precipitation clientPrecipitationAt(Level level, BlockPos pos) {
		WeatherSample sample = sampleAt(level, pos.getX(), pos.getY(), pos.getZ());

		if (sample == null) {
			return null;
		}

		if (sample.effectivePrecip() <= 0.0F
				|| !sample.precipitatesAt(WeatherSample.DITHER_SEED, pos.getX(), pos.getZ())) {
			return Biome.Precipitation.NONE;
		}

		return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
	}
}
