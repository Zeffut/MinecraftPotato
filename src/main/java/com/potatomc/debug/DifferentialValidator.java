package com.potatomc.debug;

import com.potatomc.PotatoMC;
import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.bridge.EngineHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

public final class DifferentialValidator {

    public record Report(int totalBlocks, int diffCount, int maxDelta) {}

    private DifferentialValidator() {}

    public static Report runAround(ServerWorld world, Vec3d center, int radius) {
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int range = radius * 16;

        int total = 0;
        int diffs = 0;
        int maxDelta = 0;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    int ours = PotatoMC.LIGHT_ENGINE.getLightLevel(pos, LightLevelAPI.LightType.BLOCK);
                    int vanilla = readVanilla(world, pos);
                    total++;
                    int delta = Math.abs(ours - vanilla);
                    if (delta > 0) {
                        diffs++;
                        if (delta > maxDelta) maxDelta = delta;
                        if (delta > 1) {
                            PotatoMC.LOGGER.warn(
                                "[validator] diff>1 @ {} : potato={} vanilla={}",
                                pos, ours, vanilla);
                        }
                    }
                }
            }
        }
        return new Report(total, diffs, maxDelta);
    }

    private static int readVanilla(ServerWorld world, BlockPos pos) {
        int[] result = new int[1];
        EngineHolder.runBypassed(() ->
            result[0] = world.getLightLevel(LightType.BLOCK, pos)
        );
        return result[0];
    }
}
