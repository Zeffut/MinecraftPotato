package com.potatomc.memory.dedup;

import java.util.concurrent.atomic.LongAdder;

/**
 * Canonical references for uniform 2048-byte light nibble arrays.
 *
 * <p>Most freshly-loaded sections have either all-zero block-light (everything
 * dark) or all-fifteen sky-light (open sky). Replacing the per-section
 * {@code byte[2048]} with a shared canonical reference saves 2 KB per
 * deduplicated section.</p>
 *
 * <p><strong>Safety caveat:</strong> the canonical arrays {@link #ALL_ZERO}
 * and {@link #ALL_FIFTEEN} are mutable {@code byte[]} instances and are
 * shared by reference. Vanilla {@code ChunkNibbleArray} mutates its internal
 * {@code bytes} field in-place via {@code set(int,int,int,int)} and
 * {@code clear(int)} (both call {@code asByteArray()} which returns the
 * field directly). If a section interned to a canonical array is later
 * mutated, the mutation will be visible to <em>every</em> other section
 * sharing the same canonical reference. This optimization is therefore
 * gated behind {@code -Dpotatomc.memory.nibble.enabled=true} and is
 * disabled by default. Enable only on read-only or short-lived snapshots
 * (e.g. light data shipped to clients) until a copy-on-write strategy lands.</p>
 */
public final class NibbleArrayInterner {
    public static final byte[] ALL_ZERO = new byte[2048];
    public static final byte[] ALL_FIFTEEN = new byte[2048];
    static {
        for (int i = 0; i < 2048; i++) ALL_FIFTEEN[i] = (byte) 0xFF;
    }

    private static final LongAdder lookups = new LongAdder();
    private static final LongAdder zeroHits = new LongAdder();
    private static final LongAdder fifteenHits = new LongAdder();

    private NibbleArrayInterner() {}

    public static byte[] intern(byte[] input) {
        if (input == null || input.length != 2048) return input;
        lookups.increment();
        byte first = input[0];
        for (int i = 1; i < 2048; i++) {
            if (input[i] != first) return input; // not uniform
        }
        if (first == 0) { zeroHits.increment(); return ALL_ZERO; }
        if (first == (byte) 0xFF) { fifteenHits.increment(); return ALL_FIFTEEN; }
        return input; // uniform but not 0 or 15, rare
    }

    public static long lookupsCount() { return lookups.sum(); }
    public static long zeroHitsCount() { return zeroHits.sum(); }
    public static long fifteenHitsCount() { return fifteenHits.sum(); }
    public static long estimatedBytesSaved() {
        return (zeroHits.sum() + fifteenHits.sum()) * 2048L;
    }
}
