package com.potatomc.debug;

import com.potatomc.PotatoMC;
import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.bridge.EngineHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

public final class DifferentialValidator {

    public record Report(
            int totalBlocks,
            int diffCount,
            int maxDelta,
            int blockDiffs,
            int blockMaxDelta,
            int skyDiffs,
            int skyMaxDelta
    ) {}

    private DifferentialValidator() {}

    public static Report runAround(ServerWorld world, Vec3d center, int radius) {
        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int range = radius * 16;

        int total = 0;
        int blockDiffs = 0;
        int blockMaxDelta = 0;
        int skyDiffs = 0;
        int skyMaxDelta = 0;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);

                    int ourBlock = PotatoMC.LIGHT_ENGINE.getLightLevel(pos, LightLevelAPI.LightType.BLOCK);
                    int ourSky = PotatoMC.LIGHT_ENGINE.getLightLevel(pos, LightLevelAPI.LightType.SKY);
                    int[] vanilla = readVanilla(world, pos);
                    total++;

                    int blockDelta = Math.abs(ourBlock - vanilla[0]);
                    if (blockDelta > 0) {
                        blockDiffs++;
                        if (blockDelta > blockMaxDelta) blockMaxDelta = blockDelta;
                        if (blockDelta > 1) {
                            PotatoMC.LOGGER.warn(
                                "[validator] block diff>1 @ {} : potato={} vanilla={}",
                                pos, ourBlock, vanilla[0]);
                        }
                    }

                    int skyDelta = Math.abs(ourSky - vanilla[1]);
                    if (skyDelta > 0) {
                        skyDiffs++;
                        if (skyDelta > skyMaxDelta) skyMaxDelta = skyDelta;
                        if (skyDelta > 1) {
                            PotatoMC.LOGGER.warn(
                                "[validator] sky diff>1 @ {} : potato={} vanilla={}",
                                pos, ourSky, vanilla[1]);
                        }
                    }
                }
            }
        }

        int totalDiffs = blockDiffs + skyDiffs;
        int maxDelta = Math.max(blockMaxDelta, skyMaxDelta);
        return new Report(total, totalDiffs, maxDelta, blockDiffs, blockMaxDelta, skyDiffs, skyMaxDelta);
    }

    private static int[] readVanilla(ServerWorld world, BlockPos pos) {
        int[] result = new int[2];
        EngineHolder.runBypassed(() -> {
            result[0] = world.getLightLevel(LightType.BLOCK, pos);
            result[1] = world.getLightLevel(LightType.SKY, pos);
        });
        return result;
    }
}
