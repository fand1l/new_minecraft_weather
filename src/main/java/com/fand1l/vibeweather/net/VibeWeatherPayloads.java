package com.fand1l.vibeweather.net;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import com.fand1l.vibeweather.VibeWeather;

/**
 * The two packets the server sends, and their registration.
 *
 * <p>Both carry a byte array and a handful of numbers, nothing more. Everything structured is
 * flattened in the pure package, so the codecs here are assembled entirely from
 * {@link ByteBufCodecs} primitives and {@link StreamCodec#composite} -- shapes read directly from
 * the decompiled sources -- rather than from hand-written encoder and decoder lambdas.
 *
 * <p>There is deliberately no separate "full grid" packet. A full send is the first
 * {@link GridPayload} marked as a reset followed by however many more it takes, which keeps every
 * packet a few kilobytes and avoids depending on the byte-array codec's size limit.
 */
public final class VibeWeatherPayloads {
	public static final CustomPacketPayload.Type<GridPayload> GRID =
			new CustomPacketPayload.Type<>(VibeWeather.id("grid"));

	public static final CustomPacketPayload.Type<ParamsPayload> PARAMS =
			new CustomPacketPayload.Type<>(VibeWeather.id("params"));

	private VibeWeatherPayloads() {
	}

	/**
	 * A batch of grid nodes.
	 *
	 * @param originNodeX lattice index of the grid's lowest-x column
	 * @param originNodeZ lattice index of the grid's lowest-z row
	 * @param halfExtent  nodes either side of centre
	 * @param step        node spacing in blocks
	 * @param reset       true when the client should drop its grid and start from this origin
	 * @param nodes       packed changes: repeated two-byte index plus eight node bytes
	 */
	public record GridPayload(
			int originNodeX,
			int originNodeZ,
			int halfExtent,
			float step,
			boolean reset,
			byte[] nodes
	) implements CustomPacketPayload {
		public static final StreamCodec<ByteBuf, GridPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.INT, GridPayload::originNodeX,
				ByteBufCodecs.INT, GridPayload::originNodeZ,
				ByteBufCodecs.VAR_INT, GridPayload::halfExtent,
				ByteBufCodecs.FLOAT, GridPayload::step,
				ByteBufCodecs.BOOL, GridPayload::reset,
				ByteBufCodecs.BYTE_ARRAY, GridPayload::nodes,
				GridPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return GRID;
		}
	}

	/**
	 * Band thresholds, cloud height and render limits, serialised by
	 * {@link com.fand1l.vibeweather.api.ClientWeatherParams}. Sent on join and on reload.
	 */
	public record ParamsPayload(byte[] params) implements CustomPacketPayload {
		public static final StreamCodec<ByteBuf, ParamsPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.BYTE_ARRAY, ParamsPayload::params,
				ParamsPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return PARAMS;
		}
	}

	/**
	 * Registers both types on the clientbound play channel.
	 *
	 * <p>Must run on both sides during initialisation and before any receiver is registered, which
	 * is why it lives in the common initialiser rather than the client one.
	 */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(GRID, GridPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PARAMS, ParamsPayload.CODEC);
	}

	/** Convenience for logging and commands. */
	public static Identifier gridChannel() {
		return GRID.id();
	}
}
