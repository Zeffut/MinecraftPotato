package com.potatomc.memory.dedup;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.state.property.Property;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Smoke tests for the interner. Real type-correct deduplication
 * (Property<?> / Comparable<?> keys) is exercised in-game via the State
 * mixin; here we only verify the boundary cases that can be checked
 * without spinning up the Minecraft registry.
 */
class PropertyMapInternerTest {

    @Test
    void emptyMapPassesThrough() {
        Reference2ObjectArrayMap<Property<?>, Comparable<?>> empty = new Reference2ObjectArrayMap<>();
        Map<Property<?>, Comparable<?>> result = PropertyMapInterner.intern(empty);
        assertSame(empty, result);
    }

    @Test
    void nullPassesThrough() {
        assertNull(PropertyMapInterner.intern(null));
    }

    @Test
    void countersStartNonNegative() {
        // After other tests have run the counters may be non-zero; just
        // verify the public API returns sane values.
        assert PropertyMapInterner.internedCount() >= 0;
        assert PropertyMapInterner.lookupsCount() >= 0;
        assert PropertyMapInterner.hitsCount() >= 0;
        assert PropertyMapInterner.hitRate() >= 0.0;
        assert PropertyMapInterner.estimatedBytesSaved() >= 0;
    }
}
