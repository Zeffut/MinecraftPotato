package com.potatomc.memory.dedup;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.state.property.Property;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * FerriteCore-style interner for the per-{@code BlockState} property map.
 *
 * <p>Vanilla allocates a fresh {@code Reference2ObjectArrayMap<Property<?>,
 * Comparable<?>>} for every state in the registry. Many of those maps have
 * identical content (e.g. every directional block shares the same set of
 * facing values). We intern by content equality and return the canonical
 * instance, so the {@code State.propertyMap} field across thousands of
 * BlockStates points at a single shared {@code ImmutableMap}.</p>
 *
 * <p>Safe because {@link com.google.common.collect.ImmutableMap} is value-typed:
 * its {@code equals}/{@code hashCode} are content-based, and the canonical
 * instance is immutable so no caller can mutate a shared map by accident.</p>
 */
public final class PropertyMapInterner {

    private static final ConcurrentHashMap<Map<Property<?>, Comparable<?>>, Map<Property<?>, Comparable<?>>> CANON =
        new ConcurrentHashMap<>();
    private static final LongAdder lookups = new LongAdder();
    private static final LongAdder hits = new LongAdder();

    private PropertyMapInterner() {}

    /**
     * Returns a canonical, immutable copy of {@code input}. If an equivalent
     * map has been seen before, the previously stored instance is returned;
     * otherwise the freshly wrapped {@code ImmutableMap} is stored and
     * returned.
     *
     * <p>Empty or null inputs are passed through untouched — there is no
     * deduplication win on empty maps and avoiding the wrap keeps the hot
     * path branch-cheap.</p>
     */
    public static Map<Property<?>, Comparable<?>> intern(Map<Property<?>, Comparable<?>> input) {
        if (input == null || input.isEmpty()) return input;
        lookups.increment();
        Map<Property<?>, Comparable<?>> existing = CANON.putIfAbsent(input, input);
        if (existing != null) {
            hits.increment();
            return existing;
        }
        return input;
    }

    /**
     * Specialized overload for State's actual field type. Same semantics as
     * {@link #intern(Map)} but preserves the {@code Reference2ObjectArrayMap}
     * static type so the mixin can store the result directly into the field
     * without a cast.
     */
    public static Reference2ObjectArrayMap<Property<?>, Comparable<?>> internRefMap(
            Reference2ObjectArrayMap<Property<?>, Comparable<?>> input) {
        if (input == null || input.isEmpty()) return input;
        lookups.increment();
        Map<Property<?>, Comparable<?>> existing = CANON.putIfAbsent(input, input);
        if (existing != null) {
            hits.increment();
            return (Reference2ObjectArrayMap<Property<?>, Comparable<?>>) existing;
        }
        return input;
    }

    public static long internedCount() { return CANON.size(); }
    public static long lookupsCount() { return lookups.sum(); }
    public static long hitsCount() { return hits.sum(); }
    public static double hitRate() {
        long l = lookups.sum();
        return l == 0 ? 0.0 : (double) hits.sum() / l;
    }

    /**
     * Conservative estimate: every lookup beyond the canonical-entry count
     * was a hit that avoided allocating a {@code Reference2ObjectArrayMap}
     * (header + entry array). 64 bytes is a floor for a 2-entry map; real
     * savings on larger maps are higher.
     */
    public static long estimatedBytesSaved() {
        return Math.max(0L, lookups.sum() - CANON.size()) * 64L;
    }
}
