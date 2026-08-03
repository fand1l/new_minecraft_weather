package com.fand1l.vibeweather.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.loader.api.FabricLoader;

import com.fand1l.vibeweather.VibeWeather;

/**
 * Degrades gracefully when Sodium is installed.
 *
 * <p>Sodium replaces large parts of the render path, including the weather pass. Anything this mod
 * draws itself -- the tilted precipitation quads and the wind streaks -- is built on vanilla's
 * pipelines and target, so with Sodium present it is switched off rather than left to crash or draw
 * over the world. Everything that is not drawing keeps working: the weather model, where it rains,
 * the sounds, the fog values and the wind physics.
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

	/** Whether this mod may issue its own draw calls at all. */
	public static boolean customDrawAllowed() {
		return !PRESENT;
	}

	public static void logAtStartup() {
		if (PRESENT) {
			VibeWeather.LOGGER.warn("Vibe Weather: Sodium detected. Precipitation tilt and the custom"
					+ " weather draw are disabled; the weather model, sounds and wind physics still work.");
		}
	}

	/**
	 * Tells the player once, in chat.
	 *
	 * <p>In the log alone this would be missed by exactly the people it is for: someone who installed
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
