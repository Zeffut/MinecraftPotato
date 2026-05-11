package com.potatomc.mixin.memory;

import com.potatomc.memory.MemoryGuard;
import com.potatomc.memory.dedup.NibbleArrayInterner;
import net.minecraft.world.chunk.ChunkNibbleArray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Replaces a freshly-allocated 2048-byte uniform nibble array with a shared
 * canonical reference at {@link ChunkNibbleArray} construction time.
 *
 * <p>Opt-in only ({@link MemoryGuard#isNibbleActive()}). See safety note on
 * {@link NibbleArrayInterner}: vanilla mutates the {@code bytes} field
 * in-place, so sharing a canonical reference will corrupt all sibling
 * sections on the first {@code set(...)}/{@code clear(...)} call.</p>
 */
@Mixin(ChunkNibbleArray.class)
public abstract class ChunkNibbleArrayMixin {
    @ModifyVariable(method = "<init>([B)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private static byte[] potatomc$internNibbleArray(byte[] input) {
        if (!MemoryGuard.isNibbleActive()) return input;
        return NibbleArrayInterner.intern(input);
    }
}
