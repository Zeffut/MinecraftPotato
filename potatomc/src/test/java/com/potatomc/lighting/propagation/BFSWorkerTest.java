package com.potatomc.lighting.propagation;

import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BFSWorkerTest {

    private static int idx(int x, int y, int z) { return MortonIndex.encode(x, y, z); }

    @Test
    void singleSourcePropagatesFalloffByOnePerStep() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        w.seed(s, opaque, idx(8, 8, 8), 15);
        w.propagate(s, opaque);

        assertEquals(15, s.get(idx(8, 8, 8)));
        assertEquals(14, s.get(idx(9, 8, 8)));
        assertEquals(13, s.get(idx(10, 8, 8)));
        assertEquals(13, s.get(idx(8, 9, 9)));
        assertEquals(0, s.get(idx(0, 0, 0)));
    }

    @Test
    void opaqueBlocksLight() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        opaque[idx(9, 8, 8)] = true;

        w.seed(s, opaque, idx(8, 8, 8), 15);
        w.propagate(s, opaque);

        assertEquals(15, s.get(idx(8, 8, 8)));
        assertEquals(0, s.get(idx(9, 8, 8)));
        // (10,8,8) reachable by routing around the single-block wall (distance 4 via y/z detour → level 11)
        assertEquals(11, s.get(idx(10, 8, 8)));
        assertEquals(14, s.get(idx(8, 9, 8)));
    }

    @Test
    void earlyExitWhenExistingLevelHigher() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(idx(8, 8, 8), 12);
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];

        w.seed(s, opaque, idx(8, 8, 8), 5);
        w.propagate(s, opaque);

        assertEquals(12, s.get(idx(8, 8, 8)));
        assertEquals(0, s.get(idx(9, 8, 8)));
    }

    @Test
    void multipleSourcesTakeMax() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        w.seed(s, opaque, idx(0, 8, 8), 15);
        w.seed(s, opaque, idx(15, 8, 8), 15);
        w.propagate(s, opaque);

        assertEquals(8, s.get(idx(7, 8, 8)));
        assertEquals(8, s.get(idx(8, 8, 8)));
    }

    @Test
    void zeroLevelSeedIsNoop() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        w.seed(s, opaque, idx(8, 8, 8), 0);
        w.propagate(s, opaque);

        assertEquals(0, s.get(idx(8, 8, 8)));
    }

    @Test
    void opaqueEmitterStillEmits() {
        PackedLightStorage s = new PackedLightStorage();
        BFSWorker w = new BFSWorker();
        boolean[] opaque = new boolean[4096];
        opaque[idx(8, 8, 8)] = true; // emitter cell is opaque (like glowstone)

        w.seed(s, opaque, idx(8, 8, 8), 15);
        w.propagate(s, opaque);

        assertEquals(15, s.get(idx(8, 8, 8))); // emitter holds its own level
        assertEquals(14, s.get(idx(9, 8, 8))); // light escapes to non-opaque neighbor
        assertEquals(13, s.get(idx(10, 8, 8)));
    }
}
