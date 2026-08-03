import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fand1l.vibeweather.api.ClientWeatherParams;
import com.fand1l.vibeweather.api.CloudCover;
import com.fand1l.vibeweather.api.Precipitation;
import com.fand1l.vibeweather.api.ThunderLevel;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.util.MathUtil;
import com.fand1l.vibeweather.weather.FogRules;
import com.fand1l.vibeweather.weather.GridCodec;
import com.fand1l.vibeweather.weather.WeatherGridBuilder;
import com.fand1l.vibeweather.weather.WeatherTransitions;
import com.fand1l.vibeweather.weather.WeatherZone;
import com.fand1l.vibeweather.weather.ZoneBlender;
import com.fand1l.vibeweather.weather.ZoneManager;
import com.fand1l.vibeweather.weather.ZonePersistence;
import com.fand1l.vibeweather.weather.ZoneSpawnParams;

/** Checks the two invariants that were broken in review, plus the surrounding contract. */
public final class ModelHarness {
	/** Generator pinned to its lowest outputs, giving a fully deterministic transition walk. */
	static final class PinnedRandom implements java.util.random.RandomGenerator {
		@Override
		public long nextLong() {
			return 0L;
		}

		@Override
		public float nextFloat() {
			return 0.0F;
		}

		@Override
		public double nextDouble() {
			return 0.0D;
		}

		@Override
		public boolean nextBoolean() {
			return false;
		}

		@Override
		public int nextInt(int bound) {
			return 0;
		}

		@Override
		public double nextGaussian() {
			return 0.0D;
		}
	}

	static int passed;
	static int failed;

