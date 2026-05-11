package com.potatomc.lighting.api;

import net.minecraft.util.math.BlockPos;

public interface LightLevelAPI {

    enum LightType { SKY, BLOCK }

    /**
     * Returns the light level at {@code pos} for {@code type}, in [0, 15].
     * Returns 0 if the position's section is unloaded.
     */
    int getLightLevel(BlockPos pos, LightType type);
}
