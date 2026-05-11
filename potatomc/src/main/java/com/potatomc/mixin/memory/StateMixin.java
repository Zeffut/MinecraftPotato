package com.potatomc.mixin.memory;

import com.mojang.serialization.MapCodec;
import com.potatomc.memory.dedup.PropertyMapInterner;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.state.State;
import net.minecraft.state.property.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Intercepts {@link State#State(Object, Reference2ObjectArrayMap, MapCodec)}
 * and replaces the {@code propertyMap} constructor argument with a canonical
 * shared instance returned by {@link PropertyMapInterner#internRefMap}.
 *
 * <p>The field stored on {@code State} keeps its declared
 * {@code Reference2ObjectArrayMap} static type — we never substitute an
 * {@code ImmutableMap} or anything else that would violate the field
 * contract — the interner only deduplicates by content equality.</p>
 */
@Mixin(State.class)
public abstract class StateMixin {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, require = 0)
    private static Reference2ObjectArrayMap<Property<?>, Comparable<?>> potatomc$internProperties(
            Reference2ObjectArrayMap<Property<?>, Comparable<?>> input) {
        if (!com.potatomc.memory.MemoryGuard.isActive()) return input;
        return PropertyMapInterner.internRefMap(input);
    }
}
