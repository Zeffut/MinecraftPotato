package com.potatomc.mixin;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import com.potatomc.lighting.bridge.EngineHolder;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {

    // Yarn 1.21.11 deviation from plan: WorldChunk's relevant setBlockState yarn signature
    // accepted by Mixin at runtime is (BlockPos, BlockState, int flags) -> BlockState
    // (method_12010, inherited from Chunk). The plan's `boolean moved` 3rd param was wrong.
    // The int-coord overload method_12256 (IIILBlockState;Z) is the actual implementation
    // called by World.setBlockState, but its post-tiny name was not "setBlockState" at runtime,
    // so we target the BlockPos overload which is what Mixin can resolve.
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Lnet/minecraft/block/BlockState;",
            at = @At("RETURN"), require = 0)
    private void potatomc$onSetBlockStatePos(
            BlockPos pos, BlockState state, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        notifyEngine(((WorldChunk) (Object) this), pos, state, cir.getReturnValue());
    }

    private static void notifyEngine(WorldChunk self, BlockPos pos, BlockState newState, BlockState returned) {
        if (!CompatGuard.isActive()) return;
        PotatoLightEngine engine = EngineHolder.get();
        if (engine == null) return;
        BlockState observed = newState != null ? newState : returned;
        if (observed == null) return;
        if (!(self.getWorld() instanceof ServerWorld serverWorld)) return;
        int emitted = observed.getLuminance();
        engine.onBlockChanged(serverWorld, pos, emitted);
    }
}
