package com.fand1l.vibeweather.command;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import com.fand1l.vibeweather.VibeWeather;
import com.fand1l.vibeweather.api.CloudCover;
import com.fand1l.vibeweather.api.Precipitation;
import com.fand1l.vibeweather.api.ThunderLevel;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.config.VibeWeatherConfig;
import com.fand1l.vibeweather.server.ServerWeatherManager;

/**
 * Points vanilla {@code /weather} at the local model.
 *
 * <p><b>No mixin.</b> Brigadier merges a re-registered literal into the existing node and overwrites
 * its executor, so registering {@code weather} again after vanilla did is enough to take the command
 * over -- vanilla's permission check and argument parsers stay exactly as they were. A mixin on
 * {@code WeatherCommand} would need the names of its private static methods, which this project has
 * not read out of 26.2, and it would fight any other mod doing the same thing instead of layering
 * with it.
 *
 * <p>The subcommands are read off the live tree rather than assumed. If Mojang renames one, the
 * result is a line in the log naming what was found, not a stray {@code /weather rain} that silently
 * does nothing. The takeover is verified immediately after registering, for the same reason: a
 * command that quietly failed to install is the one failure mode worth a startup check.
 *
 * <p>What the mapping deliberately leaves alone is wind and fog. Vanilla's three states say nothing
 * about either, so they keep whatever was already blowing or hanging there.
 */
public final class VanillaWeatherBridge {
	/** How a vanilla subcommand name lands on the axes. */
	private interface Mapping {
		WeatherState apply(WeatherState state, WeatherRules rules);
	}

	private static final Map<String, Mapping> MAPPINGS = Map.of(
			"clear", (state, rules) -> state
					.withClouds(rules.cloudValue(CloudCover.CLEAR))
					.withPrecip(0.0F)
					.withThunder(0.0F),
			"rain", (state, rules) -> state
					.withClouds(rules.cloudValue(CloudCover.OVERCAST))
					.withPrecip(rules.precipValue(Precipitation.RAIN))
					.withThunder(0.0F),
			"thunder", (state, rules) -> state
					.withClouds(rules.cloudValue(CloudCover.OVERCAST))
					.withPrecip(rules.precipValue(Precipitation.RAIN))
					.withThunder(rules.thunderValue(ThunderLevel.NORMAL)));

	private VanillaWeatherBridge() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		CommandNode<CommandSourceStack> vanilla = dispatcher.getRoot().getChild("weather");

		if (vanilla == null) {
			VibeWeather.LOGGER.warn("Vibe Weather: no /weather command to intercept; leaving it alone");
			return;
		}

		LiteralArgumentBuilder<CommandSourceStack> replacement = Commands.literal("weather");
		Map<String, Command<CommandSourceStack>> installed = new LinkedHashMap<>();

		for (CommandNode<CommandSourceStack> child : vanilla.getChildren()) {
			String name = child.getName();
			Mapping mapping = MAPPINGS.get(name);

			if (mapping == null) {
				VibeWeather.LOGGER.warn("Vibe Weather: /weather {} is not a subcommand this mod knows;"
						+ " it keeps its vanilla behaviour", name);
				continue;
			}

			Command<CommandSourceStack> executor = context -> apply(context, name, mapping, defaultDuration());
			installed.put(name, executor);
			LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name).executes(executor);

			// Only mirror the duration argument if vanilla has one here. Adding a node vanilla lacks
			// would be inventing syntax; matching one it has just replaces the executor on it.
			if (child.getChild("duration") != null) {
				// The argument type given here never parses anything. Brigadier's merge keeps the
				// node that is already in the tree and swaps only its executor, so vanilla's own
				// time parser -- the one that understands "5d" and "20s" -- is what still runs. A
				// plain integer type stands in because it costs nothing and avoids naming a vanilla
				// package this project has not read. Vanilla's parser yields an Integer either way,
				// which is what the executor reads back.
				node.then(Commands.argument("duration", IntegerArgumentType.integer(1))
						.executes(context -> apply(context, name, mapping,
								IntegerArgumentType.getInteger(context, "duration"))));
			}

			replacement.then(node);
		}

		dispatcher.register(replacement);
		verify(dispatcher, installed);
	}

	/**
	 * Confirms the merge actually replaced vanilla's executors.
	 *
	 * <p>This is the only failure in the whole command layer that would be invisible: everything else
	 * is a compile error or a missing command, while a failed merge leaves a working vanilla
	 * {@code /weather} that quietly ignores the mod.
	 */
	private static void verify(
			CommandDispatcher<CommandSourceStack> dispatcher,
			Map<String, Command<CommandSourceStack>> installed
	) {
		CommandNode<CommandSourceStack> weather = dispatcher.getRoot().getChild("weather");

		for (Map.Entry<String, Command<CommandSourceStack>> entry : installed.entrySet()) {
			CommandNode<CommandSourceStack> node = weather == null ? null : weather.getChild(entry.getKey());

			if (node == null || node.getCommand() != entry.getValue()) {
				VibeWeather.LOGGER.warn("Vibe Weather: failed to take over /weather {} -- it will still"
						+ " run vanilla's global weather", entry.getKey());
			}
		}
	}

	private static int apply(
			CommandContext<CommandSourceStack> context,
			String name,
			Mapping mapping,
			int durationTicks
	) {
		CommandSourceStack source = context.getSource();
		ServerWeatherManager manager = CommandSupport.managerOrFail(source);

		if (manager == null) {
			return 0;
		}

		VibeWeatherConfig config = VibeWeather.config().get();
		WeatherRules rules = config.toRules();
		double radius = config.commands.vanillaWeatherRadius;
		Vec3 position = source.getPosition();
		WeatherState current = manager.sampleAt(position.x, position.y, position.z).state();

		CommandSupport.applyOverride(source, manager, mapping.apply(current, rules), radius, durationTicks);
		// Says the radius out loud. Vanilla /weather is global, this one is not, and an operator who
		// is not told that will report it as the command half-working.
		source.sendSuccess(() -> Component.translatable("commands.vibeweather.vanilla.mapped",
				name, CommandSupport.round(radius)), true);
		return CommandSupport.SUCCESS;
	}

	private static int defaultDuration() {
		return VibeWeather.config().get().commands.defaultSetDurationTicks;
	}
}
