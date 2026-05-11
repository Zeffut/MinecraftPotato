package com.potatomeasure.bench;

import com.potatomeasure.PotatoMCBridge;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public final class Microbench {

    public enum Workload { SINGLE_BLOCK_UPDATE, BULK_RANDOM_UPDATES, FULL_CHUNK_RELIGHT, BULK_WRITES_NO_READ }
    public enum Engine { POTATO, VANILLA }

    public record Result(
        String workload, String engine,
        int iterations,
        long totalNanos,
        long p50Nanos, long p95Nanos, long p99Nanos,
        long opsPerSec
    ) {}

    private Microbench() {}

    public static Result run(ServerWorld world, Workload wl, Engine engine, int iterations, long seed) {
        long[] samples = new long[iterations];
        Random rng = new Random(seed);

        // Warmup
        int warmup = Math.min(iterations / 10, 100);
        for (int i = 0; i < warmup; i++) {
            runOne(world, wl, engine, rng);
        }

        rng = new Random(seed); // reset for reproducibility
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            runOne(world, wl, engine, rng);
            samples[i] = System.nanoTime() - t0;
        }

        long total = 0;
        for (long s : samples) total += s;
        Arrays.sort(samples);
        long p50 = samples[Math.min(iterations - 1, iterations / 2)];
        long p95 = samples[Math.min(iterations - 1, (int)(iterations * 0.95))];
        long p99 = samples[Math.min(iterations - 1, (int)(iterations * 0.99))];
        long opsPerSec = total > 0 ? (1_000_000_000L * iterations) / total : 0L;

        return new Result(wl.name(), engine.name(), iterations, total, p50, p95, p99, opsPerSec);
    }

    private static void runOne(ServerWorld world, Workload wl, Engine engine, Random rng) {
        Runnable task = () -> {
            switch (wl) {
                case SINGLE_BLOCK_UPDATE -> singleBlockUpdate(world, rng);
                case BULK_RANDOM_UPDATES -> bulkRandomUpdates(world, rng);
                case FULL_CHUNK_RELIGHT -> fullChunkRelight(world, rng);
                case BULK_WRITES_NO_READ -> bulkWritesNoRead(world, rng);
            }
        };
        if (engine == Engine.VANILLA) PotatoMCBridge.runBypassed(task);
        else task.run();
    }

    private static void singleBlockUpdate(ServerWorld world, Random rng) {
        int x = rng.nextInt(16);
        int y = 100 + rng.nextInt(8);
        int z = rng.nextInt(16);
        BlockPos pos = new BlockPos(x, y, z);
        world.setBlockState(pos, Blocks.GLOWSTONE.getDefaultState(), 2);
        // Force light resolution by reading neighbor.
        world.getLightLevel(LightType.BLOCK, pos.east());
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
    }

    private static void bulkRandomUpdates(ServerWorld world, Random rng) {
        for (int i = 0; i < 10; i++) {
            int x = rng.nextInt(32);
            int y = 100 + rng.nextInt(16);
            int z = rng.nextInt(32);
            BlockPos pos = new BlockPos(x, y, z);
            world.setBlockState(pos, (i % 2 == 0 ? Blocks.GLOWSTONE : Blocks.AIR).getDefaultState(), 2);
        }
        world.getLightLevel(LightType.BLOCK, new BlockPos(16, 108, 16));
    }

    private static void fullChunkRelight(ServerWorld world, Random rng) {
        int sx = rng.nextInt(4) * 16;
        int sz = rng.nextInt(4) * 16;
        for (int i = 0; i < 16; i++) {
            int x = sx + rng.nextInt(16);
            int y = 96 + rng.nextInt(16);
            int z = sz + rng.nextInt(16);
            world.setBlockState(new BlockPos(x, y, z), Blocks.GLOWSTONE.getDefaultState(), 2);
        }
        world.getLightLevel(LightType.BLOCK, new BlockPos(sx + 8, 104, sz + 8));
        for (int i = 0; i < 16; i++) {
            int x = sx + rng.nextInt(16);
            int y = 96 + rng.nextInt(16);
            int z = sz + rng.nextInt(16);
            world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 2);
        }
    }

    /**
     * Realistic batching workload: place 50 glowstones in a random region,
     * then trigger ONE light resolution at the end. Mirrors what vanilla does
     * during chunk gen / explosion / piston cascade — the case where deferred
     * batching should shine. No intermediate reads to force sync flushes.
     */
    private static void bulkWritesNoRead(ServerWorld world, Random rng) {
        java.util.List<BlockPos> placed = new java.util.ArrayList<>(50);
        for (int i = 0; i < 50; i++) {
            int x = rng.nextInt(32) - 16;
            int y = 100 + rng.nextInt(16);
            int z = rng.nextInt(32) - 16;
            BlockPos pos = new BlockPos(x, y, z);
            world.setBlockState(pos, Blocks.GLOWSTONE.getDefaultState(), 2);
            placed.add(pos);
        }
        // ONE read at the end — triggers a single batched flush in our engine,
        // vs ~50 individual vanilla updates that have already happened.
        world.getLightLevel(LightType.BLOCK, new BlockPos(0, 108, 0));
        // Clean up so the next iteration starts fresh
        for (BlockPos pos : placed) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        }
        world.getLightLevel(LightType.BLOCK, new BlockPos(0, 108, 0));
    }

    public static Map<String, Object> resultToJson(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workload", r.workload());
        m.put("engine", r.engine());
        m.put("iterations", r.iterations());
        m.put("total_ns", r.totalNanos());
        m.put("p50_ns", r.p50Nanos());
        m.put("p95_ns", r.p95Nanos());
        m.put("p99_ns", r.p99Nanos());
        m.put("ops_per_sec", r.opsPerSec());
        return m;
    }
}
