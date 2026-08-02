import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import com.fand1l.vibeweather.config.ConfigManager;
import com.fand1l.vibeweather.config.VibeWeatherConfig;

/**
 * Checks the config layer: forgiving parsing, strict validation, and the promise that a rejected
 * file never partially takes effect.
 */
public final class ConfigHarness {
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

	static Path dir;

	static ConfigManager withFile(String json) throws Exception {
		Files.writeString(dir.resolve(ConfigManager.FILE_NAME), json, StandardCharsets.UTF_8);
		return new ConfigManager(dir);
	}

	public static void main(String[] args) throws Exception {
		dir = Files.createTempDirectory("vibeweather-config");

		System.out.println("[1] first run writes defaults and they validate");
		ConfigManager fresh = new ConfigManager(dir);
		ConfigManager.Result first = fresh.load();
		check("default config is written and applied", first.applied(), first.message());
		check("the file now exists", Files.exists(fresh.path()), "no file written");

		String written = Files.readString(fresh.path());
		check("keys are snake_case on disk", written.contains("min_radius") && written.contains("intensity_epsilon"),
				"unexpected key naming");
		check("camelCase keys do not appear", !written.contains("minRadius"), "camelCase leaked into the file");

		System.out.println("\n[2] a missing key keeps its default, an unknown key is ignored");
		ConfigManager partial = withFile("""
				{
				  "zones": { "min_radius": 128.0 },
				  "totally_unknown_key": { "nested": 5 }
				}
				""");
		ConfigManager.Result partialResult = partial.reload();
		check("a partial file is accepted", partialResult.applied(), partialResult.message());
		check("the key present is applied", partial.get().zones.minRadius == 128.0,
				String.valueOf(partial.get().zones.minRadius));
		check("a sibling key keeps its default", partial.get().zones.maxRadius == 1024.0,
				String.valueOf(partial.get().zones.maxRadius));
		check("an absent section keeps its defaults", partial.get().thresholds.overcastFloor == 0.75F,
				String.valueOf(partial.get().thresholds.overcastFloor));
		check("an unknown key does not fail the load", partialResult.applied(), partialResult.message());

		System.out.println("\n[3] an explicit null section falls back rather than crashing");
		ConfigManager nulled = withFile("{ \"zones\": null, \"wind\": { \"boat_entity_types\": null } }");
		ConfigManager.Result nullResult = nulled.reload();
		check("null sections are replaced with defaults", nullResult.applied(), nullResult.message());
		check("the nulled section is usable", nulled.get().zones.minRadius == 64.0,
				String.valueOf(nulled.get().zones.minRadius));
		check("a nulled list is usable", !nulled.get().wind.boatEntityTypes.isEmpty(), "list stayed null");

		System.out.println("\n[4] an empty file means defaults, not an error");
		ConfigManager empty = withFile("{}");
		check("an empty object loads defaults", empty.reload().applied(), "rejected");

		System.out.println("\n[5] invalid values are rejected with a specific reason");

		ConfigManager badOrder = withFile("{ \"thresholds\": { \"cloud_few\": 0.9, \"cloud_scattered\": 0.2 } }");
		ConfigManager.Result orderResult = badOrder.reload();
		check("out-of-order thresholds are rejected", !orderResult.applied(), "accepted");
		check("the message names the axis", orderResult.message().contains("clouds"), orderResult.message());
		System.out.println("        " + orderResult.message());

		// The exact combination that shipped before review: a 64-block zone cannot hold a blend band
		// of two 48-block grid steps and still have a core.
		ConfigManager badGrid = withFile("{ \"grid\": { \"step\": 48.0 } }");
		ConfigManager.Result gridResult = badGrid.reload();
		check("radius/grid-step conflict is rejected", !gridResult.applied(), "accepted");
		check("the message says what is required", gridResult.message().contains("4 x grid.step"),
				gridResult.message());
		System.out.println("        " + gridResult.message());

		ConfigManager badMode = withFile("{ \"gameplay\": { \"server_rain_level_mode\": \"SOMETIMES\" } }");
		ConfigManager.Result modeResult = badMode.reload();
		check("an unknown rain level mode is rejected", !modeResult.applied(), "accepted");
		check("the message lists the valid modes", modeResult.message().contains("NEAREST_PLAYER"),
				modeResult.message());

		ConfigManager badClouds = withFile("{ \"render\": { \"cloud_bottom\": 300.0, \"cloud_top\": 200.0 } }");
		check("an inverted cloud band is rejected", !badClouds.reload().applied(), "accepted");

		ConfigManager badJson = withFile("{ \"zones\": { oops }");
		ConfigManager.Result jsonResult = badJson.reload();
		check("malformed JSON is rejected", !jsonResult.applied(), "accepted");
		check("the message says the file is malformed", jsonResult.message().contains("malformed"),
				jsonResult.message());

		System.out.println("\n[6] a rejected reload leaves the active config untouched");
		ConfigManager stable = withFile("{ \"zones\": { \"min_radius\": 256.0 } }");
		check("the good config is applied", stable.reload().applied(), "rejected");
		double before = stable.get().zones.minRadius;

		Files.writeString(stable.path(), "{ \"thresholds\": { \"cloud_few\": 0.9, \"cloud_scattered\": 0.1 } }",
				StandardCharsets.UTF_8);
		ConfigManager.Result second = stable.reload();

		check("the bad reload is refused", !second.applied(), "accepted");
		check("the previously active values survive", stable.get().zones.minRadius == before,
				before + " became " + stable.get().zones.minRadius);
		check("the surviving config is still coherent", stable.get().thresholds.cloudFew == 0.20F,
				"partially applied: cloudFew=" + stable.get().thresholds.cloudFew);

		System.out.println("\n[7] the model records build from a loaded config");
		ConfigManager good = new ConfigManager(Files.createTempDirectory("vibeweather-config-2"));
		good.load();
		VibeWeatherConfig cfg = good.get();
		check("thresholds convert", cfg.toRules().overcastFloor() == 0.75F, "bad rules");
		check("zone params convert and validate", cfg.toSpawnParams().minRadius() == 64.0, "bad spawn params");
		check("fog rules convert", cfg.toFogRules().afterRainWindow() == 2400, "bad fog rules");
		check("transitions convert", cfg.toTransitions(new Random(1L)).durationBaseTicks() == 24000,
				"bad transitions");
		check("rain level mode parses", cfg.serverRainLevelMode() == VibeWeatherConfig.RainLevelMode.MAX,
				String.valueOf(cfg.serverRainLevelMode()));

		System.out.println("\n[8] writing then reading gives back the same values");
		cfg.zones.minRadius = 200.0;
		cfg.render.maxTiltTan = 0.5F;
		good.write(cfg);
		ConfigManager roundTrip = new ConfigManager(good.path().getParent());
		check("a round trip is accepted", roundTrip.reload().applied(), "rejected");
		check("a double survives", roundTrip.get().zones.minRadius == 200.0,
				String.valueOf(roundTrip.get().zones.minRadius));
		check("a float survives", roundTrip.get().render.maxTiltTan == 0.5F,
				String.valueOf(roundTrip.get().render.maxTiltTan));

		System.out.println("\n================================");
		System.out.println("passed " + passed + ", failed " + failed);
		System.out.println("================================");

		if (failed > 0) {
			System.exit(1);
		}
	}
}
