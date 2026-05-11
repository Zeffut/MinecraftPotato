package com.potatomc.lighting.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MortonIndexTest {

    @Test
    void encodeOrigin() {
        assertEquals(0, MortonIndex.encode(0, 0, 0));
    }

    @Test
    void encodeUnitsInterleave() {
        assertEquals(0b001, MortonIndex.encode(1, 0, 0));
        assertEquals(0b010, MortonIndex.encode(0, 1, 0));
        assertEquals(0b100, MortonIndex.encode(0, 0, 1));
        assertEquals(0b111, MortonIndex.encode(1, 1, 1));
    }

    @Test
    void encodeMaxFitsIn12Bits() {
        int idx = MortonIndex.encode(15, 15, 15);
        assertTrue(idx >= 0 && idx < 4096, "idx=" + idx);
    }

    @Test
    void roundTripAllPositions() {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int idx = MortonIndex.encode(x, y, z);
                    assertEquals(x, MortonIndex.decodeX(idx), "x");
                    assertEquals(y, MortonIndex.decodeY(idx), "y");
                    assertEquals(z, MortonIndex.decodeZ(idx), "z");
                }
            }
        }
    }

    @Test
    void uniquenessAcrossSection() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    assertTrue(seen.add(MortonIndex.encode(x, y, z)));
        assertEquals(4096, seen.size());
    }
}
