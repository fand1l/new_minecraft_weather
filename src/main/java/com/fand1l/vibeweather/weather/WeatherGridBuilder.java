package com.fand1l.vibeweather.weather;

import java.util.ArrayList;
import java.util.List;

import com.fand1l.vibeweather.api.WeatherRules;
import com.fand1l.vibeweather.api.WeatherSample;

/**
 * Builds the grid of weather samples sent to one player, and works out what changed since last time.
 *
 * <p>The grid is anchored to a <em>world</em> lattice, not to the player: node coordinates are
 * always multiples of the step. A player-relative grid would shift by a fraction of a step on every
 * move, so every node would change every tick and the whole grid would have to be resent. Anchored
 * to the world, walking simply exposes a new row while every other node keeps its value.
 *
 * <p>Free of Minecraft types, so the indexing and delta logic can be checked without a server.
 */
public final class WeatherGridBuilder {
	/**
	 * A snapshot of the grid.
	 *
	 * @param originNodeX lattice index of the lowest-x column, i.e. world x divided by the step
	 * @param originNodeZ lattice index of the lowest-z row
	 * @param halfExtent  nodes either side of the centre, so the side length is {@code 2n + 1}
	 * @param step        node spacing in blocks
	 * @param data        packed nodes, {@link GridCodec#NODE_BYTES} each, row-major in z then x
	 */
	public record Grid(int originNodeX, int originNodeZ, int halfExtent, double step, byte[] data) {
		public int side() {
			return 2 * halfExtent + 1;
		}

		public int nodeCount() {
			return side() * side();
		}

		/** Array index of a lattice node, or -1 when it lies outside this grid. */
		public int indexOf(int nodeX, int nodeZ) {
			int localX = nodeX - originNodeX;
			int localZ = nodeZ - originNodeZ;

			if (localX < 0 || localZ < 0 || localX >= side() || localZ >= side()) {
				return -1;
			}

			return localZ * side() + localX;
		}

		public int nodeXAt(int index) {
			return originNodeX + index % side();
		}

		public int nodeZAt(int index) {
			return originNodeZ + index / side();
		}

		public double worldX(int index) {
			return nodeXAt(index) * step;
		}

		public double worldZ(int index) {
			return nodeZAt(index) * step;
		}

		public WeatherSample sampleAt(int index, float altitudeFactor) {
			return GridCodec.unpack(data, index * GridCodec.NODE_BYTES, altitudeFactor);
		}
	}

	/** One node of a delta: where it sits in the new grid, and its packed bytes. */
	public record Change(int index, byte[] node) {
	}

	/**
	 * A delta against a previous grid.
	 *
	 * <p>{@code full} means the previous grid overlapped too little to be worth patching, so the
	 * whole grid is sent instead. That happens on join, on a dimension change, and after a teleport.
	 */
	public record Delta(Grid grid, List<Change> changes, boolean full) {
		public int byteLength() {
			return full
					? grid.data().length
					: changes.size() * (Short.BYTES + GridCodec.NODE_BYTES);
		}

		/**
		 * Packs the changes into one array: a repeated {@code [u16 index][8 node bytes]}.
		 *
		 * <p>Flattening here rather than in the payload keeps the network layer carrying nothing but
		 * a byte array and a few integers, so its stream codec is built entirely from
		 * {@code ByteBufCodecs} primitives instead of hand-written encode and decode lambdas.
		 */
		public byte[] changesToBytes() {
			byte[] out = new byte[changes.size() * (Short.BYTES + GridCodec.NODE_BYTES)];
			int offset = 0;

			for (Change change : changes) {
				out[offset] = (byte) (change.index() & 0xFF);
				out[offset + 1] = (byte) ((change.index() >>> 8) & 0xFF);
				System.arraycopy(change.node(), 0, out, offset + 2, GridCodec.NODE_BYTES);
				offset += Short.BYTES + GridCodec.NODE_BYTES;
			}

			return out;
		}
	}

	/**
	 * Splits a set of changes into wire-sized batches.
	 *
	 * <p>A full grid is around 24 KB, which is uncomfortably close to packet size limits, and the
	 * exact cap on the byte-array stream codec is not something this project has verified. Sending
	 * several small packets sidesteps the question entirely and costs one payload type instead of
	 * two: a full send is simply the first batch marked as a reset, followed by the rest.
	 *
	 * @param maxNodesPerBatch nodes per packet; each node costs ten bytes on the wire
	 */
	public static List<byte[]> batch(List<Change> changes, int maxNodesPerBatch) {
		if (changes.isEmpty()) {
			return List.of();
		}

		int perBatch = Math.max(1, maxNodesPerBatch);
		List<byte[]> batches = new ArrayList<>((changes.size() + perBatch - 1) / perBatch);

		for (int start = 0; start < changes.size(); start += perBatch) {
			int end = Math.min(start + perBatch, changes.size());
			batches.add(new Delta(null, changes.subList(start, end), false).changesToBytes());
		}

		return batches;
	}

	/** Every node of a grid as changes, for a full send. */
	public static List<Change> allNodes(Grid grid) {
		List<Change> changes = new ArrayList<>(grid.nodeCount());

		for (int index = 0; index < grid.nodeCount(); index++) {
			byte[] node = new byte[GridCodec.NODE_BYTES];
			System.arraycopy(grid.data(), index * GridCodec.NODE_BYTES, node, 0, GridCodec.NODE_BYTES);
			changes.add(new Change(index, node));
		}

		return changes;
	}

