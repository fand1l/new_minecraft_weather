package com.fand1l.vibeweather.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.loader.api.FabricLoader;

import com.fand1l.vibeweather.VibeWeather;

/**
 * Degrades gracefully when Sodium is installed.
 *
 * <p>Sodium replaces large parts of the render path. This mod turns out to need none of its own: the
 * precipitation tilt is written into vanilla's existing weather geometry rather than drawn
 * separately, so there is no pipeline, target or draw call here to conflict with. What that means in
 * practice is that everything keeps working, and the tilt specifically survives unless Sodium has
 * replaced the weather renderer itself -- which is worth saying out loud rather than leaving someone
 * to guess.
 *
 * <p>Detection is by mod id, not by class probing. Sodium's internals move between versions, so a
 * class lookup would be a second thing to keep up to date; the id is what the loader guarantees.
 */
public final class SodiumCompat {
	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("sodium");

	private static boolean toldThePlayer;

	private SodiumCompat() {
	}

	public static boolean present() {
		return PRESENT;
	}

	public static void logAtStartup() {
		if (PRESENT) {
			VibeWeather.LOGGER.warn("Vibe Weather: Sodium detected. This mod draws nothing of its own,"
					+ " so weather, sounds, fog and wind are unaffected; precipitation tilt rides on"
					+ " vanilla's weather renderer and will be missing if Sodium has replaced it.");
		}
	}

	/**
	 * Tells the player once, in chat.
	 *
	 * <p>In the log alone this would be missed by exactly the person it is for: someone who installed
	 * both mods and is wondering why the rain does not lean.
	 */
	public static void warnOnce(Minecraft client) {
		if (!PRESENT || toldThePlayer || client.player == null) {
			return;
		}

		toldThePlayer = true;
		client.player.displayClientMessage(Component.translatable("vibeweather.compat.sodium"), false);
	}
}
