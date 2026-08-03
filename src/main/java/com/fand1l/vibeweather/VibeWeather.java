package com.fand1l.vibeweather;

import java.util.HashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import com.fand1l.vibeweather.config.ConfigManager;
import com.fand1l.vibeweather.net.VibeWeatherPayloads;
import com.fand1l.vibeweather.server.ServerWeatherManager;
import com.fand1l.vibeweather.util.Hashing;

/**
 * Common entry point. Owns the config and the per-dimension weather managers.
 *
 * <p>Everything with consequences lives elsewhere; this wires it to the game's lifecycle.
 */
public final class VibeWeather implements ModInitializer {
	public static final String MOD_ID = "vibe_weather";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<Level, ServerWeatherManager> MANAGERS = new HashMap<>();
	private static ConfigManager config;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static ConfigManager config() {
		if (config == null) {
			// Commands and mixins can reach for the config before onInitialize on a dedicated
			// server's first tick; a defaults-backed manager is better than a null check at every
			// call site.
			config = new ConfigManager(FabricLoader.getInstance().getConfigDir());
		}

		return config;
	}

	/** The manager for a dimension, or null where weather does not apply. */
	public static ServerWeatherManager managerFor(Level level) {
		return MANAGERS.get(level);
	}

	@Override
	public void onInitialize() {
		config = new ConfigManager(FabricLoader.getInstance().getConfigDir());
		ConfigManager.Result result = config.load();

		if (result.applied()) {
			LOGGER.info("Vibe Weather: {}", result.message());
		} else {
			LOGGER.error("Vibe Weather: {} -- continuing with defaults", result.message());
		}

		VibeWeatherPayloads.register();

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> MANAGERS.values()
				.forEach(ServerWeatherManager::flush));

		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			ServerWeatherManager manager = MANAGERS.get(level);

			if (manager != null) {
				manager.tick();
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> MANAGERS.values()
				.forEach(manager -> manager.forgetPlayer(listener.getPlayer().getUUID())));
	}

	private void onServerStarted(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			// canHaveWeather already excludes the Nether and the End -- it requires sky light, no
			// ceiling, and a dimension other than the End -- so no dimension list of our own is
			// needed, and modded dimensions are covered by the same rule.
			if (!level.canHaveWeather()) {
				LOGGER.info("Vibe Weather: skipping {}, it has no weather", level.dimension());
				continue;
			}

			MANAGERS.put(level, new ServerWeatherManager(level, generatorFor(level)));
		}

		LOGGER.info("Vibe Weather: managing weather in {} dimension(s)", MANAGERS.size());
	}

	private void onServerStopping(MinecraftServer server) {
		MANAGERS.values().forEach(ServerWeatherManager::flush);
		MANAGERS.clear();
	}

	/**
	 * A generator seeded from the world and dimension.
	 *
	 * <p>Seeded rather than shared so two dimensions do not roll identical weather, and so a world
	 * replays the same way given the same actions -- useful when tracking down a report of weather
	 * behaving oddly.
	 */
	private static RandomGenerator generatorFor(ServerLevel level) {
		// The dimension key is hashed through its string form rather than through a method on
		// ResourceKey. ResourceKey.location() does not exist in 26.2 -- it was presumably renamed
		// alongside ResourceLocation becoming Identifier -- and its new name is not something this
		// project has verified. Its own hashCode would be identity-based and differ between runs,
		// which would defeat the point of seeding at all.
		long seed = level.getSeed() ^ Hashing.hashString(level.dimension().toString());
		return RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(seed);
	}

	/** Re-reads the config and pushes the result to every connected player. */
	public static ConfigManager.Result reload() {
		ConfigManager.Result result = config().reload();

		if (result.applied()) {
			MANAGERS.values().forEach(ServerWeatherManager::resendEverything);
		}

		return result;
	}
}
