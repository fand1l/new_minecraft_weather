package com.fand1l.vibeweather.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import com.fand1l.vibeweather.api.ClientWeatherParams;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.config.VibeWeatherConfig;
import com.fand1l.vibeweather.net.VibeWeatherPayloads;
import com.fand1l.vibeweather.weather.WeatherGridBuilder;
import com.fand1l.vibeweather.weather.WeatherTransitions;
import com.fand1l.vibeweather.weather.WeatherZone;
import com.fand1l.vibeweather.weather.ZoneBlender;
import com.fand1l.vibeweather.weather.ZoneManager;
import com.fand1l.vibeweather.weather.ZonePersistence;
import com.fand1l.vibeweather.weather.ZoneSpawnParams;

/**
 * Drives the weather for one dimension and keeps its players in sync.
 *
 * <p>An adapter, not a simulation: the zone lifecycle, blending, grid construction and wire format
 * all live in the pure package, where the harness exercises them without a game. What happens here
 * is translating between that and Minecraft -- reading player positions, sending packets, and
 * persisting.
 */
public final class ServerWeatherManager {
	/** What each player has been sent, so a delta can be computed against it. */
	private static final class Tracked {
		WeatherGridBuilder.Grid grid;
		int ticksSinceUpdate;
		int ticksSinceFullResync;
		boolean paramsSent;
	}

	private final ServerLevel level;
	private final ZoneManager zones = new ZoneManager();
	private final Map<UUID, Tracked> tracked = new HashMap<>();
	private final RandomGenerator random;

	private WeatherSavedData saved;
	private int ticksUntilZoneTick;
	private boolean loaded;

	public ServerWeatherManager(ServerLevel level, RandomGenerator random) {
		this.level = level;
		this.random = random;
	}

	public ServerLevel level() {
		return level;
	}

	/**
	 * The dimension's generator.
	 *
	 * <p>Shared with the thunder hook so strike rolls come from the same seeded stream as the zone
	 * simulation, rather than from the level's own random -- which would need shadowing a vanilla
	 * field for no benefit.
	 */
	public RandomGenerator random() {
		return random;
	}

	public List<WeatherZone> zones() {
		return zones.zones();
	}

	public boolean frozen() {
		return saved != null && saved.frozen();
	}

	public void setFrozen(boolean value) {
		if (saved != null) {
			saved.setFrozen(value);
		}

		// Everyone needs to know, since the client shows freeze state and stops interpolating.
		for (ServerPlayer player : level.players()) {
			sendParams(player, currentConfig());
		}
	}

	/** Reads persisted zones. Called once, lazily, because saved data is not available at construction. */
	private void ensureLoaded(WeatherRules rules) {
		if (loaded) {
			return;
		}

		saved = WeatherSavedData.get(level);
		ZonePersistence.Snapshot snapshot = saved.loadZones(rules);
		zones.restore(new ArrayList<>(snapshot.zones()), snapshot.nextId());
		loaded = true;
	}

	private VibeWeatherConfig currentConfig() {
		return com.fand1l.vibeweather.VibeWeather.config().get();
	}

	/**
	 * One server tick for this dimension.
	 *
	 * <p>Zone simulation and grid rebuilds run on their own intervals rather than every tick: weather
	 * changes over game days, so ticking it twenty times a second would burn CPU to compute the same
	 * numbers.
	 */
	public void tick() {
		VibeWeatherConfig config = currentConfig();
		WeatherRules rules = config.toRules();
		ensureLoaded(rules);

		if (--ticksUntilZoneTick <= 0) {
			ticksUntilZoneTick = config.zones.tickInterval;
			tickZones(config, rules);
		}

		syncPlayers(config, rules);
	}

	private void tickZones(VibeWeatherConfig config, WeatherRules rules) {
		ZoneSpawnParams params = config.toSpawnParams();
		WeatherTransitions transitions = config.toTransitions(random);
		ZoneManager.Settings settings = new ZoneManager.Settings(
				config.zones.targetZonesPerPlayer,
				config.zones.maxPersistedZones,
				config.zones.keepRadius,
				config.zones.unloadedTtlTicks);

		zones.tick(level.getGameTime(), anchors(config), rules, transitions, params,
				settings, frozen(), random);

		if (saved != null) {
			saved.storeZones(zones.zones(), zones.nextId());
		}
	}

	/**
	 * Player positions and how far weather is computed around each.
	 *
	 * <p>The radius comes from the grid the player is actually sent rather than from their view
	 * distance: that is the area whose weather can be seen, so it is the area that needs zones.
	 */
	private List<ZoneManager.Anchor> anchors(VibeWeatherConfig config) {
		List<ServerPlayer> players = level.players();
		List<ZoneManager.Anchor> result = new ArrayList<>(players.size());
		double reach = config.grid.halfExtent * config.grid.step;

		for (ServerPlayer player : players) {
			result.add(new ZoneManager.Anchor(player.position().x, player.position().z, reach));
		}

		return result;
	}

	private void syncPlayers(VibeWeatherConfig config, WeatherRules rules) {
		List<ServerPlayer> players = level.players();
		tracked.keySet().removeIf(id -> players.stream().noneMatch(p -> p.getUUID().equals(id)));

		for (ServerPlayer player : players) {
			Tracked state = tracked.computeIfAbsent(player.getUUID(), id -> new Tracked());

			if (!state.paramsSent) {
				sendParams(player, config);
				state.paramsSent = true;
			}

			if (++state.ticksSinceUpdate < config.grid.updateIntervalTicks) {
				continue;
			}

			state.ticksSinceUpdate = 0;
			state.ticksSinceFullResync += config.grid.updateIntervalTicks;
			sendGrid(player, state, config, rules);
		}
	}

