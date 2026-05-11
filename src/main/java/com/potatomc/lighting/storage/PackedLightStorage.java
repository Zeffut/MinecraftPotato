package com.potatomc.lighting.storage;

/**
 * 4 bits per block × 4096 blocks per section = 256 longs.
 * Indexed by Morton-encoded position (use {@link MortonIndex}).
 */
public final class PackedLightStorage {

    public static final int SECTION_SIZE = 4096;
    private static final int LONGS = 256;
    private static final int BITS_PER_VALUE = 4;
    private static final int VALUES_PER_LONG = 16;
    private static final long MASK = 0xFL;

    private final long[] data = new long[LONGS];

    public int get(int index) {
        int longIdx = index >>> 4;
        int bitOffset = (index & 0xF) << 2;
        return (int) ((data[longIdx] >>> bitOffset) & MASK);
    }

    public void set(int index, int value) {
        int longIdx = index >>> 4;
        int bitOffset = (index & 0xF) << 2;
        long cleared = data[longIdx] & ~(MASK << bitOffset);
        data[longIdx] = cleared | ((value & MASK) << bitOffset);
    }

    public void fill(int value) {
        long v = value & MASK;
        long packed = 0L;
        for (int i = 0; i < VALUES_PER_LONG; i++) packed |= v << (i * BITS_PER_VALUE);
        java.util.Arrays.fill(data, packed);
    }

    public long[] rawData() { return data; }
}
