package com.fand1l.vibeweather.weather;

import com.fand1l.vibeweather.api.WeatherSample;
import com.fand1l.vibeweather.api.WeatherState;
import com.fand1l.vibeweather.util.MathUtil;

/**
 * Packs a weather sample into eight bytes for the client grid, and back again.
 *
 * <p>Deliberately operates on plain byte arrays rather than a Minecraft buffer, so the quantisation
 * -- the part with real precision consequences -- can be checked without a game. The network payload
 * is a thin wrapper that copies these bytes.
 *
 * <p>Layout, eight bytes per node:
 *
 * <pre>
 *   0  cloud cover        u8   step 1/255
 *   1  precipitation      u8   step 1/255
 *   2  thunder            u8   step 1/255
 *   3  fog                u8   step 1/255
 *   4  wind strength      u8   step 1/255
 *   5  coverage           u8   step 1/255, drives the dithered zone edge
 *   6..7 wind direction   u16  step 360/65536 degrees
 * </pre>
 *
 * <p>Wind direction gets sixteen bits while everything else gets eight because it is the one axis
 * whose quantisation is directly visible: precipitation tilt is computed from the bearing, so a
 * coarse step would make the rain angle jump in discrete notches as the wind swings.
 *
 * <p>Discrete bands are not transmitted. The client derives them from these floats using the
 * thresholds the server already sent, so there is exactly one source of truth for where a band
 * begins.
 */
public final class GridCodec {
	/** Bytes per grid node. */
	public static final int NODE_BYTES = 8;

	/** Byte offsets of the 0..1 axes inside a node, for reading one field without unpacking all of them. */
	public static final int FIELD_CLOUDS = 0;
	public static final int FIELD_PRECIP = 1;
	public static final int FIELD_THUNDER = 2;
	public static final int FIELD_FOG = 3;
	public static final int FIELD_WIND = 4;
	public static final int FIELD_COVERAGE = 5;

	private GridCodec() {
	}

	/**
	 * One 0..1 axis of one node.
	 *
	 * <p>Exists so the render path can ask "how hard is it raining here" per column without building
	 * a sample object for each one. The brief forbids allocating in the render loop, and a column
	 * sweep at the configured radius is tens of thousands of calls a frame.
	 */
	public static float unit(byte[] source, int nodeOffset, int field) {
		return dequantiseUnit(source[nodeOffset + field]);
	}

	/** The wind bearing of one node, in degrees. */
	public static float angle(byte[] source, int nodeOffset) {
		int raw = (source[nodeOffset + 6] & 0xFF) | ((source[nodeOffset + 7] & 0xFF) << 8);
		return dequantiseAngle(raw);
	}

	public static int byteLength(int nodeCount) {
		return nodeCount * NODE_BYTES;
	}

	/** Writes one node at {@code offset}. The array must hold {@link #NODE_BYTES} more bytes. */
	public static void pack(WeatherSample sample, byte[] destination, int offset) {
		WeatherState state = sample.state();
		destination[offset] = quantiseUnit(state.clouds());
		destination[offset + 1] = quantiseUnit(state.precip());
		destination[offset + 2] = quantiseUnit(state.thunder());
		destination[offset + 3] = quantiseUnit(state.fog());
		destination[offset + 4] = quantiseUnit(state.windStrength());
		destination[offset + 5] = quantiseUnit(sample.coverage());

		int direction = quantiseAngle(state.windDirection());
		destination[offset + 6] = (byte) (direction & 0xFF);
		destination[offset + 7] = (byte) ((direction >>> 8) & 0xFF);
	}

	/**
	 * Reads one node.
	 *
	 * <p>The altitude factor is not transmitted: it depends only on the viewer's height and the
	 * cloud band, both of which the client already has, so sending it per node would waste a byte
	 * on a value identical across the whole grid.
	 */
	public static WeatherSample unpack(byte[] source, int offset, float altitudeFactor) {
		float clouds = dequantiseUnit(source[offset]);
		float precip = dequantiseUnit(source[offset + 1]);
		float thunder = dequantiseUnit(source[offset + 2]);
		float fog = dequantiseUnit(source[offset + 3]);
		float windStrength = dequantiseUnit(source[offset + 4]);
		float coverage = dequantiseUnit(source[offset + 5]);

		int direction = (source[offset + 6] & 0xFF) | ((source[offset + 7] & 0xFF) << 8);
		float windDirection = dequantiseAngle(direction);

		return new WeatherSample(
				new WeatherState(clouds, precip, thunder, fog, windStrength, windDirection),
				coverage,
				altitudeFactor);
	}

	/** Largest error a 0..1 axis can pick up in a round trip. */
	public static float unitError() {
		return 0.5F / 255.0F;
	}

	/** Largest error the wind bearing can pick up in a round trip, in degrees. */
	public static float angleErrorDegrees() {
		return 360.0F / 65536.0F;
	}

	private static byte quantiseUnit(float value) {
		return (byte) Math.round(MathUtil.clamp01(value) * 255.0F);
	}

	private static float dequantiseUnit(byte value) {
		return (value & 0xFF) / 255.0F;
	}

	private static int quantiseAngle(float degrees) {
		// Round-to-nearest would let 359.999 round up to 65536 and wrap to 0 -- correct as an angle,
		// but it makes the round trip look like a 360-degree error in a test. Truncating keeps the
		// result inside the range by construction.
		return (int) (MathUtil.wrapDegrees(degrees) / 360.0F * 65536.0F) & 0xFFFF;
	}

	private static float dequantiseAngle(int raw) {
		return MathUtil.wrapDegrees(raw / 65536.0F * 360.0F);
	}
}
