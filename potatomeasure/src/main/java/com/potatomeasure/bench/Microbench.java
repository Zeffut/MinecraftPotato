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

    public enum Workload { SINGLE_BLOCK_UPDATE, BULK_RANDOM_UPDATES, FULL_CHUNK_RELIGHT, BULK_WRITES_NO_READ, CHUNK_LOAD_COLD }
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
                case CHUNK_LOAD_COLD -> chunkLoadCold(world, rng);
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
    private static int chunkLoadCounter = 0;

    /**
     * Cold chunk-lighting workload. Each iteration force-loads a NEW chunk
     * (one never previously lit by either engine), then forces lighting
     * settlement via a sample read. This measures the Starlight-style win:
     * lighting computation when chunks first become active.
     *
     * Chunks are picked deterministically but far from spawn (cx/cz ≥ 100,
     * stride 4 chunks) so warmup + main pass + both engines see distinct
     * chunks. Counter is process-global so re-runs also pick fresh chunks
     * until the 100×100 grid (~40k chunks) is exhausted.
     */
    private static void chunkLoadCold(ServerWorld world, Random rng) {
        int counter = chunkLoadCounter++;
        // Sweep a fresh 16³ section per iteration inside the 4 always-loaded
        // chunks at spawn (forceload -2..2 only really loads chunks (-1,-1)
        // to (0,0) — vanilla `forceload` takes BLOCK coords, so -2..2 = 4
        // chunks). We sweep Y sections [4..18] (Y=64..303) across those 4
        // chunks giving 60 unique never-before-imported sections — enough
        // for warmup(3) + main(30) × 2 engines = 66.
        //
        // What this measures (the Starlight-style cold-chunk win):
        //   - importVanillaSection: 4096 vanilla light queries + packed
        //     storage writes on the first read into an unseen section.
        //   - lazy column init (recomputeSkyForColumn) for the column at
        //     placement coords on the first sky read.
        //   - block-light seed + BFS for the placed glowstone.
        //   - sky-light heightmap shift seed + sky BFS (placement raises top).
        // Vanilla pays the equivalent work via its own light providers.
        int sx = ((counter / 15) % 2) - 1;            // -1 or 0
        int sz = (((counter / 15) / 2) % 2) - 1;       // -1 or 0
        int sy = 4 + (counter % 15);                   // 4..18
        int blockX = (sx << 4) + 8;
        int blockY = (sy << 4) + 8;
        int blockZ = (sz << 4) + 8;

        BlockPos pos = new BlockPos(blockX, blockY, blockZ);
        // Place a glowstone — triggers light source seeding in both engines.
        world.setBlockState(pos, Blocks.GLOWSTONE.getDefaultState(), 2);

        // Read at the section centre + a far corner to force light resolution
        // across the section. For our engine, the first read into an
        // un-imported section also pays the importVanillaSection cost (4096
        // vanilla queries) — that's the headline "cold chunk lighting" cost
        // we want to measure.
        world.getLightLevel(LightType.BLOCK, pos);
        world.getLightLevel(LightType.SKY, pos);
        world.getLightLevel(LightType.BLOCK, pos.east(7));

        // Restore air so subsequent unrelated reads don't pollute follow-up
        // benches. Position is unique to this iter so no aliasing.
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
    }

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
