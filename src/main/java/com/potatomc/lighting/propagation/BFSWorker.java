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

    /**
     * Seeds an emitter at the given Morton index with the given light level.
     * Writes the level directly to storage (regardless of opacity — the emitter
     * itself holds the emission), then enqueues non-opaque neighbors at level-1.
     * Call {@link #propagate} after seeding.
     */
    public void seed(PackedLightStorage storage, boolean[] opaque, int mortonIdx, int level) {
        if (level <= 0) return;
        if (storage.get(mortonIdx) >= level) return;
        storage.set(mortonIdx, level);
        if (level <= 1) return;
        int nextLevel = level - 1;
        int x = MortonIndex.decodeX(mortonIdx);
        int y = MortonIndex.decodeY(mortonIdx);
        int z = MortonIndex.decodeZ(mortonIdx);
        if (x > 0)  enqueue(MortonIndex.encode(x - 1, y, z), nextLevel, storage, opaque);
        if (x < 15) enqueue(MortonIndex.encode(x + 1, y, z), nextLevel, storage, opaque);
        if (y > 0)  enqueue(MortonIndex.encode(x, y - 1, z), nextLevel, storage, opaque);
        if (y < 15) enqueue(MortonIndex.encode(x, y + 1, z), nextLevel, storage, opaque);
        if (z > 0)  enqueue(MortonIndex.encode(x, y, z - 1), nextLevel, storage, opaque);
        if (z < 15) enqueue(MortonIndex.encode(x, y, z + 1), nextLevel, storage, opaque);
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

            if (x > 0)  enqueue(MortonIndex.encode(x - 1, y, z), nextLevel, storage, opaque);
            if (x < 15) enqueue(MortonIndex.encode(x + 1, y, z), nextLevel, storage, opaque);
            if (y > 0)  enqueue(MortonIndex.encode(x, y - 1, z), nextLevel, storage, opaque);
            if (y < 15) enqueue(MortonIndex.encode(x, y + 1, z), nextLevel, storage, opaque);
            if (z > 0)  enqueue(MortonIndex.encode(x, y, z - 1), nextLevel, storage, opaque);
            if (z < 15) enqueue(MortonIndex.encode(x, y, z + 1), nextLevel, storage, opaque);
        }
        queue.reset();
    }

    private void enqueue(int idx, int level, PackedLightStorage storage, boolean[] opaque) {
        if (opaque[idx]) return;
        if (storage.get(idx) >= level) return;
        queue.enqueue((idx << LEVEL_BITS) | level);
    }
}