	/** Inverse of {@link Delta#changesToBytes()}. Rejects a malformed length rather than half-reading. */
	public static List<Change> changesFromBytes(byte[] packed) {
		int stride = Short.BYTES + GridCodec.NODE_BYTES;

		if (packed == null || packed.length % stride != 0) {
			return List.of();
		}

		List<Change> changes = new ArrayList<>(packed.length / stride);

		for (int offset = 0; offset < packed.length; offset += stride) {
			int index = (packed[offset] & 0xFF) | ((packed[offset + 1] & 0xFF) << 8);
			byte[] node = new byte[GridCodec.NODE_BYTES];
			System.arraycopy(packed, offset + 2, node, 0, GridCodec.NODE_BYTES);
			changes.add(new Change(index, node));
		}

		return changes;
	}

	/**
	 * A grid index does not fit a {@code u16} beyond this side length, which is what caps the
	 * configurable extent.
	 */
	public static int maxHalfExtent() {
		return (int) ((Math.sqrt(65536.0) - 1.0) / 2.0);
	}

	private WeatherGridBuilder() {
	}

	/** Lattice index containing a world coordinate. */
	public static int toNode(double world, double step) {
		return (int) Math.floor(world / step);
	}

	/** Samples every node of the grid centred on a position. */
	public static Grid build(
			List<WeatherZone> zones,
			double centerX,
			double centerZ,
			int halfExtent,
			double step,
			float cloudBottom,
			float cloudTop,
			WeatherRules rules
	) {
		int originX = toNode(centerX, step) - halfExtent;
		int originZ = toNode(centerZ, step) - halfExtent;
		int side = 2 * halfExtent + 1;
		byte[] data = new byte[GridCodec.byteLength(side * side)];

		for (int localZ = 0; localZ < side; localZ++) {
			for (int localX = 0; localX < side; localX++) {
				double worldX = (originX + localX) * step;
				double worldZ = (originZ + localZ) * step;

				// Sampled at the cloud base rather than at the player's feet, so the grid describes
				// the weather itself. The vertical falloff is applied client-side from the viewer's
				// own height, which is why the altitude factor is not stored per node.
				WeatherSample sample = ZoneBlender.sample(
						zones, worldX, cloudBottom, worldZ, cloudBottom, cloudTop, rules);
				GridCodec.pack(sample, data, (localZ * side + localX) * GridCodec.NODE_BYTES);
			}
		}

		return new Grid(originX, originZ, halfExtent, step, data);
	}

	/**
	 * Works out what to send, given what the client already has.
	 *
	 * <p>Falls back to a full send once the patch would cost more than {@code fullSendFraction} of
	 * the grid. Below that a sparse index list is far smaller; above it, the indices are pure
	 * overhead on top of data being sent anyway.
	 */
	public static Delta diff(Grid previous, Grid current, float fullSendFraction) {
		if (previous == null
				|| previous.halfExtent() != current.halfExtent()
				|| previous.step() != current.step()) {
			return new Delta(current, List.of(), true);
		}

		List<Change> changes = new ArrayList<>();
		int limit = (int) (current.nodeCount() * fullSendFraction);

		for (int index = 0; index < current.nodeCount(); index++) {
			int previousIndex = previous.indexOf(current.nodeXAt(index), current.nodeZAt(index));
			int offset = index * GridCodec.NODE_BYTES;

			boolean changed = previousIndex < 0;

			if (!changed) {
				int previousOffset = previousIndex * GridCodec.NODE_BYTES;

				for (int b = 0; b < GridCodec.NODE_BYTES; b++) {
					if (previous.data()[previousOffset + b] != current.data()[offset + b]) {
						changed = true;
						break;
					}
				}
			}

			if (changed) {
				byte[] node = new byte[GridCodec.NODE_BYTES];
				System.arraycopy(current.data(), offset, node, 0, GridCodec.NODE_BYTES);
				changes.add(new Change(index, node));

				if (changes.size() > limit) {
					return new Delta(current, List.of(), true);
				}
			}
		}

		return new Delta(current, changes, false);
	}

	/** Applies a delta to a client-side copy, producing the grid the server built. */
	public static Grid apply(Grid previous, Delta delta) {
		if (delta.full() || previous == null) {
			return delta.grid();
		}

		Grid target = delta.grid();
		byte[] data = new byte[target.data().length];

		// Start from whatever the previous grid already knows about each node, then overwrite the
		// nodes the delta carries. Nodes with no previous value and no change cannot occur: the
		// diff marks every newly exposed node as changed.
		for (int index = 0; index < target.nodeCount(); index++) {
			int previousIndex = previous.indexOf(target.nodeXAt(index), target.nodeZAt(index));

			if (previousIndex >= 0) {
				System.arraycopy(previous.data(), previousIndex * GridCodec.NODE_BYTES,
						data, index * GridCodec.NODE_BYTES, GridCodec.NODE_BYTES);
			}
		}

		for (Change change : delta.changes()) {
			System.arraycopy(change.node(), 0, data, change.index() * GridCodec.NODE_BYTES,
					GridCodec.NODE_BYTES);
		}

		return new Grid(target.originNodeX(), target.originNodeZ(), target.halfExtent(),
				target.step(), data);
	}
}
