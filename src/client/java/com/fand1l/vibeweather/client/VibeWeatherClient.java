package com.fand1l.vibeweather.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.fand1l.vibeweather.net.VibeWeatherPayloads;
import com.fand1l.vibeweather.server.WeatherHooks;

/**
 * Client entry point: receive, hold, and hand the weather to whoever asks.
 *
 * <p>Nothing here decides anything about the weather. The client is told what it is, and the mixins
 * that ask the questions ({@code getPrecipitationAt}, {@code getRainLevel}, the fog) are answered
 * from that. Keeping the decision on the server is what makes the block a player sees rain on the
 * same block whose fire goes out.
 */
public final class VibeWeatherClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Registered here rather than in common code: the common mixins run on both sides, and this is
		// how they reach a client answer without the common source set depending on the client one.
		WeatherHooks.setClientSource(ClientWeatherState.get());
		SodiumCompat.logAtStartup();

		ClientPlayNetworking.registerGlobalReceiver(VibeWeatherPayloads.PARAMS,
				(payload, context) -> ClientWeatherState.get().acceptParams(payload.params()));

		ClientPlayNetworking.registerGlobalReceiver(VibeWeatherPayloads.GRID,
				(payload, context) -> ClientWeatherState.get()
						.acceptGrid(payload, Minecraft.getInstance().level));

		ClientTickEvents.END_CLIENT_TICK.register(VibeWeatherClient::tick);
	}

	private static void tick(Minecraft client) {
		ClientWeatherState state = ClientWeatherState.get();

		// A null level covers the menu, the loading screen and the moment after a disconnect. Passing
		// it through rather than returning early is deliberate: that is what drops the stale grid, so
		// rejoining a different world cannot briefly show the previous one's storm.
		if (client.level == null || client.player == null) {
			state.tick(null, 0.0, 0.0, 0.0);
			return;
		}

		Vec3 position = client.player.position();
		state.tick(client.level, position.x, position.y, position.z);
		SodiumCompat.warnOnce(client);
	}
}
