package com.potatomc.mixin.memory;

import com.potatomc.memory.MemoryGuard;
import com.potatomc.memory.dedup.NbtKeyInterner;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Interns short string keys passed to {@link NbtCompound#put} and the
 * primitive {@code putXxx} variants.
 *
 * <p>Note: the {@code putXxx} overloads write directly to the backing map
 * (they don't delegate to {@code put}), so each must be intercepted
 * separately. Deserialization keys (read in {@code NbtCompound$1.readCompound})
 * are <em>not</em> covered here — that path is a private static method on an
 * anonymous inner class and is too fragile to target without yarn-name
 * verification. Programmatic puts (entity NBT writes, save serialization,
 * SNBT parsing) are still a large source of dup keys and are covered.</p>
 */
@Mixin(NbtCompound.class)
public abstract class NbtCompoundMixin {

    @ModifyVariable(method = "put(Ljava/lang/String;Lnet/minecraft/nbt/NbtElement;)Lnet/minecraft/nbt/NbtElement;",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKey(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putByte(Ljava/lang/String;B)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyB(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putShort(Ljava/lang/String;S)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyS(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putInt(Ljava/lang/String;I)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyI(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putLong(Ljava/lang/String;J)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyL(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putFloat(Ljava/lang/String;F)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyF(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putDouble(Ljava/lang/String;D)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyD(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putString(Ljava/lang/String;Ljava/lang/String;)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyStr(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putByteArray(Ljava/lang/String;[B)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyBA(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putIntArray(Ljava/lang/String;[I)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyIA(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putLongArray(Ljava/lang/String;[J)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyLA(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }

    @ModifyVariable(method = "putBoolean(Ljava/lang/String;Z)V",
                    at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String potatomc$internKeyBool(String key) {
        if (!MemoryGuard.isActive()) return key;
        return NbtKeyInterner.intern(key);
    }
}
