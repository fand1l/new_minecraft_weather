package com.fand1l.vibeweather.config;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.weather.FogRules;
import com.fand1l.vibeweather.weather.WeatherTransitions;
import com.fand1l.vibeweather.weather.ZoneSpawnParams;

/**
 * The whole mod configuration, mirroring {@code config/vibe_weather.json}.
 *
 * <p>These are mutable classes with initialised fields rather than records, on purpose. Gson leaves
 * a field untouched when the JSON omits it, so every absent key keeps its default here, while
 * unknown keys are ignored. A record would instead deserialise a missing number as zero and a
 * missing object as null, turning a partial config into a broken one.
 *
 * <p>The strictness lives one step later: {@link #toRules()} and friends build the immutable model
 * records, whose constructors validate. So parsing is forgiving and the model is not.
 *
 * <p>Field names are camel case here and snake case in the file; the naming policy in
 * {@link ConfigManager} maps between them, so {@code minRadius} is {@code min_radius} on disk.
 */
public final class VibeWeatherConfig {
	public Thresholds thresholds = new Thresholds();
	public Zones zones = new Zones();
	public Grid grid = new Grid();
	public Transitions transitions = new Transitions();
	public Fog fog = new Fog();
	public Wind wind = new Wind();
	public Render render = new Render();
	public Gameplay gameplay = new Gameplay();
	public Commands commands = new Commands();

	/** Where each discrete band begins, and how transitions are paced. */
	public static final class Thresholds {
		/** Below this an intensity snaps to zero, which is what lets a fade finish. */
		public float intensityEpsilon = 0.01F;
		public float overcastFloor = 0.75F;
		public float cloudFew = 0.20F;
		public float cloudScattered = 0.45F;
		public float precipDrizzle = 0.05F;
		public float precipRain = 0.35F;
		public float precipDownpour = 0.75F;
		public float thunderWeak = 0.05F;
		public float thunderNormal = 0.50F;
		public float fogLight = 0.05F;
		public float fogThick = 0.55F;
		public float windBreeze = 0.25F;
		public float windGale = 0.65F;
		/** Ticks for precipitation to fade out once cloud cover starts dropping. */
		public int precipFadeTicks = 600;
		public int thunderFadeTicks = 400;
		public int cloudFadeTicks = 1200;
		public int fogFadeTicks = 800;
		public int windFadeTicks = 900;
		public float windTurnDegreesTick = 0.35F;
	}

	/** Zone geometry, movement and lifetime. */
	public static final class Zones {
		public double minRadius = 64.0;
		public double maxRadius = 1024.0;
		/** Uniform draws averaged for the radius. Higher concentrates towards the middle. */
		public int sizeBiasSamples = 3;
		public double blendFraction = 0.35;
		/** Zone speed as a fraction of wind strength. Far below the wind players feel. */
		public double driftScale = 0.02;
		public double maxDriftPerTick = 0.02;
		/** How far beyond a player's view zones are born, so weather drifts in rather than appearing. */
		public double spawnMargin = 768.0;
		/** Target number of live zones per player-occupied area. */
		public int targetZonesPerPlayer = 3;
		public int maxPersistedZones = 512;
		/** A zone with no player within this radius for {@code unloadedTtlTicks} is discarded. */
		public double keepRadius = 2048.0;
		public long unloadedTtlTicks = 72000L;
		public int tickInterval = 20;
	}

	/** The grid of samples sent to clients. */
	public static final class Grid {
		/** Node spacing in blocks. Also sets the smallest legal zone radius, at four times this. */
		public double step = 16.0;
		/** Nodes in each direction from the player, so 27 gives a 55x55 grid. */
		public int halfExtent = 27;
		/** Ticks between delta updates. */
		public int updateIntervalTicks = 40;
		/** Ticks between full resends, as a safety net against a lost delta. */
		public int fullResyncTicks = 600;
	}

