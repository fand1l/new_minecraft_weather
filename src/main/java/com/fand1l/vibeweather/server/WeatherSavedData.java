package com.fand1l.vibeweather.server;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import com.fand1l.vibeweather.VibeWeather;
import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.weather.WeatherZone;
import com.fand1l.vibeweather.weather.ZonePersistence;

/**
 * Per-dimension persistence for the zone list and the freeze flag.
 *
 * <p>The zones themselves are serialised by {@link ZonePersistence} into a Base64 string, so the
 * codec here has two fields rather than a nested structure. That keeps the format definition in the
 * pure package where it is round-tripped by the harness, and keeps this class small enough that a
 * field moving in {@code WeatherZone} does not require touching Minecraft-facing code.
 */
public final class WeatherSavedData extends SavedData {
	public static final Codec<WeatherSavedData> CODEC = RecordCodecBuilder.create(
			instance -> instance.group(
					Codec.STRING.fieldOf("zones").forGetter(WeatherSavedData::encodedZones),
					Codec.BOOL.fieldOf("frozen").forGetter(WeatherSavedData::frozen)
			).apply(instance, WeatherSavedData::new));

	/**
	 * The datafix type is null on purpose.
	 *
	 * <p>{@code DataFixTypes} holds only vanilla values, so a mod has nothing correct to pass;
	 * borrowing a vanilla one would run vanilla's fixers over data they know nothing about. Fabric
	 * API does exactly this in its own attachment storage, noting that object-builder-api 12.1.0
	 * and later makes the argument a no-op. This project depends on 24.1.0.
	 */
	public static final SavedDataType<WeatherSavedData> TYPE = new SavedDataType<>(
			VibeWeather.id("weather_zones"),
			WeatherSavedData::new,
			CODEC,
			null);

	private String encodedZones;
	private boolean frozen;

	public WeatherSavedData() {
		this("", false);
	}

	public WeatherSavedData(String encodedZones, boolean frozen) {
		this.encodedZones = encodedZones == null ? "" : encodedZones;
		this.frozen = frozen;
	}

	/** Loads, or creates, this dimension's storage. */
	public static WeatherSavedData get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public String encodedZones() {
		return encodedZones;
	}

	public boolean frozen() {
		return frozen;
	}

	public void setFrozen(boolean value) {
		if (frozen != value) {
			frozen = value;
			setDirty();
		}
	}

	public ZonePersistence.Snapshot loadZones(WeatherRules rules) {
		return ZonePersistence.fromBase64(encodedZones, rules);
	}

	/**
	 * Stores the zone list.
	 *
	 * <p>Marks itself dirty only when the encoding actually changed. Zones drift every tick, so an
	 * unconditional {@code setDirty} would have the world's saved data rewritten on every save even
	 * when nothing meaningful moved.
	 */
	public void storeZones(List<WeatherZone> zones, long nextId) {
		String encoded = ZonePersistence.toBase64(zones, nextId);

		if (!encoded.equals(encodedZones)) {
			encodedZones = encoded;
			setDirty();
		}
	}
}
