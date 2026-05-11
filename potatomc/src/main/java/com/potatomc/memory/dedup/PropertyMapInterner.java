package com.potatomc.memory.dedup;

import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.state.property.Property;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * FerriteCore-style interner for the per-{@code BlockState} property map.
 *
 * <p>Weak-key intern table: canonical entries can be GC'd when no live
 * {@code BlockState} references them. This avoids the unbounded growth seen
 * with the previous strong-ref ConcurrentHashMap implementation.</p>
 */
public final class PropertyMapInterner {

    // Weak-key map so canonical entries can be GC'd when no BlockState references them.
    private static final Map<Map<Property<?>, Comparable<?>>, Map<Property<?>, Comparable<?>>> CANON =
        Collections.synchronizedMap(new WeakHashMap<>());

    private static final LongAdder lookups = new LongAdder();
    private static final LongAdder hits = new LongAdder();

    private PropertyMapInterner() {}

    @SuppressWarnings("unchecked")
    public static <T extends Map<Property<?>, Comparable<?>>> T intern(T input) {
        if (input == null || input.isEmpty()) return input;
        lookups.increment();
        Map<Property<?>, Comparable<?>> immutable =
            (input instanceof ImmutableMap<?, ?>) ? input : ImmutableMap.copyOf(input);
        synchronized (CANON) {
            Map<Property<?>, Comparable<?>> existing = CANON.get(immutable);
            if (existing != null) {
                hits.increment();
                return (T) existing;
            }
            CANON.put(immutable, immutable);
            return (T) immutable;
        }
    }

    /**
     * Specialized overload for State's actual field type. Same semantics as
     * {@link #intern(Map)} but preserves the {@code Reference2ObjectArrayMap}
     * static type so the mixin can store the result directly into the field
     * without a cast.
     */
    @SuppressWarnings("unchecked")
    public static Reference2ObjectArrayMap<Property<?>, Comparable<?>> internRefMap(
            Reference2ObjectArrayMap<Property<?>, Comparable<?>> input) {
        if (input == null || input.isEmpty()) return input;
        lookups.increment();
        // Note: for the ref-map overload we keep the input as the canonical key
        // (cannot return an ImmutableMap given the static type constraint).
        synchronized (CANON) {
            Map<Property<?>, Comparable<?>> existing = CANON.get(input);
            if (existing instanceof Reference2ObjectArrayMap<?, ?>) {
                hits.increment();
                return (Reference2ObjectArrayMap<Property<?>, Comparable<?>>) existing;
            }
            CANON.put(input, input);
            return input;
        }
    }

    public static long internedCount() { synchronized (CANON) { return CANON.size(); } }
    public static long lookupsCount() { return lookups.sum(); }
    public static long hitsCount() { return hits.sum(); }
    public static double hitRate() {
        long l = lookups.sum();
        return l == 0 ? 0.0 : (double) hits.sum() / l;
    }

    /**
     * Conservative estimate: every lookup beyond the canonical-entry count
     * was a hit that avoided allocating a {@code Reference2ObjectArrayMap}
     * (header + entry array). 64 bytes is a floor for a 2-entry map.
     */
    public static long estimatedBytesSaved() {
        return Math.max(0L, lookups.sum() - internedCount()) * 64L;
    }
}
