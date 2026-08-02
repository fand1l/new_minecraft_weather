package com.fand1l.vibeweather.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Loads, validates and writes {@code config/vibe_weather.json}.
 *
 * <h2>Failure policy</h2>
 * A bad config never takes effect and never stops the server. On any parse or validation failure
 * the previously active config is kept -- defaults on first load -- and the exact reason is logged.
 * Refusing to start would punish a dedicated server for a typo; applying a half-parsed file would
 * be worse still, since the result would be weather that looks subtly wrong with no error anywhere.
 *
 * <p>Reloads are therefore atomic: {@link #reload()} either swaps in a fully validated config or
 * changes nothing at all.
 */
public final class ConfigManager {
	public static final String FILE_NAME = "vibe_weather.json";

	private static final Gson GSON = new GsonBuilder()
			// Java camelCase fields, snake_case keys on disk.
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	/** Outcome of a load or reload, so callers can report it without re-deriving it. */
	public record Result(boolean applied, String message) {
		public static Result ok(String message) {
			return new Result(true, message);
		}

		public static Result rejected(String message) {
			return new Result(false, message);
		}
	}

	private final Path path;
	private volatile VibeWeatherConfig active = new VibeWeatherConfig();

	public ConfigManager(Path configDirectory) {
		this.path = configDirectory.resolve(FILE_NAME);
	}

	/** The config currently in force. Never null, never partially applied. */
	public VibeWeatherConfig get() {
		return active;
	}

	public Path path() {
		return path;
	}

	/**
	 * Reads the file, or writes defaults if it is missing.
	 *
	 * <p>Also rewrites the file after a successful load, so keys added by a mod update appear on
	 * disk with their defaults rather than staying invisible until someone reads the source.
	 */
	public Result load() {
		if (!Files.exists(path)) {
			VibeWeatherConfig defaults = new VibeWeatherConfig();

			try {
				write(defaults);
			} catch (IOException e) {
				return Result.rejected("could not write default config to " + path + ": " + e.getMessage());
			}

			active = defaults;
			return Result.ok("wrote default config to " + path);
		}

		return reload();
	}

	/**
	 * Re-reads the file. Leaves the active config untouched unless the new one parses and validates
	 * completely.
	 */
	public Result reload() {
		VibeWeatherConfig parsed;

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			parsed = GSON.fromJson(reader, VibeWeatherConfig.class);
		} catch (IOException e) {
			return Result.rejected("could not read " + path + ": " + e.getMessage());
		} catch (JsonParseException e) {
			return Result.rejected("malformed JSON in " + path + ": " + e.getMessage());
		}

		if (parsed == null) {
			// An empty or literal-null file. Every key would be missing, so defaults are the honest
			// reading rather than an error.
			parsed = new VibeWeatherConfig();
		}

		// Gson leaves a field alone when the key is absent, but writes null when the key is present
		// with a null value. Replace those so a hand-edited "zones": null cannot reach validation.
		fillMissingSections(parsed);

		try {
			parsed.validate();
		} catch (IllegalArgumentException e) {
			return Result.rejected("invalid config in " + path + ": " + e.getMessage());
		}

		active = parsed;

		try {
			write(parsed);
		} catch (IOException e) {
			// The config is good and already in force; failing to rewrite it is not worth rejecting.
			return Result.ok("loaded config (could not rewrite file: " + e.getMessage() + ")");
		}

		return Result.ok("loaded config from " + path);
	}

	/** Writes via a temporary file and an atomic move, so a crash mid-write cannot truncate it. */
	public void write(VibeWeatherConfig config) throws IOException {
		Files.createDirectories(path.getParent());
		Path temp = path.resolveSibling(FILE_NAME + ".tmp");

		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
			writer.write(System.lineSeparator());
		}

		try {
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicUnsupported) {
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void fillMissingSections(VibeWeatherConfig config) {
		if (config.thresholds == null) {
			config.thresholds = new VibeWeatherConfig.Thresholds();
		}

		if (config.zones == null) {
			config.zones = new VibeWeatherConfig.Zones();
		}

		if (config.grid == null) {
			config.grid = new VibeWeatherConfig.Grid();
		}

		if (config.transitions == null) {
			config.transitions = new VibeWeatherConfig.Transitions();
		}

		if (config.fog == null) {
			config.fog = new VibeWeatherConfig.Fog();
		}

		if (config.wind == null) {
			config.wind = new VibeWeatherConfig.Wind();
		}

		if (config.render == null) {
			config.render = new VibeWeatherConfig.Render();
		}

		if (config.gameplay == null) {
			config.gameplay = new VibeWeatherConfig.Gameplay();
		}

		if (config.commands == null) {
			config.commands = new VibeWeatherConfig.Commands();
		}

		if (config.wind.boatEntityTypes == null) {
			config.wind.boatEntityTypes = new VibeWeatherConfig.Wind().boatEntityTypes;
		}

		if (config.gameplay.serverRainLevelMode == null) {
			config.gameplay.serverRainLevelMode = new VibeWeatherConfig.Gameplay().serverRainLevelMode;
		}
	}
}
