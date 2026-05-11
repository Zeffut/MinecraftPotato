package com.potatomc.mixin;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.bridge.EngineHolder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightingProvider.class)
public abstract class LightingProviderMixin {

    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;I)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void potatomc$getLight(BlockPos pos, int ambientDarkness, CallbackInfoReturnable<Integer> cir) {
        if (!CompatGuard.isActive()) return;
        PotatoLightEngine engine = EngineHolder.get();
        if (engine == null) return;
        int block = engine.getLightLevel(pos, LightLevelAPI.LightType.BLOCK);
        int sky = engine.getLightLevel(pos, LightLevelAPI.LightType.SKY) - ambientDarkness;
        cir.setReturnValue(Math.max(block, Math.max(0, sky)));
    }
}
