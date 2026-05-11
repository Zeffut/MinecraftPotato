package com.potatomc.memory.dedup;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Dedicated intern table for NBT compound keys.
 *
 * <p>NBT keys are short, repeated strings ("Pos", "Motion", "Health", ...)
 * that show up millions of times across entities and chunk persistence.
 * A scoped intern map (vs. {@link String#intern()}) avoids polluting the
 * VM string table and keeps lookup costs predictable.</p>
 *
 * <p>Long strings (&gt; 64 chars) are skipped — they are almost certainly
 * content payloads (player names, formatted text) rather than schema keys,
 * and interning them costs more than it saves.</p>
 */
public final class NbtKeyInterner {
    private static final ConcurrentHashMap<String, String> CANON = new ConcurrentHashMap<>();
    private static final LongAdder lookups = new LongAdder();
    private static final LongAdder hits = new LongAdder();

    private NbtKeyInterner() {}

    public static String intern(String key) {
        if (key == null || key.length() > 64) return key;
        lookups.increment();
        String existing = CANON.putIfAbsent(key, key);
        if (existing != null) {
            hits.increment();
            return existing;
        }
        return key;
    }

    public static long lookupsCount() { return lookups.sum(); }
    public static long hitsCount() { return hits.sum(); }
    public static long internedCount() { return CANON.size(); }
    /**
     * Each hit avoids one duplicate String header (~40 bytes) + average payload.
     * Conservative floor: 24 bytes/hit. Real savings depend on key length.
     */
    public static long estimatedBytesSaved() {
        return hits.sum() * 24L;
    }
}
