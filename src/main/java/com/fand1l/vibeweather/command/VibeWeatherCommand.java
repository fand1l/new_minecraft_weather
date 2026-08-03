package com.fand1l.vibeweather.command;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import com.fand1l.vibeweather.VibeWeather;
import com.fand1l.vibeweather.api.CloudCover;
import com.fand1l.vibeweather.api.FogLevel;
import com.fand1l.vibeweather.api.Precipitation;
import com.fand1l.vibeweather.api.ThunderLevel;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.api.WindMode;
import com.fand1l.vibeweather.config.ConfigManager;
import com.fand1l.vibeweather.config.VibeWeatherConfig;
import com.fand1l.vibeweather.server.ServerWeatherManager;

/**
 * {@code /vibeweather query | set | freeze | reload}.
 *
 * <p>Every axis is set on its own, by name, rather than through one "weather type" argument. That is
 * the command shape the data model asks for: the axes are independent, and collapsing them back into
 * presets at the command layer would hide exactly the combinations the mod exists to produce -- dry
 * thunder, or an overcast sky with nothing falling out of it.
 *
 * <p>Setting an axis samples the weather where the caller stands, changes that one axis and drops the
 * result as an override zone. So {@code set precip rain} keeps the wind that was already blowing, and
 * two set commands in a row compose instead of resetting each other.
 */
public final class VibeWeatherCommand {
	/** Applies one axis to a sampled state. */
	private interface AxisSetter<E extends Enum<E>> {
		WeatherState apply(WeatherState state, WeatherRules rules, E value);
	}