	/** Weighted randomness driving state changes. */
	public static final class Transitions {
		public float neighbourWeight = 1.0F;
		public float stayWeight = 0.6F;
		/** Probability of jumping two precipitation levels at once. Rare by design. */
		public float skipLevelChance = 0.05F;
		/** After precipitation ends, probability the sky stays overcast rather than clearing. */
		public float stayOvercastChance = 0.75F;
		public float instantClearChance = 0.10F;
		/** Probability of thunder with no precipitation at all. Dry storms are supported. */
		public float dryThunderChance = 0.12F;
		public float thunderWithRainChance = 0.35F;
		public float thunderWeakBias = 0.55F;
		/** Median state duration. A Minecraft day is 24000 ticks. */
		public int durationBaseTicks = 24000;
		/** Log-normal spread. Larger means more very long and very short states. */
		public float durationSigma = 0.6F;
		public int durationMinTicks = 4000;
		public int durationMaxTicks = 96000;
		public float windChangeChance = 0.5F;
		public float windTurnMaxDegrees = 120.0F;
	}

	/** Contextual fog chances. */
	public static final class Fog {
		public float baseChance = 0.05F;
		public float afterRainChance = 0.45F;
		public int afterRainWindowTicks = 2400;
		public float dawnChance = 0.35F;
		/** Dawn window in day-time ticks. May wrap past midnight, and by default does. */
		public int dawnStartTick = 22000;
		public int dawnEndTick = 1000;
		public float nearWaterChance = 0.25F;
		public int waterSearchRadius = 48;
		public int waterBlocksForLarge = 96;
		public float downpourChance = 0.60F;
		public float thickBias = 0.35F;
	}

	/** Wind effects on entities. */
	public static final class Wind {
		public boolean physicsEnabled = true;
		/** Below this strength nothing is pushed at all. */
		public float minEffectStrength = 0.05F;
		/** Wind never applies to a spectator. Present so the behaviour is visible, not to be turned off. */
		public boolean skipSpectators = true;
		/** Wind never applies to a creatively flying player. Same reasoning. */
		public boolean skipCreativeFlight = true;
		public double standingPush = 0.0015;
		public double movingPush = 0.006;
		public double arrowPush = 0.004;
		public double mobPush = 0.003;
		/** Applied to a boat only while a player is aboard. */
		public double boatPush = 0.008;
		public double elytraTailwind = 0.020;
		public double elytraHeadwind = 0.014;
		/** Hard cap on added velocity per tick, kept below vanilla's movement checks. */
		public double maxPushPerTick = 0.05;
		public double leafDrift = 0.02;
		/**
		 * Extra entity types the boat rule applies to, as registry ids.
		 *
		 * <p>Vanilla boats are matched by class ({@code vehicle.boat.AbstractBoat}, which moved into
		 * its own subpackage in 26.2), so this list exists for modded vehicles that do not extend it.
		 * The vanilla ids are listed anyway: matching either way costs nothing and makes the rule
		 * visible to anyone reading the config rather than hidden in a class check.
		 *
		 * <p>Unknown ids are reported rather than ignored -- the entity registry is defaulted and
		 * silently answers with a pig for anything it does not recognise.
		 */
		public List<String> boatEntityTypes = new ArrayList<>(List.of(
				"minecraft:oak_boat",
				"minecraft:spruce_boat",
				"minecraft:birch_boat",
				"minecraft:jungle_boat",
				"minecraft:acacia_boat",
				"minecraft:dark_oak_boat",
				"minecraft:mangrove_boat",
				"minecraft:cherry_boat",
				"minecraft:pale_oak_boat",
				"minecraft:bamboo_raft",
				"minecraft:oak_chest_boat",
				"minecraft:spruce_chest_boat",
				"minecraft:birch_chest_boat",
				"minecraft:jungle_chest_boat",
				"minecraft:acacia_chest_boat",
				"minecraft:dark_oak_chest_boat",
				"minecraft:mangrove_chest_boat",
				"minecraft:cherry_chest_boat",
				"minecraft:pale_oak_chest_boat",
				"minecraft:bamboo_chest_raft"));
	}

