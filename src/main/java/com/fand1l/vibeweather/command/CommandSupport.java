package com.fand1l.vibeweather.command;

import java.util.Locale;
import java.util.function.Predicate;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import com.fand1l.vibeweather.VibeWeather;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.config.VibeWeatherConfig;
import com.fand1l.vibeweather.server.ServerWeatherManager;

/** Shared plumbing for both command trees. */
final class CommandSupport {
	/** Brigadier's convention: a positive return means success. */
	static final int SUCCESS = 1;

	private CommandSupport() {
	}

	/**
	 * The permission gate, re-read per check.
	 *
	 * <p>26.2 has no integer permission levels left -- {@code Commands.LEVEL_GAMEMASTERS} and friends
	 * are permission-check objects -- so the config names a tier instead. Building the check on each
	 * evaluation rather than once at registration means {@code /vibeweather reload} actually changes
	 * who may run the command, instead of silently waiting for a restart.
	 */
	static Predicate<CommandSourceStack> permission() {
		return source -> checkFor(VibeWeather.config().get().commands.permissionTier).test(source);
	}

	static Predicate<CommandSourceStack> checkFor(String tier) {
		// `var` rather than the declared type: the constants' class is verified to exist on Commands,
		// but which package it lives in is not, and inference does not need to be told.
		var check = switch (tier == null ? "" : tier.trim().toUpperCase(Locale.ROOT)) {
			case "ALL" -> Commands.LEVEL_ALL;
			case "MODERATORS" -> Commands.LEVEL_MODERATORS;
			case "ADMINS" -> Commands.LEVEL_ADMINS;
			case "OWNERS" -> Commands.LEVEL_OWNERS;
			// Anything unrecognised lands on the tier vanilla /weather itself uses. A typo in the
			// config must not open the command up, so the fallback is the stricter reading.
			default -> Commands.LEVEL_GAMEMASTERS;
		};

		return Commands.hasPermission(check);
	}

	/** The weather manager for the source's dimension, or null with the failure already reported. */
	static ServerWeatherManager managerOrFail(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		ServerWeatherManager manager = VibeWeather.managerFor(level);

		if (manager == null) {
			source.sendFailure(Component.translatable("commands.vibeweather.no_weather"));
		}

		return manager;
	}

	/**
	 * Drops a command-made zone at the source's position.
	 *
	 * <p>Every set path goes through here, including the intercepted vanilla {@code /weather}, so the
	 * two cannot drift apart in how they treat radius, duration or the override cap.
	 */
	static void applyOverride(
			CommandSourceStack source,
			ServerWeatherManager manager,
			WeatherState state,
			double radius,
			int durationTicks
	) {
		VibeWeatherConfig config = VibeWeather.config().get();
		WeatherRules rules = config.toRules();
		Vec3 position = source.getPosition();

		manager.zoneManager().addOverride(
				position.x,
				position.z,
				radius,
				config.toSpawnParams().blendBand(radius),
				state.sanitize(rules),
				source.getLevel().getGameTime(),
				durationTicks,
				config.commands.maxOverrides,
				rules);

		// Weather that answers two seconds after the command reads as a broken command.
		manager.refreshGrids();
	}

	/** Localised name of an axis band, e.g. {@code vibeweather.precip.downpour}. */
	static Component band(String axis, Enum<?> value) {
		return Component.translatable("vibeweather." + axis + "." + value.name().toLowerCase(Locale.ROOT));
	}

	static String round(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
