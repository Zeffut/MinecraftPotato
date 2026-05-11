package com.potatomc.lighting.storage;

public final class MortonIndex {

    private MortonIndex() {}

    private static int splitBy3(int v) {
        v = (v | (v << 8)) & 0x0300F00F;
        v = (v | (v << 4)) & 0x030C30C3;
        v = (v | (v << 2)) & 0x09249249;
        return v;
    }

    private static int compactBy3(int v) {
        v &= 0x09249249;
        v = (v | (v >> 2)) & 0x030C30C3;
        v = (v | (v >> 4)) & 0x0300F00F;
        v = (v | (v >> 8)) & 0xFF0000FF;
        v = (v | (v >> 16)) & 0x000003FF;
        return v;
    }

    public static int encode(int x, int y, int z) {
        return splitBy3(x) | (splitBy3(y) << 1) | (splitBy3(z) << 2);
    }

    public static int decodeX(int idx) { return compactBy3(idx); }
    public static int decodeY(int idx) { return compactBy3(idx >> 1); }
    public static int decodeZ(int idx) { return compactBy3(idx >> 2); }
}