	/** Client-side rendering. */
	public static final class Render {
		/** Overrides the vanilla weather radius option, in blocks. */
		public int weatherRadius = 96;
		/** Ceiling on drawn precipitation columns, the guard against a large radius costing frames. */
		public int maxColumns = 20000;
		/** Tangent of the maximum precipitation tilt at full wind. */
		public float maxTiltTan = 0.85F;
		public float cloudBottom = 192.0F;
		public float cloudTop = 224.0F;
		public boolean windStreaks = true;
		public int windStreakBudget = 48;
		public float rainVolume = 1.0F;
		public boolean tiltEnabled = true;
	}

	/** How vanilla systems see our weather. */
	public static final class Gameplay {
		/**
		 * What the server reports for the level-wide rain level, which has no position argument.
		 *
		 * <p>{@code MAX} means "precipitation exists somewhere in this dimension", which keeps
		 * vanilla's gate open so the position-aware hooks can decide. {@code NEAREST_PLAYER} is more
		 * accurate for small servers. {@code OFF} disables the vanilla path entirely.
		 */
		public String serverRainLevelMode = "MAX";
		public boolean fasterCauldronFill = true;
		public float cauldronDrizzleScale = 0.5F;
		public float cauldronRainScale = 1.5F;
		public float cauldronDownpourScale = 3.0F;
		public boolean fasterSnowAccumulation = true;
		public float snowDownpourScale = 3.0F;
		/** Base strike interval; divided by the level multiplier below. */
		public int thunderStrikeInterval = 100000;
		public float thunderWeakMultiplier = 0.15F;
		public float thunderNormalMultiplier = 1.0F;
	}

	/** Command behaviour. */
	public static final class Commands {
		/**
		 * Which vanilla permission tier the command requires.
		 *
		 * <p>A name, not the integer the brief specified, because 26.2 no longer expresses command
		 * permissions as levels: {@code Commands.LEVEL_GAMEMASTERS} and friends are
		 * {@code PermissionCheck} objects wrapping named permissions, and there is no integer to
		 * pass. {@code GAMEMASTERS} is the tier vanilla {@code /weather} itself uses, and is the
		 * direct equivalent of the requested level 2.
		 *
		 * <p>One of {@code ALL}, {@code MODERATORS}, {@code GAMEMASTERS}, {@code ADMINS},
		 * {@code OWNERS}.
		 */
		public String permissionTier = "GAMEMASTERS";
		/** Radius the intercepted vanilla /weather applies to, in blocks. */
		public double vanillaWeatherRadius = 512.0;
		public double defaultSetRadius = 256.0;
		public int maxOverrides = 64;
		/** Whether /vibeweather query needs the permission level. It is read-only information. */
		public boolean queryNeedsPermission = false;
	}

	// ------------------------------------------------------------------ conversions to the model

	/**
	 * Builds the model's threshold record. Throws {@link IllegalArgumentException} with a specific
	 * message when the thresholds cannot describe a coherent model.
	 */
	public WeatherRules toRules() {
		return new WeatherRules(
				thresholds.intensityEpsilon,
				thresholds.overcastFloor,
				thresholds.cloudFew,
				thresholds.cloudScattered,
				thresholds.precipDrizzle,
				thresholds.precipRain,
				thresholds.precipDownpour,
				thresholds.thunderWeak,
				thresholds.thunderNormal,
				thresholds.fogLight,
				thresholds.fogThick,
				thresholds.windBreeze,
				thresholds.windGale,
				thresholds.precipFadeTicks,
				thresholds.thunderFadeTicks,
				thresholds.cloudFadeTicks,
				thresholds.fogFadeTicks,
				thresholds.windFadeTicks,
				thresholds.windTurnDegreesTick);
	}

