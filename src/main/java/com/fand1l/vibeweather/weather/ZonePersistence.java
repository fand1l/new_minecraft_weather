package com.fand1l.vibeweather.weather;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherState;

/**
 * Serialises the zone list to a versioned byte blob, and back.
 *
 * <p>Deliberately hand-rolled rather than built on Mojang's codec framework, for two reasons. It
 * keeps serialisation in the pure package where the harness can round-trip it without a game, and
 * it reduces the game-facing surface to a single {@code Codec.STRING.xmap} over the Base64 form --
 * one of the most stable pieces of that API, rather than a nested record codec that would have to
 * be rewritten every time a field moves.
 *
 * <p>The saved blob is therefore opaque in the world data rather than readable NBT. That is an
 * accepted trade: nobody hand-edits weather zones, and the format carries its own version so a
 * later change can be detected instead of silently misreading old data.
 */
public final class ZonePersistence {
	/** Bumped whenever the field layout changes. Older or newer blobs are discarded, not guessed at. */
	public static final int FORMAT_VERSION = 1;

	private static final int MAGIC = 0x56575A31; // "VWZ1"

	/** What a load produced: the zones, and the id counter so a reload cannot reuse an id. */
	public record Snapshot(List<WeatherZone> zones, long nextId) {
		public static final Snapshot EMPTY = new Snapshot(List.of(), 1L);
	}

	private ZonePersistence() {
	}

	public static String toBase64(List<WeatherZone> zones, long nextId) {
		return Base64.getEncoder().encodeToString(toBytes(zones, nextId));
	}

	/**
	 * Reads a blob, falling back to an empty snapshot on anything unreadable.
	 *
	 * <p>Corrupt or unrecognised saved data costs at most a few seconds of regenerated weather,
	 * whereas refusing to load would cost the player their world. So this never throws.
	 */
	public static Snapshot fromBase64(String encoded, WeatherRules rules) {
		if (encoded == null || encoded.isEmpty()) {
			return Snapshot.EMPTY;
		}

		try {
			return fromBytes(Base64.getDecoder().decode(encoded), rules);
		} catch (IllegalArgumentException | UncheckedIOException e) {
			return Snapshot.EMPTY;
		}
	}

	public static byte[] toBytes(List<WeatherZone> zones, long nextId) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 + zones.size() * 128);

		try (DataOutputStream out = new DataOutputStream(buffer)) {
			out.writeInt(MAGIC);
			out.writeInt(FORMAT_VERSION);
			out.writeLong(nextId);
			out.writeInt(zones.size());

			for (WeatherZone zone : zones) {
				out.writeLong(zone.id());
				out.writeDouble(zone.centerX());
				out.writeDouble(zone.centerZ());
				out.writeDouble(zone.radius());
				out.writeDouble(zone.blendBand());
				out.writeDouble(zone.driftX());
				out.writeDouble(zone.driftZ());
				writeState(out, zone.state());
				writeState(out, zone.target());
				out.writeByte(zone.phase().ordinal());
				out.writeLong(zone.stateExpiryTick());
				out.writeLong(zone.deathTick());
				out.writeLong(zone.lastSeenTick());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return buffer.toByteArray();
	}

	public static Snapshot fromBytes(byte[] bytes, WeatherRules rules) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
				return Snapshot.EMPTY;
			}

			long nextId = in.readLong();
			int count = in.readInt();

			if (count < 0) {
				return Snapshot.EMPTY;
			}

			List<WeatherZone> zones = new ArrayList<>(count);

			for (int i = 0; i < count; i++) {
				long id = in.readLong();
				double centerX = in.readDouble();
				double centerZ = in.readDouble();
				double radius = in.readDouble();
				double band = in.readDouble();
				double driftX = in.readDouble();
				double driftZ = in.readDouble();
				WeatherState current = readState(in);
				WeatherState target = readState(in);
				WeatherZone.Phase phase = phaseByOrdinal(in.readByte());
				long expiry = in.readLong();
				long death = in.readLong();
				long lastSeen = in.readLong();

				zones.add(WeatherZone.restore(id, centerX, centerZ, radius, band, driftX, driftZ,
						current, target, phase, expiry, death, lastSeen, rules));
			}

			return new Snapshot(zones, nextId);
		} catch (IOException e) {
			// Truncated blob: treat it as absent rather than propagating, per the class note.
			return Snapshot.EMPTY;
		}
	}

	private static void writeState(DataOutputStream out, WeatherState state) throws IOException {
		out.writeFloat(state.clouds());
		out.writeFloat(state.precip());
		out.writeFloat(state.thunder());
		out.writeFloat(state.fog());
		out.writeFloat(state.windStrength());
		out.writeFloat(state.windDirection());
	}

	private static WeatherState readState(DataInputStream in) throws IOException {
		return new WeatherState(in.readFloat(), in.readFloat(), in.readFloat(),
				in.readFloat(), in.readFloat(), in.readFloat());
	}

	private static WeatherZone.Phase phaseByOrdinal(int ordinal) {
		WeatherZone.Phase[] values = WeatherZone.Phase.values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : WeatherZone.Phase.STEADY;
	}
}
