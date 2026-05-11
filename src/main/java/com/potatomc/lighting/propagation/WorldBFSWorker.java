package com.potatomc.lighting.propagation;

/**
 * World-space (cross-section) block-light BFS. Unlike {@link BFSWorker} which
 * operates on Morton indices within a single 16×16×16 section, this worker
 * uses absolute world coordinates so that light naturally propagates across
 * section boundaries.
 *
 * <p>Storage and opacity are decoupled via {@link SectionAccess} so the
 * worker can be unit-tested independently of the engine.
 *
 * <p>Queue tokens pack (x, y, z, level) into a single {@code long}:
 * <pre>
 *   bits 63..40 : x (24 bits, signed two's-complement)
 *   bits 39..28 : y (12 bits, signed two's-complement)
 *   bits 27..04 : z (24 bits, signed two's-complement)
 *   bits 03..00 : level (4 bits, unsigned)
 * </pre>
 * 12 signed bits give a y range of [-2048, 2047], comfortably covering vanilla
 * world heights. 24 signed bits give an x/z range of ±8 388 608, comfortably
 * covering vanilla world borders.
 */
public final class WorldBFSWorker {

    public interface SectionAccess {
        /** Returns true if the block at the given world position is an opaque full cube. */
        boolean isOpaque(int x, int y, int z);

        /** Returns the currently-stored block light at the world position, or 0 if unknown. */
        int getLight(int x, int y, int z);

        /** Writes the given block-light level at the world position. */
        void setLight(int x, int y, int z, int level);
    }

    private static final long X_MASK = 0xFFFFFFL;     // 24 bits
    private static final long Y_MASK = 0xFFFL;        // 12 bits
    private static final long Z_MASK = 0xFFFFFFL;     // 24 bits
    private static final long LEVEL_MASK = 0xFL;      // 4 bits

    private static final int X_SHIFT = 40;
    private static final int Y_SHIFT = 28;
    private static final int Z_SHIFT = 4;

    private final PooledLongQueue queue = new PooledLongQueue(16384);

    static long pack(int x, int y, int z, int level) {
        return ((((long) x) & X_MASK) << X_SHIFT)
                | ((((long) y) & Y_MASK) << Y_SHIFT)
                | ((((long) z) & Z_MASK) << Z_SHIFT)
                | (((long) level) & LEVEL_MASK);
    }

    static int unpackX(long p) {
        // sign-extend from 24 bits
        long raw = (p >>> X_SHIFT) & X_MASK;
        if ((raw & 0x800000L) != 0) raw |= ~X_MASK;
        return (int) raw;
    }

    static int unpackY(long p) {
        long raw = (p >>> Y_SHIFT) & Y_MASK;
        if ((raw & 0x800L) != 0) raw |= ~Y_MASK;
        return (int) raw;
    }

    static int unpackZ(long p) {
        long raw = (p >>> Z_SHIFT) & Z_MASK;
        if ((raw & 0x800000L) != 0) raw |= ~Z_MASK;
        return (int) raw;
    }

    static int unpackLevel(long p) {
        return (int) (p & LEVEL_MASK);
    }

    /**
     * Seeds an emitter at the world position with the given level. The emitter
     * cell is forced to the seed level regardless of opacity (emitters like
     * glowstone are themselves opaque), and its non-opaque neighbours are
     * enqueued at level-1.
     */
    public void seed(SectionAccess access, int x, int y, int z, int level) {
        if (level <= 0) return;
        if (access.getLight(x, y, z) >= level) return;
        access.setLight(x, y, z, level);
        if (level <= 1) return;
        int next = level - 1;
        enqueueNeighbor(access, x - 1, y, z, next);
        enqueueNeighbor(access, x + 1, y, z, next);
        enqueueNeighbor(access, x, y - 1, z, next);
        enqueueNeighbor(access, x, y + 1, z, next);
        enqueueNeighbor(access, x, y, z - 1, next);
        enqueueNeighbor(access, x, y, z + 1, next);
    }

    public void propagate(SectionAccess access) {
        while (!queue.isEmpty()) {
            long packed = queue.dequeue();
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            int level = unpackLevel(packed);

            if (access.isOpaque(x, y, z)) continue;
            if (access.getLight(x, y, z) >= level) continue;
            access.setLight(x, y, z, level);

            if (level <= 1) continue;
            int next = level - 1;
            enqueueNeighbor(access, x - 1, y, z, next);
            enqueueNeighbor(access, x + 1, y, z, next);
            enqueueNeighbor(access, x, y - 1, z, next);
            enqueueNeighbor(access, x, y + 1, z, next);
            enqueueNeighbor(access, x, y, z - 1, next);
            enqueueNeighbor(access, x, y, z + 1, next);
        }
        queue.reset();
    }

    private void enqueueNeighbor(SectionAccess access, int x, int y, int z, int level) {
        if (access.isOpaque(x, y, z)) return;
        if (access.getLight(x, y, z) >= level) return;
        queue.enqueue(pack(x, y, z, level));
    }
}
