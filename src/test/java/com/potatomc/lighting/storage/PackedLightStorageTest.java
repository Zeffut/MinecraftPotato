package com.potatomc.lighting.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackedLightStorageTest {

    @Test
    void newStorageIsZero() {
        PackedLightStorage s = new PackedLightStorage();
        for (int i = 0; i < 4096; i++) {
            assertEquals(0, s.get(i));
        }
    }

    @Test
    void setAndGetSingle() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(0, 15);
        assertEquals(15, s.get(0));
    }

    @Test
    void setAndGetIndependent() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(0, 15);
        s.set(1, 7);
        s.set(2, 3);
        assertEquals(15, s.get(0));
        assertEquals(7, s.get(1));
        assertEquals(3, s.get(2));
    }

    @Test
    void setMasksTo4Bits() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(10, 0xFF);
        assertEquals(15, s.get(10));
    }

    @Test
    void overwriteResetsValue() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(100, 15);
        s.set(100, 4);
        assertEquals(4, s.get(100));
    }

    @Test
    void boundaryIndices() {
        PackedLightStorage s = new PackedLightStorage();
        s.set(0, 1);
        s.set(4095, 14);
        assertEquals(1, s.get(0));
        assertEquals(14, s.get(4095));
    }

    @Test
    void fillEvery16thWriteIsolated() {
        PackedLightStorage s = new PackedLightStorage();
        for (int i = 0; i < 4096; i += 16) s.set(i, 5);
        for (int i = 0; i < 4096; i++) {
            assertEquals(i % 16 == 0 ? 5 : 0, s.get(i));
        }
    }
}
