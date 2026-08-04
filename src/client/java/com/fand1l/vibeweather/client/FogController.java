package com.fand1l.vibeweather.client;

import net.minecraft.client.renderer.fog.FogData;

import com.fand1l.vibeweather.util.MathUtil;

/**
 * Applies the fog axis to whatever fog the game already worked out.
 *
 * <p>Interpolates towards a configured distance rather than setting one. Vanilla's fog already varies
 * with biome, depth, time of day and -- through the rain level this mod supplies -- with
 * precipitation; overwriting it outright would flatten all of that into one number and make a foggy
 * morning underwater look like a foggy morning in a desert.
 *
 * <p>Which is also why nothing here reads the rain: {@code AtmosphericFogEnvironment} already
 * compresses fog from {@code getRainLevel}, and that method is already local. Rain fog arrives for
 * free; this is only the independent fog axis on top.
 */
public final class FogController {
	private FogController() {
	}

	public static void apply(FogData data) {
		if (data == null) {
			return;
		}

		ClientWeatherState state = ClientWeatherState.get();

		if (!state.ready()) {
			return;
		}

		float fog = state.fogAtViewer();

		if (fog <= 0.0F) {
			return;
		}

		float thick = state.params().fogThickDistance();
		float end = MathUtil.lerp(fog, data.environmentalEnd, thick);

		// Never push the far plane outwards. Underwater or in powdered snow the game has already
		// closed the fog in much further than any weather would, and lerping towards a weather
		// distance there would clear it up.
		if (end < data.environmentalEnd) {
			data.environmentalEnd = end;
			data.environmentalStart = Math.min(data.environmentalStart, end * 0.25F);
		}
	}
}