	static void check(String name, boolean ok, String detail) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name + "  -- " + detail);
		}
	}

	public static void main(String[] args) {
		WeatherRules r = WeatherRules.defaults();
		Random rng = new Random(20260802L);

		System.out.println("[1] sanitize() is pure and idempotent");
		boolean idempotent = true;
		boolean neverLowers = true;
		boolean inv1 = true;
		boolean inv2Clouds = true;
		boolean inv5 = true;

		for (int i = 0; i < 200_000; i++) {
			WeatherState raw = new WeatherState(
					rng.nextFloat() * 1.4F - 0.2F,
					rng.nextFloat() * 1.4F - 0.2F,
					rng.nextFloat() * 1.4F - 0.2F,
					rng.nextFloat() * 1.4F - 0.2F,
					rng.nextFloat() * 1.4F - 0.2F,
					rng.nextFloat() * 1000.0F - 500.0F);
			WeatherState a = raw.sanitize(r);
			WeatherState b = a.sanitize(r);

			if (!a.equals(b)) {
				idempotent = false;
			}

			float clampedPrecip = Math.clamp(raw.precip(), 0.0F, 1.0F);

			if (a.precip() > clampedPrecip + 1e-6F) {
				neverLowers = false;
			}

			if (a.precip() > 0.0F && a.clouds() < r.overcastFloor()) {
				inv1 = false;
			}

			if (a.thunder() > 0.0F && a.clouds() < r.overcastFloor()) {
				inv2Clouds = false;
			}

			float clampedFog = Math.clamp(raw.fog(), 0.0F, 1.0F);
			float expectedFog = clampedFog < r.intensityEpsilon() ? 0.0F : clampedFog;

			if (Math.abs(a.fog() - expectedFog) > 1e-6F) {
				inv5 = false;
			}

			if (a.windDirection() < 0.0F || a.windDirection() >= 360.0F) {
				idempotent = false;
			}
		}

		check("idempotent over 200k random tuples", idempotent, "sanitize twice != sanitize once");
		check("never raises precipitation above its clamped input", neverLowers, "precip grew");
		check("invariant 1: precipitation implies overcast", inv1, "wet without overcast");
		check("invariant 2: thunder implies overcast", inv2Clouds, "thunder without overcast");
		check("invariant 5: fog untouched by other axes", inv5, "fog was modified");

		System.out.println("\n[2] dry thunder is representable (invariant 2, the review blocker)");
		WeatherState dry = new WeatherState(0.0F, 0.0F, 1.0F, 0.0F, 0.3F, 90.0F).sanitize(r);
		check("thunder survives with zero precipitation",
				dry.thunder() > 0.0F && dry.precip() == 0.0F, "thunder=" + dry.thunder() + " precip=" + dry.precip());
		check("dry thunder classifies as an active storm",
				dry.thunderLevel(r) == ThunderLevel.NORMAL && dry.precipitation(r) == Precipitation.NONE,
				dry.thunderLevel(r) + "/" + dry.precipitation(r));
		check("dry thunder forced the sky overcast",
				dry.cloudCover(r) == CloudCover.OVERCAST, String.valueOf(dry.cloudCover(r)));

		System.out.println("\n[3] invariant 3: overcast, dry, calm is a stable legal state");
		WeatherState overcastDry = new WeatherState(1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F).sanitize(r);
		check("stays overcast with nothing falling",
				overcastDry.cloudCover(r) == CloudCover.OVERCAST && overcastDry.isQuiet(),
				String.valueOf(overcastDry));

		System.out.println("\n[4] dead zone terminates a fade instead of trailing off");
		WeatherState tiny = new WeatherState(1.0F, r.intensityEpsilon() * 0.5F, 0.0F, 0.0F, 0.0F, 0.0F).sanitize(r);
		check("sub-epsilon precipitation snaps to exactly zero", tiny.precip() == 0.0F, String.valueOf(tiny.precip()));

		System.out.println("\n[5] invariant 4: clearing is ordered and completes (the other review blocker)");

		// A generator pinned to its lowest outputs, so the transition walk is fully deterministic:
		// precipitation steps down one level each retarget and thunder never restarts. Records are
		// final, so the roller is steered through its inputs rather than subclassed.
		WeatherTransitions clearing = new WeatherTransitions(
				new PinnedRandom(),
				1.0F,   // neighbour weight
				0.0F,   // stay weight: never hold a level
				0.0F,   // skip chance: always adjacent
				0.0F,   // stay-overcast chance
				1.0F,   // instant clear: force the cloud drop that triggers phase one
				0.0F,   // dry thunder chance
				0.0F,   // thunder with rain
				0.5F,
				5000, 0.0F, 5000, 5000,
				0.0F,   // wind never re-rolls
				0.0F);

		WeatherState storm = new WeatherState(1.0F, 1.0F, 1.0F, 0.0F, 0.4F, 180.0F).sanitize(r);
		WeatherZone zone = new WeatherZone(1L, 0, 0, 300, 100, 0, 0, storm, 0L, Long.MAX_VALUE, r);

		boolean cloudsHeldWhileWet = true;
		int precipZeroTick = -1;
		int cloudsClearedTick = -1;

		for (int tick = 1; tick <= 14_000; tick++) {
			zone.tick(tick, r, clearing, false);
			boolean active = zone.state().precip() > 0.0F || zone.state().thunder() > 0.0F;

			if (active) {
				// Phase one contract: while anything is still falling, cloud cover must not drop.
				if (zone.state().clouds() < 0.99F) {
					cloudsHeldWhileWet = false;
				}
			} else if (precipZeroTick < 0) {
				precipZeroTick = tick;
			}

			if (precipZeroTick > 0 && cloudsClearedTick < 0 && zone.state().clouds() <= 0.0F) {
				cloudsClearedTick = tick;
			}
		}

		check("cloud cover is held while precipitation fades", cloudsHeldWhileWet,
				"clouds dropped during phase one");
		check("precipitation and thunder reach exactly zero", precipZeroTick > 0,
				"never reached zero in 20000 ticks");
		check("cloud cover only then falls to the target", cloudsClearedTick > precipZeroTick,
				"precipZero=" + precipZeroTick + " cloudsCleared=" + cloudsClearedTick);
		check("the whole transition completes", zone.phase() == WeatherZone.Phase.STEADY,
				"stuck in " + zone.phase());
		System.out.println("        precipitation hit zero at tick " + precipZeroTick
				+ ", clouds finished at tick " + cloudsClearedTick);

		System.out.println("\n[6] blending: clear outside, full inside, soft between");
		List<WeatherZone> zones = new ArrayList<>();
		zones.add(new WeatherZone(2L, 0, 0, 200, 100, 0, 0,
				new WeatherState(1.0F, 1.0F, 0.0F, 0.0F, 0.5F, 0.0F), Long.MAX_VALUE, Long.MAX_VALUE, r));

		WeatherSample outside = ZoneBlender.sample(zones, 5000, 64, 5000, 192, 224, r);
		WeatherSample core = ZoneBlender.sample(zones, 0, 64, 0, 192, 224, r);
		WeatherSample edge = ZoneBlender.sample(zones, 250, 64, 0, 192, 224, r);

		check("outside every zone the sky is clear", outside.coverage() == 0.0F && outside.state().isQuiet(),
				String.valueOf(outside));
		check("in the core coverage is full", core.coverage() == 1.0F, String.valueOf(core.coverage()));
		check("in the blend band coverage is strictly between",
				edge.coverage() > 0.0F && edge.coverage() < 1.0F, String.valueOf(edge.coverage()));

		System.out.println("\n[7] above the clouds the weather stops");
		WeatherSample high = ZoneBlender.sample(zones, 0, 400, 0, 192, 224, r);
		check("altitude factor is zero above the cloud band", high.altitudeFactor() == 0.0F,
				String.valueOf(high.altitudeFactor()));
		check("no precipitation applies up there", high.effectivePrecip() == 0.0F,
				String.valueOf(high.effectivePrecip()));

		System.out.println("\n[8] wind direction blends the short way round");
		List<WeatherZone> twoWinds = new ArrayList<>();
		twoWinds.add(new WeatherZone(3L, -10, 0, 500, 50, 0, 0,
				new WeatherState(0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 350.0F), Long.MAX_VALUE, Long.MAX_VALUE, r));
		twoWinds.add(new WeatherZone(4L, 10, 0, 500, 50, 0, 0,
				new WeatherState(0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 10.0F), Long.MAX_VALUE, Long.MAX_VALUE, r));
		float dir = ZoneBlender.sample(twoWinds, 0, 64, 0, 192, 224, r).state().windDirection();
		boolean nearNorth = dir < 1.0F || dir > 359.0F;
		check("350 deg and 10 deg average to north, not south", nearNorth, "got " + dir);

		System.out.println("\n[9] edge dithering is stable in world space and matches coverage");
		WeatherSample half = new WeatherSample(WeatherState.CLEAR, 0.5F, 1.0F);
		boolean stable = true;

		for (int i = 0; i < 1000; i++) {
			if (half.shouldDrawColumn(42L, i, -i) != half.shouldDrawColumn(42L, i, -i)) {
				stable = false;
			}
		}

		int drawn = 0;

		for (int x = -100; x < 100; x++) {
			for (int z = -100; z < 100; z++) {
				if (half.shouldDrawColumn(42L, x, z)) {
					drawn++;
				}
			}
		}

		float fraction = drawn / 40_000.0F;
		check("a column's decision never changes between frames", stable, "flickered");
		check("drawn fraction tracks coverage (0.5)", Math.abs(fraction - 0.5F) < 0.02F, "got " + fraction);

		WeatherSample full = new WeatherSample(WeatherState.CLEAR, 1.0F, 1.0F);
		WeatherSample none = new WeatherSample(WeatherState.CLEAR, 0.0F, 1.0F);
		check("full coverage draws every column", full.shouldDrawColumn(42L, 3, 9), "missed a column");
		check("zero coverage draws none", !none.shouldDrawColumn(42L, 3, 9), "drew a column");

		System.out.println("\n[10] server-side rain level is a maximum, not an average");
		List<WeatherZone> mixed = new ArrayList<>();
		mixed.add(new WeatherZone(5L, 0, 0, 100, 50, 0, 0,
				new WeatherState(1.0F, 0.9F, 0.0F, 0.0F, 0.0F, 0.0F), Long.MAX_VALUE, Long.MAX_VALUE, r));
		mixed.add(new WeatherZone(6L, 9000, 0, 100, 50, 0, 0, WeatherState.CLEAR, Long.MAX_VALUE, Long.MAX_VALUE, r));
		check("one storm among clear zones keeps the gate open",
				Math.abs(ZoneBlender.maxPrecip(mixed) - 0.9F) < 1e-6F, String.valueOf(ZoneBlender.maxPrecip(mixed)));

		System.out.println("\n[11] zone radius is skewed to the middle, not uniform");
		ZoneSpawnParams params = ZoneSpawnParams.defaults();
		Random sizeRng = new Random(99L);
		int middle = 0;
		int extremes = 0;
		double lowest = Double.MAX_VALUE;
		double highest = 0.0;

		for (int i = 0; i < 200_000; i++) {
			double radius = params.rollRadius(sizeRng);
			lowest = Math.min(lowest, radius);
			highest = Math.max(highest, radius);

			double t = (radius - params.minRadius()) / (params.maxRadius() - params.minRadius());

			if (t > 0.4 && t < 0.6) {
				middle++;
			}

			if (t < 0.1 || t > 0.9) {
				extremes++;
			}
		}

		check("radii stay inside the configured range",
				lowest >= params.minRadius() && highest <= params.maxRadius(),
				lowest + ".." + highest);
		check("the middle fifth is far more common than both outer tenths",
				middle > extremes * 2, "middle=" + middle + " extremes=" + extremes);
		System.out.println("        middle fifth " + (middle / 2000) + "%, outer tenths "
				+ (extremes / 2000) + "%");

		System.out.println("\n[12] blend band fits inside every legal zone (the review blocker)");
		boolean bandFits = true;
		boolean bandResolvable = true;

		for (double radius = params.minRadius(); radius <= params.maxRadius(); radius += 1.0) {
			double band = params.blendBand(radius);

			// Must leave a full-intensity core: a band wider than half the radius means the zone
			// never reaches full strength anywhere, which is exactly what the old 96-block
			// minimum did to a 64-block zone.
			if (band > radius * 0.5 + 1e-9) {
				bandFits = false;
			}

			// Must be wide enough for the sample grid to resolve the gradient.
			if (band < 2.0 * params.gridStep() - 1e-9) {
				bandResolvable = false;
			}
		}

		check("band never exceeds half the radius, so a core always exists", bandFits, "band too wide");
		check("band always spans at least two grid steps", bandResolvable, "band too narrow");
		System.out.println("        min radius " + params.minRadius() + " -> band "
				+ params.blendBand(params.minRadius()) + ", max radius " + params.maxRadius()
				+ " -> band " + params.blendBand(params.maxRadius()));

		boolean rejected = false;

		try {
			// 64 < 4 * 48: the combination the old defaults silently shipped.
			new ZoneSpawnParams(64.0, 1024.0, 3, 0.35, 48.0, 0.02, 0.02, 768.0);
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}

		check("an incompatible radius/grid-step pair is rejected at construction", rejected,
				"the impossible config was accepted");

		System.out.println("\n[13] zone drift is far slower than the wind the player feels");
		double[] drift = params.drift(90.0F, 1.0F);
		double perDay = Math.hypot(drift[0], drift[1]) * 24000.0;
		check("a zone travels well under one kilometre per game day at full wind", perDay < 1000.0,
				perDay + " blocks/day");
		System.out.println("        full wind moves a zone " + Math.round(perDay) + " blocks per game day");

		System.out.println("\n[14] fog is contextual and its chances compose without saturating");
		FogRules fog = FogRules.defaults();
		float quiet = fog.fogChance(FogRules.Context.NONE);
		float afterRain = fog.fogChance(new FogRules.Context(100L, 6000L, false, false));
		float everything = fog.fogChance(new FogRules.Context(100L, 23000L, true, true));

		check("with no context fog is rare", quiet < 0.1F, String.valueOf(quiet));
		check("just after rain fog is much more likely", afterRain > quiet * 3.0F,
				quiet + " -> " + afterRain);
		check("every context at once still stays a probability", everything < 1.0F && everything > afterRain,
				String.valueOf(everything));
		System.out.println("        quiet " + quiet + ", after rain " + afterRain + ", all contexts " + everything);

		check("the dawn window wraps past midnight", fog.isDawn(23500L) && fog.isDawn(500L) && !fog.isDawn(12000L),
				"dawn window did not wrap");

		System.out.println("\n[15] zones are born outside the view and never on top of a player");
		ZoneManager manager = new ZoneManager();
		ZoneManager.Settings settings = ZoneManager.Settings.defaults();
		WeatherTransitions live = WeatherTransitions.defaults(new Random(4242L));
		Random spawnRng = new Random(4242L);
		List<ZoneManager.Anchor> anchors = List.of(new ZoneManager.Anchor(0, 0, 256.0));

		boolean neverCoversAnchor = true;

		for (int i = 0; i < 2000; i++) {
			WeatherZone spawned = manager.spawnNear(anchors.get(0), 0L, r, live, params, spawnRng);

			// The near edge, not just the centre, must clear the horizon -- otherwise a large zone
			// spawned just out of sight would still swallow the player on its first tick.
			if (spawned.weightAt(0, 0) > 0.0F) {
				neverCoversAnchor = false;
			}
		}

		check("a freshly spawned zone never reaches the player", neverCoversAnchor,
				"a new zone covered the anchor");

		System.out.println("\n[16] the zone population converges and stays capped");
		ZoneManager pop = new ZoneManager();

		for (long tick = 1; tick <= 4000; tick++) {
			pop.tick(tick, anchors, r, live, params, settings, false, spawnRng);
		}

		check("population reaches the target for one player",
				pop.size() >= settings.targetZonesPerAnchor(),
				"only " + pop.size() + " zones");
		check("population does not run away", pop.size() <= settings.maxZones(),
				pop.size() + " zones exceeds the cap");
		System.out.println("        " + pop.size() + " zones around one player after 4000 ticks");

		ZoneManager capped = new ZoneManager();
		ZoneManager.Settings tight = new ZoneManager.Settings(50, 5, 2048.0, 72000L);

		for (long tick = 1; tick <= 3000; tick++) {
			capped.tick(tick, anchors, r, live, params, tight, false, spawnRng);
		}

		check("the hard cap is respected even when the target exceeds it", capped.size() <= 5,
				String.valueOf(capped.size()));

		System.out.println("\n[17] zones nobody visits are retired");
		ZoneManager abandoned = new ZoneManager();

		for (long tick = 1; tick <= 600; tick++) {
			abandoned.tick(tick, anchors, r, live, params, settings, false, spawnRng);
		}

		int beforeLeaving = abandoned.size();
		check("zones exist while a player is present", beforeLeaving > 0, "none spawned");

		// Everyone logs out: no anchors at all, so nothing is ever marked as seen again.
		for (long tick = 601; tick <= 601 + settings.unloadedTtlTicks() + 100; tick++) {
			abandoned.tick(tick, List.of(), r, live, params, settings, false, spawnRng);
		}

		check("they are dropped once the time-to-live expires", abandoned.size() == 0,
				abandoned.size() + " zones survived with no players");
		System.out.println("        " + beforeLeaving + " zones -> 0 after the ttl elapsed with nobody online");

		System.out.println("\n[18] freezing stops weather changing but not the wind");
		ZoneManager frozen = new ZoneManager();
		WeatherZone drifting = new WeatherZone(900L, 0, 0, 300, 100, 0.02, 0.0,
				new WeatherState(1.0F, 1.0F, 0.0F, 0.0F, 0.5F, 90.0F), 0L, Long.MAX_VALUE, r);
		frozen.add(drifting);
		WeatherState stateBefore = drifting.state();
		double xBefore = drifting.centerX();

		for (long tick = 1; tick <= 500; tick++) {
			frozen.tick(tick, anchors, r, live, params, settings, true, spawnRng);
		}

		check("the weather state is unchanged while frozen", drifting.state().equals(stateBefore),
				stateBefore + " became " + drifting.state());
		check("the zone still drifts while frozen", drifting.centerX() > xBefore + 1.0,
				"moved " + (drifting.centerX() - xBefore) + " blocks");
		check("no new zones appear while frozen", frozen.size() == 1, String.valueOf(frozen.size()));

		System.out.println("\n[19] grid nodes survive the eight-byte round trip");
		byte[] buffer = new byte[GridCodec.byteLength(3)];
		Random codecRng = new Random(31337L);
		float worstUnit = 0.0F;
		float worstAngle = 0.0F;

		for (int i = 0; i < 100_000; i++) {
			WeatherSample original = new WeatherSample(
					new WeatherState(codecRng.nextFloat(), codecRng.nextFloat(), codecRng.nextFloat(),
							codecRng.nextFloat(), codecRng.nextFloat(), codecRng.nextFloat() * 360.0F)
							.sanitize(r),
					codecRng.nextFloat(),
					1.0F);

			GridCodec.pack(original, buffer, GridCodec.NODE_BYTES);
			WeatherSample decoded = GridCodec.unpack(buffer, GridCodec.NODE_BYTES, 1.0F);

			worstUnit = Math.max(worstUnit, Math.abs(decoded.state().clouds() - original.state().clouds()));
			worstUnit = Math.max(worstUnit, Math.abs(decoded.state().precip() - original.state().precip()));
			worstUnit = Math.max(worstUnit, Math.abs(decoded.state().thunder() - original.state().thunder()));
			worstUnit = Math.max(worstUnit, Math.abs(decoded.state().fog() - original.state().fog()));
			worstUnit = Math.max(worstUnit, Math.abs(decoded.coverage() - original.coverage()));
			worstAngle = Math.max(worstAngle, Math.abs(MathUtil.angleDelta(
					original.state().windDirection(), decoded.state().windDirection())));
		}

		check("intensity error stays within one quantisation step", worstUnit <= GridCodec.unitError() + 1e-6F,
				"worst " + worstUnit + " allowed " + GridCodec.unitError());
		check("bearing error stays within one quantisation step",
				worstAngle <= GridCodec.angleErrorDegrees() + 1e-4F,
				"worst " + worstAngle + " allowed " + GridCodec.angleErrorDegrees());
		System.out.println("        worst intensity error " + worstUnit + ", worst bearing error "
				+ worstAngle + " deg");

		check("a node writes exactly eight bytes and touches no neighbour",
				buffer[0] == 0 && buffer[GridCodec.NODE_BYTES * 2] == 0, "wrote outside its slot");

		System.out.println("\n[20] a full default grid stays a sane packet size");
		int nodes = (2 * 27 + 1) * (2 * 27 + 1);
		int bytes = GridCodec.byteLength(nodes);
		check("the full grid fits comfortably under 32 KiB", bytes < 32 * 1024, bytes + " bytes");
		System.out.println("        " + nodes + " nodes = " + bytes + " bytes ("
				+ (bytes / 1024) + " KiB) for the one-off full send, "
				+ GridCodec.byteLength(2 * 27 + 1) + " bytes for a one-row delta");

		System.out.println("\n[21] the grid is anchored to the world, not to the player");
		List<WeatherZone> gridZones = List.of(new WeatherZone(700L, 0, 0, 400, 140, 0, 0,
				new WeatherState(1.0F, 0.8F, 0.0F, 0.0F, 0.5F, 45.0F), Long.MAX_VALUE, Long.MAX_VALUE, r));

		WeatherGridBuilder.Grid atOrigin =
				WeatherGridBuilder.build(gridZones, 0, 0, 27, 16.0, 192, 224, r);
		// Move a fraction of a step. A player-anchored grid would shift and invalidate everything.
		WeatherGridBuilder.Grid nudged =
				WeatherGridBuilder.build(gridZones, 7.0, 3.0, 27, 16.0, 192, 224, r);

		check("a sub-step move leaves the lattice origin alone",
				atOrigin.originNodeX() == nudged.originNodeX()
						&& atOrigin.originNodeZ() == nudged.originNodeZ(),
				atOrigin.originNodeX() + "," + atOrigin.originNodeZ() + " vs "
						+ nudged.originNodeX() + "," + nudged.originNodeZ());
		check("and produces an identical grid", java.util.Arrays.equals(atOrigin.data(), nudged.data()),
				"grid changed after a 7-block step");

		WeatherGridBuilder.Delta unchanged = WeatherGridBuilder.diff(atOrigin, nudged, 0.4F);
		check("so the delta is empty", !unchanged.full() && unchanged.changes().isEmpty(),
				unchanged.changes().size() + " changes");

		System.out.println("\n[22] walking one step exposes one row and nothing else");
		WeatherGridBuilder.Grid moved =
				WeatherGridBuilder.build(gridZones, 16.0, 0, 27, 16.0, 192, 224, r);
		WeatherGridBuilder.Delta rowDelta = WeatherGridBuilder.diff(atOrigin, moved, 0.4F);

		check("the delta is a patch, not a full resend", !rowDelta.full(), "fell back to full");
		check("only one column of nodes is new", rowDelta.changes().size() <= moved.side(),
				rowDelta.changes().size() + " changes for a " + moved.side() + "-node column");
		System.out.println("        " + rowDelta.changes().size() + " nodes = "
				+ rowDelta.byteLength() + " bytes, against "
				+ moved.data().length + " for a full send");

		System.out.println("\n[23] applying a delta reproduces the server's grid exactly");
		WeatherGridBuilder.Grid rebuilt = WeatherGridBuilder.apply(atOrigin, rowDelta);
		check("client copy matches the server byte for byte",
				java.util.Arrays.equals(rebuilt.data(), moved.data()), "mismatch after applying the delta");
		check("origins match", rebuilt.originNodeX() == moved.originNodeX()
				&& rebuilt.originNodeZ() == moved.originNodeZ(), "origin mismatch");

		System.out.println("\n[24] a teleport falls back to a full send instead of a huge patch");
		WeatherGridBuilder.Grid faraway =
				WeatherGridBuilder.build(gridZones, 100_000, 100_000, 27, 16.0, 192, 224, r);
		WeatherGridBuilder.Delta jump = WeatherGridBuilder.diff(atOrigin, faraway, 0.4F);
		check("no overlap means a full send", jump.full(), "tried to patch a disjoint grid");
		check("a full send costs the grid, not the grid plus indices",
				jump.byteLength() == faraway.data().length, String.valueOf(jump.byteLength()));

		WeatherGridBuilder.Grid firstEver =
				WeatherGridBuilder.diff(null, atOrigin, 0.4F).grid();
		check("a client with no grid at all gets a full send",
				WeatherGridBuilder.diff(null, atOrigin, 0.4F).full() && firstEver != null, "not full");

		System.out.println("\n[25] the grid actually describes the zone it covers");
		int centreIndex = atOrigin.indexOf(0, 0);
		WeatherSample centreSample = atOrigin.sampleAt(centreIndex, 1.0F);
		int cornerIndex = atOrigin.indexOf(atOrigin.originNodeX(), atOrigin.originNodeZ());
		WeatherSample cornerSample = atOrigin.sampleAt(cornerIndex, 1.0F);

		check("the node at the zone core reports full coverage", centreSample.coverage() > 0.99F,
				String.valueOf(centreSample.coverage()));
		check("the node at the zone core reports precipitation", centreSample.state().precip() > 0.5F,
				String.valueOf(centreSample.state().precip()));
		check("a corner far outside the zone reports clear", cornerSample.coverage() == 0.0F,
				String.valueOf(cornerSample.coverage()));

		System.out.println("\n[26] zones survive a save and load unchanged");
		ZoneManager saving = new ZoneManager();

		for (long tick = 1; tick <= 3000; tick++) {
			saving.tick(tick, anchors, r, live, params, settings, false, spawnRng);
		}

		// Put one zone mid-transition, so the phase and target are exercised rather than only
		// the steady case a freshly spawned zone would give.
		WeatherZone midFade = new WeatherZone(555L, 120, -80, 400, 140, 0.01, -0.005,
				new WeatherState(1.0F, 0.8F, 0.4F, 0.2F, 0.6F, 275.0F), 50L, 99999L, r);
		midFade.tick(60L, r, clearing, false);
		saving.add(midFade);

		String blob = ZonePersistence.toBase64(saving.zones(), saving.nextId());
		ZonePersistence.Snapshot loaded = ZonePersistence.fromBase64(blob, r);

		check("every zone comes back", loaded.zones().size() == saving.size(),
				saving.size() + " saved, " + loaded.zones().size() + " loaded");
		check("the id counter comes back", loaded.nextId() == saving.nextId(),
				saving.nextId() + " vs " + loaded.nextId());

		boolean fieldsMatch = true;

		for (int i = 0; i < saving.size(); i++) {
			WeatherZone before = saving.zones().get(i);
			WeatherZone after = loaded.zones().get(i);

			if (before.id() != after.id()
					|| before.centerX() != after.centerX()
					|| before.centerZ() != after.centerZ()
					|| before.radius() != after.radius()
					|| before.blendBand() != after.blendBand()
					|| before.driftX() != after.driftX()
					|| before.driftZ() != after.driftZ()
					|| !before.state().equals(after.state())
					|| !before.target().equals(after.target())
					|| before.phase() != after.phase()
					|| before.stateExpiryTick() != after.stateExpiryTick()
					|| before.deathTick() != after.deathTick()
					|| before.lastSeenTick() != after.lastSeenTick()) {
				fieldsMatch = false;
			}
		}

		check("every field survives exactly", fieldsMatch, "a field changed across the round trip");
		System.out.println("        " + saving.size() + " zones -> " + blob.length()
				+ " base64 chars (" + (blob.length() / Math.max(1, saving.size())) + " per zone)");

		System.out.println("\n[27] unreadable saved data costs weather, never the world");
		check("an empty string loads as empty",
				ZonePersistence.fromBase64("", r).zones().isEmpty(), "not empty");
		check("null loads as empty",
				ZonePersistence.fromBase64(null, r).zones().isEmpty(), "not empty");
		check("garbage loads as empty",
				ZonePersistence.fromBase64("!!!not base64!!!", r).zones().isEmpty(), "not empty");

		byte[] truncated = java.util.Arrays.copyOf(ZonePersistence.toBytes(saving.zones(), 9L), 40);
		check("a truncated blob loads as empty",
				ZonePersistence.fromBytes(truncated, r).zones().isEmpty(), "not empty");

		byte[] wrongVersion = ZonePersistence.toBytes(saving.zones(), 9L);
		wrongVersion[7] = (byte) (ZonePersistence.FORMAT_VERSION + 1);
		check("a future format version is discarded, not guessed at",
				ZonePersistence.fromBytes(wrongVersion, r).zones().isEmpty(), "not empty");

		check("an empty zone list round-trips",
				ZonePersistence.fromBase64(ZonePersistence.toBase64(List.of(), 7L), r).nextId() == 7L,
				"lost the id counter");

		System.out.println("\n[28] a delta survives being flattened for the wire");
		byte[] packedChanges = rowDelta.changesToBytes();
		List<WeatherGridBuilder.Change> unpacked = WeatherGridBuilder.changesFromBytes(packedChanges);

		check("the change count survives", unpacked.size() == rowDelta.changes().size(),
				rowDelta.changes().size() + " -> " + unpacked.size());

		boolean changesMatch = unpacked.size() == rowDelta.changes().size();

		for (int i = 0; changesMatch && i < unpacked.size(); i++) {
			if (unpacked.get(i).index() != rowDelta.changes().get(i).index()
					|| !java.util.Arrays.equals(unpacked.get(i).node(), rowDelta.changes().get(i).node())) {
				changesMatch = false;
			}
		}

		check("every index and node survives", changesMatch, "a change differed after unpacking");
		check("the packed length matches the advertised cost",
				packedChanges.length == rowDelta.byteLength(),
				packedChanges.length + " vs " + rowDelta.byteLength());
		check("a malformed length yields nothing rather than half a patch",
				WeatherGridBuilder.changesFromBytes(new byte[7]).isEmpty(), "half-read a bad array");
		check("null yields nothing",
				WeatherGridBuilder.changesFromBytes(null).isEmpty(), "did not reject null");

		// Applying the unpacked changes must reproduce the same grid as applying the original ones,
		// which is what the client actually does with what arrives over the wire.
		WeatherGridBuilder.Grid fromWire = WeatherGridBuilder.apply(atOrigin,
				new WeatherGridBuilder.Delta(moved, unpacked, false));
		check("the client rebuilds the server grid from the wire form",
				java.util.Arrays.equals(fromWire.data(), moved.data()), "mismatch after the wire round trip");

		check("the default extent fits the two-byte node index",
				27 <= WeatherGridBuilder.maxHalfExtent(),
				"27 exceeds " + WeatherGridBuilder.maxHalfExtent());
		System.out.println("        largest half extent a u16 index allows: "
				+ WeatherGridBuilder.maxHalfExtent() + " nodes");

		System.out.println("\n[29] client params survive the wire and degrade rather than break");
		ClientWeatherParams params0 = ClientWeatherParams.defaults();
		ClientWeatherParams back = ClientWeatherParams.fromBytes(params0.toBytes());

		check("thresholds survive exactly", back.rules().equals(params0.rules()), "rules differed");
		check("the cloud band survives",
				back.cloudBottom() == params0.cloudBottom() && back.cloudTop() == params0.cloudTop(),
				back.cloudBottom() + ".." + back.cloudTop());
		check("render limits survive",
				back.weatherRadius() == params0.weatherRadius() && back.maxColumns() == params0.maxColumns()
						&& back.maxTiltTan() == params0.maxTiltTan(),
				"a render limit differed");
		check("flags survive",
				back.tiltEnabled() == params0.tiltEnabled() && back.windStreaks() == params0.windStreaks()
						&& back.frozen() == params0.frozen(),
				"a flag differed");
		System.out.println("        " + params0.toBytes().length + " bytes, sent once per join");

		check("empty params fall back to defaults",
				ClientWeatherParams.fromBytes(new byte[0]).rules().equals(WeatherRules.defaults()),
				"not defaults");
		check("null params fall back to defaults",
				ClientWeatherParams.fromBytes(null).rules().equals(WeatherRules.defaults()), "not defaults");

		byte[] futureVersion = params0.toBytes();
		futureVersion[3] = (byte) (ClientWeatherParams.FORMAT_VERSION + 1);
		check("a version mismatch renders with defaults rather than disconnecting",
				ClientWeatherParams.fromBytes(futureVersion).rules().equals(WeatherRules.defaults()),
				"did not fall back");
		check("a truncated packet falls back too",
				ClientWeatherParams.fromBytes(java.util.Arrays.copyOf(params0.toBytes(), 20))
						.rules().equals(WeatherRules.defaults()), "did not fall back");

		check("the altitude cutoff comes from the params' own cloud band",
				params0.altitudeFactor(64) == 1.0F && params0.altitudeFactor(400) == 0.0F,
				params0.altitudeFactor(64) + " / " + params0.altitudeFactor(400));

		System.out.println("\n[30] a full grid splits into wire-sized batches");
		List<WeatherGridBuilder.Change> allNodes = WeatherGridBuilder.allNodes(atOrigin);
		check("a full send covers every node", allNodes.size() == atOrigin.nodeCount(),
				allNodes.size() + " of " + atOrigin.nodeCount());

		List<byte[]> batches = WeatherGridBuilder.batch(allNodes, 512);
		int batchedNodes = 0;
		int largestBatch = 0;

		for (byte[] batch : batches) {
			batchedNodes += WeatherGridBuilder.changesFromBytes(batch).size();
			largestBatch = Math.max(largestBatch, batch.length);
		}

		check("batching loses no nodes", batchedNodes == allNodes.size(),
				batchedNodes + " of " + allNodes.size());
		check("no packet approaches the size a full grid would be", largestBatch < 8192,
				largestBatch + " bytes");
		System.out.println("        " + atOrigin.nodeCount() + " nodes -> " + batches.size()
				+ " packets, largest " + largestBatch + " bytes (a single full send would be "
				+ atOrigin.data().length + ")");

		// Rebuilding from the batches must give the server's grid back, which is what a joining
		// client does: an empty grid, then every batch applied in turn.
		WeatherGridBuilder.Grid assembled = new WeatherGridBuilder.Grid(atOrigin.originNodeX(),
				atOrigin.originNodeZ(), atOrigin.halfExtent(), atOrigin.step(),
				new byte[atOrigin.data().length]);

		for (byte[] batch : batches) {
			assembled = WeatherGridBuilder.apply(assembled, new WeatherGridBuilder.Delta(
					assembled, WeatherGridBuilder.changesFromBytes(batch), false));
		}

		check("applying every batch reproduces the grid",
				java.util.Arrays.equals(assembled.data(), atOrigin.data()), "mismatch after reassembly");
		check("an empty change list produces no packets",
				WeatherGridBuilder.batch(List.of(), 512).isEmpty(), "produced a packet");

		System.out.println("\n================================");
		System.out.println("passed " + passed + ", failed " + failed);
		System.out.println("================================");

		if (failed > 0) {
			System.exit(1);
		}
	}
}