	private VibeWeatherCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("vibeweather")
				.then(Commands.literal("query")
						.requires(source -> !VibeWeather.config().get().commands.queryNeedsPermission
								|| CommandSupport.permission().test(source))
						.executes(VibeWeatherCommand::query))
				.then(setNode())
				.then(Commands.literal("freeze")
						.requires(CommandSupport.permission())
						// Literals rather than a boolean argument: two words, both tab-completable,
						// and no third state to explain.
						.then(Commands.literal("on").executes(context -> freeze(context, true)))
						.then(Commands.literal("off").executes(context -> freeze(context, false))))
				.then(Commands.literal("reload")
						.requires(CommandSupport.permission())
						.executes(VibeWeatherCommand::reload)));
	}

	// ------------------------------------------------------------------------------------ set

	private static LiteralArgumentBuilder<CommandSourceStack> setNode() {
		return Commands.literal("set")
				.requires(CommandSupport.permission())
				.then(axis("clouds", CloudCover.values(),
						(state, rules, value) -> state.withClouds(rules.cloudValue(value))))
				.then(axis("precip", Precipitation.values(),
						(state, rules, value) -> state.withPrecip(rules.precipValue(value))))
				.then(axis("thunder", ThunderLevel.values(),
						(state, rules, value) -> state.withThunder(rules.thunderValue(value))))
				.then(axis("fog", FogLevel.values(),
						(state, rules, value) -> state.withFog(rules.fogValue(value))))
				.then(axis("wind", WindMode.values(),
						(state, rules, value) -> state.withWind(rules.windValue(value), state.windDirection())))
				.then(Commands.literal("wind_direction")
						.then(Commands.argument("degrees", FloatArgumentType.floatArg(0.0F, 360.0F))
								.executes(context -> setWindDirection(context, defaultRadius()))
								.then(Commands.argument("radius", FloatArgumentType.floatArg(8.0F, 8192.0F))
										.executes(context -> setWindDirection(context,
												FloatArgumentType.getFloat(context, "radius"))))));
	}

	/**
	 * One axis as a literal per band, with an optional radius.
	 *
	 * <p>Built from {@code values()} so a new band in the model shows up in the command without anyone
	 * remembering to add it here.
	 */
	private static <E extends Enum<E>> LiteralArgumentBuilder<CommandSourceStack> axis(
			String name,
			E[] values,
			AxisSetter<E> setter
	) {
		LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name);

		for (E value : values) {
			node.then(Commands.literal(value.name().toLowerCase(Locale.ROOT))
					.executes(context -> setAxis(context, name, value, setter, defaultRadius()))
					.then(Commands.argument("radius", FloatArgumentType.floatArg(8.0F, 8192.0F))
							.executes(context -> setAxis(context, name, value, setter,
									FloatArgumentType.getFloat(context, "radius")))));
		}

		return node;
	}

	private static <E extends Enum<E>> int setAxis(
			CommandContext<CommandSourceStack> context,
			String axis,
			E value,
			AxisSetter<E> setter,
			double radius
	) {
		CommandSourceStack source = context.getSource();
		ServerWeatherManager manager = CommandSupport.managerOrFail(source);

		if (manager == null) {
			return 0;
		}

		VibeWeatherConfig config = VibeWeather.config().get();
		WeatherRules rules = config.toRules();
		Vec3 position = source.getPosition();
		WeatherState current = manager.sampleAt(position.x, position.y, position.z).state();
		WeatherState changed = setter.apply(current, rules, value);

		CommandSupport.applyOverride(source, manager, changed, radius, config.commands.defaultSetDurationTicks);
		source.sendSuccess(() -> Component.translatable("commands.vibeweather.set.axis",
				Component.translatable("vibeweather.axis." + axis),
				CommandSupport.band(axis, value),
				CommandSupport.round(radius)), true);
		return CommandSupport.SUCCESS;
	}

	private static int setWindDirection(CommandContext<CommandSourceStack> context, double radius) {
		CommandSourceStack source = context.getSource();
		ServerWeatherManager manager = CommandSupport.managerOrFail(source);

		if (manager == null) {
			return 0;
		}

		VibeWeatherConfig config = VibeWeather.config().get();
		float degrees = FloatArgumentType.getFloat(context, "degrees");
		Vec3 position = source.getPosition();
		WeatherState current = manager.sampleAt(position.x, position.y, position.z).state();

		CommandSupport.applyOverride(source, manager,
				current.withWind(current.windStrength(), degrees), radius,
				config.commands.defaultSetDurationTicks);
		source.sendSuccess(() -> Component.translatable("commands.vibeweather.set.wind_direction",
				CommandSupport.round(degrees), CommandSupport.round(radius)), true);
		return CommandSupport.SUCCESS;
	}

	private static double defaultRadius() {
		return VibeWeather.config().get().commands.defaultSetRadius;
	}

	// ---------------------------------------------------------------------------------- query

	/**
	 * Reports every axis where the caller stands.
	 *
	 * <p>Deliberately has no position argument. One would need an argument type this project has not
	 * verified against 26.2, and the answer for "over there" is the whole point of the grid the client
	 * already has -- you can go and look.
	 */
	private static int query(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerWeatherManager manager = CommandSupport.managerOrFail(source);

		if (manager == null) {
			return 0;
		}

		WeatherRules rules = VibeWeather.config().get().toRules();
		Vec3 position = source.getPosition();
		WeatherSample sample = manager.sampleAt(position.x, position.y, position.z);
		WeatherState state = sample.state();

		List<Component> lines = List.of(
				Component.translatable("commands.vibeweather.query.header",
						CommandSupport.round(position.x), CommandSupport.round(position.y),
						CommandSupport.round(position.z)),
				Component.translatable("commands.vibeweather.query.clouds",
						CommandSupport.band("clouds", state.cloudCover(rules)),
						CommandSupport.round(state.clouds())),
				Component.translatable("commands.vibeweather.query.precipitation",
						CommandSupport.band("precip", state.precipitation(rules)),
						CommandSupport.round(sample.effectivePrecip())),
				Component.translatable("commands.vibeweather.query.thunder",
						CommandSupport.band("thunder", state.thunderLevel(rules)),
						CommandSupport.round(sample.effectiveThunder())),
				Component.translatable("commands.vibeweather.query.wind",
						CommandSupport.band("wind", state.windMode(rules)),
						CommandSupport.round(state.windDirection()),
						CommandSupport.round(state.windStrength())),
				Component.translatable("commands.vibeweather.query.fog",
						CommandSupport.band("fog", state.fogLevel(rules)),
						CommandSupport.round(sample.effectiveFog())),
				Component.translatable("commands.vibeweather.query.coverage",
						CommandSupport.round(sample.coverage())),
				Component.translatable("commands.vibeweather.query.altitude",
						CommandSupport.round(sample.altitudeFactor())),
				Component.translatable("commands.vibeweather.query.zones",
						String.valueOf(manager.zones().size())));

		for (Component line : lines) {
			// false: a query is for whoever asked, not for everyone's chat.
			source.sendSuccess(() -> line, false);
		}

		if (manager.frozen()) {
			source.sendSuccess(() -> Component.translatable("commands.vibeweather.query.frozen"), false);
		}

		return CommandSupport.SUCCESS;
	}

	// ------------------------------------------------------------------------- freeze, reload

	private static int freeze(CommandContext<CommandSourceStack> context, boolean frozen) {
		CommandSourceStack source = context.getSource();
		ServerWeatherManager manager = CommandSupport.managerOrFail(source);

		if (manager == null) {
			return 0;
		}

		manager.setFrozen(frozen);
		source.sendSuccess(() -> Component.translatable(
				frozen ? "commands.vibeweather.freeze.on" : "commands.vibeweather.freeze.off"), true);
		return CommandSupport.SUCCESS;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ConfigManager.Result result = VibeWeather.reload();

		if (!result.applied()) {
			// The active config is untouched on a rejected reload, so this is a report, not a warning
			// about a half-applied state.
			source.sendFailure(Component.translatable("commands.vibeweather.reload.failure", result.message()));
			return 0;
		}

		source.sendSuccess(() -> Component.translatable("commands.vibeweather.reload.success"), true);
		return CommandSupport.SUCCESS;
	}
}
