package com.potatomc.mixin.entity;

import com.potatomc.entity.DistantEntityTracker;
import com.potatomc.entity.PotatoEntityTick;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips {@code mobTick(ServerWorld)} — the per-tick AI entry point that runs
 * pathfinding, goal selection, targeting and look control — when no player is
 * within {@link DistantEntityTracker#SKIP_DISTANCE} blocks of the mob.
 *
 * <p>The cheap physics tick on {@code Entity#tick()} keeps running so gravity,
 * fluid drag, on-fire / drowning damage and age progression are unaffected.
 * Only the expensive AI computations are skipped.</p>
 */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin {

    @Inject(method = "mobTick(Lnet/minecraft/server/world/ServerWorld;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void potatomc$skipDistantAITick(ServerWorld world, CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;
        boolean skip = DistantEntityTracker.shouldSkipAITick(self);
        PotatoEntityTick.recordTick(skip);
        if (skip) ci.cancel();
    }
}
