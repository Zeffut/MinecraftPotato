package com.potatomc.mixin;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.bridge.EngineHolder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkLightProvider.class)
public abstract class ChunkLightProviderMixin {

    // getLightLevel(BlockPos) is declared on ChunkLightingView interface and inherited by
    // ChunkLightProvider concrete subclasses. method_15543, signature (Lnet/minecraft/util/math/BlockPos;)I.
    @Inject(method = "getLightLevel(Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void potatomc$getLightLevel(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!CompatGuard.isActive()) return;
        PotatoLightEngine engine = EngineHolder.get();
        if (engine == null) return;
        cir.setReturnValue(engine.getLightLevel(pos, LightLevelAPI.LightType.BLOCK));
    }
}
