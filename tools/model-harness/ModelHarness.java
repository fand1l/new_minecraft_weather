import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fand1l.vibeweather.api.CloudCover;
import com.fand1l.vibeweather.api.Precipitation;
import com.fand1l.vibeweather.api.ThunderLevel;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.weather.FogRules;
import com.fand1l.vibeweather.weather.WeatherTransitions;
import com.fand1l.vibeweather.weather.WeatherZone;
import com.fand1l.vibeweather.weather.ZoneBlender;
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

		System.out.println("\n================================");
		System.out.println("passed " + passed + ", failed " + failed);
		System.out.println("================================");

		if (failed > 0) {
			System.exit(1);
		}
	}
}