	/** Builds the zone geometry record, which enforces {@code min_radius >= 4 * grid.step}. */
	public ZoneSpawnParams toSpawnParams() {
		return new ZoneSpawnParams(
				zones.minRadius,
				zones.maxRadius,
				zones.sizeBiasSamples,
				zones.blendFraction,
				grid.step,
				zones.driftScale,
				zones.maxDriftPerTick,
				zones.spawnMargin);
	}

	public FogRules toFogRules() {
		return new FogRules(
				fog.baseChance,
				fog.afterRainChance,
				fog.afterRainWindowTicks,
				fog.dawnChance,
				fog.dawnStartTick,
				fog.dawnEndTick,
				fog.nearWaterChance,
				fog.downpourChance,
				fog.thickBias);
	}

	public WeatherTransitions toTransitions(RandomGenerator random) {
		return new WeatherTransitions(
				random,
				transitions.neighbourWeight,
				transitions.stayWeight,
				transitions.skipLevelChance,
				transitions.stayOvercastChance,
				transitions.instantClearChance,
				transitions.dryThunderChance,
				transitions.thunderWithRainChance,
				transitions.thunderWeakBias,
				transitions.durationBaseTicks,
				transitions.durationSigma,
				transitions.durationMinTicks,
				transitions.durationMaxTicks,
				transitions.windChangeChance,
				transitions.windTurnMaxDegrees);
	}

	/**
	 * Checks everything the individual records cannot see on their own, then builds them all so
	 * their constructors run. Called before a config is accepted, so a bad file is rejected as a
	 * whole rather than half-applied.
	 */
	public void validate() {
		if (grid.halfExtent < 1) {
			throw new IllegalArgumentException("grid.half_extent must be >= 1, got " + grid.halfExtent);
		}

		if (grid.updateIntervalTicks < 1) {
			throw new IllegalArgumentException(
					"grid.update_interval_ticks must be >= 1, got " + grid.updateIntervalTicks);
		}

		if (zones.tickInterval < 1) {
			throw new IllegalArgumentException("zones.tick_interval must be >= 1, got " + zones.tickInterval);
		}

		if (render.cloudTop <= render.cloudBottom) {
			throw new IllegalArgumentException("render.cloud_top must exceed render.cloud_bottom, got "
					+ render.cloudBottom + ".." + render.cloudTop);
		}

		if (render.maxColumns < 1) {
			throw new IllegalArgumentException("render.max_columns must be >= 1, got " + render.maxColumns);
		}

		if (transitions.durationMinTicks < 1 || transitions.durationMaxTicks < transitions.durationMinTicks) {
			throw new IllegalArgumentException("transitions duration range must be positive and ordered, got "
					+ transitions.durationMinTicks + ".." + transitions.durationMaxTicks);
		}

		serverRainLevelMode();
		permissionTier();

		// Building these runs their validating constructors.
		toRules();
		toSpawnParams();
		toFogRules();
	}

	public RainLevelMode serverRainLevelMode() {
		try {
			return RainLevelMode.valueOf(gameplay.serverRainLevelMode.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("gameplay.server_rain_level_mode must be one of MAX, "
					+ "NEAREST_PLAYER, OFF; got '" + gameplay.serverRainLevelMode + "'");
		}
	}

	/** See {@link Gameplay#serverRainLevelMode}. */
	public enum RainLevelMode {
		MAX,
		NEAREST_PLAYER,
		OFF
	}

	public PermissionTier permissionTier() {
		try {
			return PermissionTier.valueOf(commands.permissionTier.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("commands.permission_tier must be one of ALL, MODERATORS, "
					+ "GAMEMASTERS, ADMINS, OWNERS; got '" + commands.permissionTier + "'");
		}
	}

	/**
	 * Mirrors the vanilla permission tiers. Named rather than numbered because 26.2 replaced integer
	 * command levels with {@code PermissionCheck} objects; see {@link Commands#permissionTier}.
	 */
	public enum PermissionTier {
		ALL,
		MODERATORS,
		GAMEMASTERS,
		ADMINS,
		OWNERS
	}
}