	private void sendGrid(ServerPlayer player, Tracked state, VibeWeatherConfig config, WeatherRules rules) {
		WeatherGridBuilder.Grid current = WeatherGridBuilder.build(
				zones.zones(),
				player.position().x,
				player.position().z,
				config.grid.halfExtent,
				config.grid.step,
				config.render.cloudBottom,
				config.render.cloudTop,
				rules);

		boolean forceFull = state.grid == null
				|| state.ticksSinceFullResync >= config.grid.fullResyncTicks;

		WeatherGridBuilder.Delta delta = forceFull
				? new WeatherGridBuilder.Delta(current, List.of(), true)
				: WeatherGridBuilder.diff(state.grid, current, 0.4F);

		List<WeatherGridBuilder.Change> changes = delta.full()
				? WeatherGridBuilder.allNodes(current)
				: delta.changes();

		if (changes.isEmpty()) {
			state.grid = current;
			return;
		}

		List<byte[]> batches = WeatherGridBuilder.batch(changes, 512);

		for (int i = 0; i < batches.size(); i++) {
			// Only the first batch resets: the rest patch the grid it just established.
			boolean reset = delta.full() && i == 0;
			VibeWeatherPayloads.GridPayload payload = new VibeWeatherPayloads.GridPayload(
					current.originNodeX(),
					current.originNodeZ(),
					current.halfExtent(),
					(float) current.step(),
					reset,
					batches.get(i));

			if (ServerPlayNetworking.canSend(player, VibeWeatherPayloads.GRID)) {
				ServerPlayNetworking.send(player, payload);
			}
		}

		state.grid = current;

		if (delta.full()) {
			state.ticksSinceFullResync = 0;
		}
	}

	/** Sends the thresholds and render limits. Also called on reload and on a freeze change. */
	public void sendParams(ServerPlayer player, VibeWeatherConfig config) {
		if (!ServerPlayNetworking.canSend(player, VibeWeatherPayloads.PARAMS)) {
			return;
		}

		ClientWeatherParams params = new ClientWeatherParams(
				config.toRules(),
				config.render.cloudBottom,
				config.render.cloudTop,
				config.render.weatherRadius,
				config.render.maxColumns,
				config.render.maxTiltTan,
				config.render.tiltEnabled,
				config.render.rainVolume,
				config.render.windStreaks,
				config.render.windStreakBudget,
				frozen());

		ServerPlayNetworking.send(player, new VibeWeatherPayloads.ParamsPayload(params.toBytes()));
	}

	/** Forces every player to receive params and a full grid again, after a config reload. */
	public void resendEverything() {
		VibeWeatherConfig config = currentConfig();

		for (Tracked state : tracked.values()) {
			state.paramsSent = false;
			state.grid = null;
			state.ticksSinceUpdate = config.grid.updateIntervalTicks;
		}
	}

	/**
	 * Makes every player's next tick carry a grid update.
	 *
	 * <p>Used after a command changes the weather. Waiting out the normal update interval would put a
	 * two-second gap between the command and the sky reacting, which reads as the command not having
	 * worked. Cheaper than {@link #resendEverything}, which also resends parameters.
	 */
	public void refreshGrids() {
		VibeWeatherConfig config = currentConfig();

		for (Tracked state : tracked.values()) {
			state.ticksSinceUpdate = config.grid.updateIntervalTicks;
		}
	}

	public void forgetPlayer(UUID id) {
		tracked.remove(id);
	}

	/** Weather at a point, for gameplay hooks and the query command. */
	public WeatherSample sampleAt(double x, double y, double z) {
		VibeWeatherConfig config = currentConfig();
		WeatherRules rules = config.toRules();
		ensureLoaded(rules);
		return ZoneBlender.sample(zones.zones(), x, y, z,
				config.render.cloudBottom, config.render.cloudTop, rules);
	}

	/**
	 * Level-wide precipitation intensity.
	 *
	 * <p>Has no position, so it cannot mean "at your location" with several players in different
	 * weather. It means "there is precipitation somewhere in this dimension", and it is a maximum
	 * rather than an average on purpose: after the mixins, vanilla's rain checks survive only as a
	 * gate upstream of the position-aware ones, and an average would randomly close that gate and
	 * silence the logic that should be deciding.
	 */
	public float levelRainLevel() {
		VibeWeatherConfig config = currentConfig();

		return switch (config.serverRainLevelMode()) {
			case OFF -> 0.0F;
			case MAX -> ZoneBlender.maxPrecip(zones.zones());
			case NEAREST_PLAYER -> nearestPlayerPrecip();
		};
	}

	public float levelThunderLevel() {
		VibeWeatherConfig config = currentConfig();
		return config.serverRainLevelMode() == VibeWeatherConfig.RainLevelMode.OFF
				? 0.0F
				: ZoneBlender.maxThunder(zones.zones());
	}

	private float nearestPlayerPrecip() {
		float max = 0.0F;

		for (ServerPlayer player : level.players()) {
			max = Math.max(max, sampleAt(player.position().x, player.position().y,
					player.position().z).state().precip());
		}

		return max;
	}

	/** Backing store for command-driven overrides. */
	public ZoneManager zoneManager() {
		VibeWeatherConfig config = currentConfig();
		ensureLoaded(config.toRules());
		return zones;
	}

	/** Persists immediately, for world save and shutdown. */
	public void flush() {
		if (saved != null) {
			saved.storeZones(zones.zones(), zones.nextId());
		}
	}
}
