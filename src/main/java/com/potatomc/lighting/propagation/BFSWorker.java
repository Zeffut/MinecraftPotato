package com.potatomc.lighting.propagation;

import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;

/**
 * Section-local flood-fill propagation. One instance per thread; reused
 * across calls via reset().
 */
public final class BFSWorker {

    private static final int LEVEL_BITS = 4;
    private static final int LEVEL_MASK = 0xF;

    private final PooledQueue queue = new PooledQueue(16384);

    public void seed(int mortonIdx, int level) {
        if (level <= 0) return;
        queue.enqueue((mortonIdx << LEVEL_BITS) | (level & LEVEL_MASK));
    }

    public void propagate(PackedLightStorage storage, boolean[] opaque) {
        while (!queue.isEmpty()) {
            int packed = queue.dequeue();
            int idx = packed >>> LEVEL_BITS;
            int level = packed & LEVEL_MASK;

            if (opaque[idx]) continue;
            if (storage.get(idx) >= level) continue;
            storage.set(idx, level);

            if (level <= 1) continue;
            int nextLevel = level - 1;

            int x = MortonIndex.decodeX(idx);
            int y = MortonIndex.decodeY(idx);
            int z = MortonIndex.decodeZ(idx);

            if (x > 0)  enqueueNeighbor(MortonIndex.encode(x - 1, y, z), nextLevel, storage);
            if (x < 15) enqueueNeighbor(MortonIndex.encode(x + 1, y, z), nextLevel, storage);
            if (y > 0)  enqueueNeighbor(MortonIndex.encode(x, y - 1, z), nextLevel, storage);
            if (y < 15) enqueueNeighbor(MortonIndex.encode(x, y + 1, z), nextLevel, storage);
            if (z > 0)  enqueueNeighbor(MortonIndex.encode(x, y, z - 1), nextLevel, storage);
            if (z < 15) enqueueNeighbor(MortonIndex.encode(x, y, z + 1), nextLevel, storage);
        }
        queue.reset();
    }

    private void enqueueNeighbor(int idx, int level, PackedLightStorage storage) {
        if (storage.get(idx) >= level) return;
        queue.enqueue((idx << LEVEL_BITS) | level);
    }
}
