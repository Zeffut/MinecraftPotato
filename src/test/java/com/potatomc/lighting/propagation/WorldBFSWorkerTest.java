package com.potatomc.lighting.propagation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldBFSWorkerTest {

    /** Stub SectionAccess backed by hash maps. */
    private static final class MapAccess implements WorldBFSWorker.SectionAccess {
        final Map<Long, Integer> light = new HashMap<>();
        final Set<Long> opaque = new HashSet<>();

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFFFL) << 40
                    | ((long) y & 0xFFFL) << 28
                    | ((long) z & 0xFFFFFFL) << 4;
        }

        @Override public boolean isOpaque(int x, int y, int z) { return opaque.contains(key(x, y, z)); }
        @Override public int getLight(int x, int y, int z) { return light.getOrDefault(key(x, y, z), 0); }
        @Override public void setLight(int x, int y, int z, int level) { light.put(key(x, y, z), level); }

        void makeOpaque(int x, int y, int z) { opaque.add(key(x, y, z)); }
    }

    @Test
    void singleEmitterFalloffPerStep() {
        MapAccess a = new MapAccess();
        WorldBFSWorker w = new WorldBFSWorker();
        w.seed(a, 0, 0, 0, 15);
        w.propagate(a);

        assertEquals(15, a.getLight(0, 0, 0));
        assertEquals(14, a.getLight(1, 0, 0));
        assertEquals(13, a.getLight(2, 0, 0));
        assertEquals(7, a.getLight(8, 0, 0));
        assertEquals(0, a.getLight(15, 0, 0));
        assertEquals(0, a.getLight(16, 0, 0));
    }

    @Test
    void crossesSectionBoundary() {
        // emitter sits at world x=15 (section 0). x=16 is in section 1.
        MapAccess a = new MapAccess();
        WorldBFSWorker w = new WorldBFSWorker();
        w.seed(a, 15, 0, 0, 15);
        w.propagate(a);

        assertEquals(15, a.getLight(15, 0, 0));
        assertEquals(14, a.getLight(16, 0, 0));
        assertEquals(13, a.getLight(17, 0, 0));
        assertEquals(1, a.getLight(29, 0, 0));
    }

    @Test
    void opaqueWallForcesDetour() {
        MapAccess a = new MapAccess();
        a.makeOpaque(5, 0, 0);
        WorldBFSWorker w = new WorldBFSWorker();
        w.seed(a, 0, 0, 0, 15);
        w.propagate(a);

        assertEquals(0, a.getLight(5, 0, 0)); // opaque, never lit
        // (6,0,0) reachable via detour: 0→(0,1,0)→…→(6,1,0)→(6,0,0), Manhattan 8 → level 7
        assertEquals(7, a.getLight(6, 0, 0));
        // (0,1,0) reached directly at level 14
        assertEquals(14, a.getLight(0, 1, 0));
    }

    @Test
    void zeroLevelSeedIsNoop() {
        MapAccess a = new MapAccess();
        WorldBFSWorker w = new WorldBFSWorker();
        w.seed(a, 0, 0, 0, 0);
        w.propagate(a);
        assertEquals(0, a.getLight(0, 0, 0));
        assertEquals(0, a.getLight(1, 0, 0));
    }

    @Test
    void packUnpackRoundtripPositive() {
        long p = WorldBFSWorker.pack(123, 64, -57, 12);
        assertEquals(123, WorldBFSWorker.unpackX(p));
        assertEquals(64, WorldBFSWorker.unpackY(p));
        assertEquals(-57, WorldBFSWorker.unpackZ(p));
        assertEquals(12, WorldBFSWorker.unpackLevel(p));
    }

    @Test
    void packUnpackNegativeY() {
        long p = WorldBFSWorker.pack(-1000, -64, 999_999, 5);
        assertEquals(-1000, WorldBFSWorker.unpackX(p));
        assertEquals(-64, WorldBFSWorker.unpackY(p));
        assertEquals(999_999, WorldBFSWorker.unpackZ(p));
        assertEquals(5, WorldBFSWorker.unpackLevel(p));
    }
}
