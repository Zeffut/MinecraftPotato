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

    public enum Workload {
        SINGLE_BLOCK_UPDATE, BULK_RANDOM_UPDATES, FULL_CHUNK_RELIGHT, BULK_WRITES_NO_READ, CHUNK_LOAD_COLD,
        GAMEPLAY_PLAYER_PACE, GAMEPLAY_EXPLORATION, EXPLOSION_BURST, WORLDGEN_STREAMING
    }
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
                case GAMEPLAY_PLAYER_PACE -> gameplayPlayerPace(world, rng);
                case GAMEPLAY_EXPLORATION -> gameplayExploration(world, rng);
                case EXPLOSION_BURST -> explosionBurst(world, rng);
                case WORLDGEN_STREAMING -> worldgenStreaming(world, rng);
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

    // ===== Realistic gameplay workloads =====================================

    private static void gameplayPlayerPace(ServerWorld world, Random rng) {
        int cx = rng.nextInt(8) - 4;
        int cz = rng.nextInt(8) - 4;
        int cy = 100;
        for (int i = 0; i < 10; i++) {
            BlockPos pos = new BlockPos(cx + rng.nextInt(8) - 4, cy + rng.nextInt(4), cz + rng.nextInt(8) - 4);
            boolean placing = rng.nextBoolean();
            world.setBlockState(pos, placing ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(), 2);
            world.getLightLevel(LightType.BLOCK, pos.up());
        }
    }

    private static int explorationCounter = 0;
    private static void gameplayExploration(ServerWorld world, Random rng) {
        int counter = explorationCounter++;
        // Sweep across the always-forceloaded -8..8 chunk band at spawn.
        // Simulates a player walking and sampling sky+block light at many points
        // per "view" — what happens as the renderer queries chunks the player can
        // see. We can't forceload new chunks here (main-thread deadlock), so we
        // pick a moving region inside the pre-loaded area to mirror "fresh look"
        // behaviour: different chunks per iteration -> different cold sections.
        // forceload -8..8 covers chunks (-1,-1)..(0,0). Stay strictly inside.
        int cx = -1 + (counter % 2);
        int cz = -1 + ((counter / 2) % 2);
        for (int dx = 0; dx < 16; dx += 3) {
            for (int dz = 0; dz < 16; dz += 3) {
                BlockPos pos = new BlockPos((cx << 4) + dx, 80 + rng.nextInt(40), (cz << 4) + dz);
                world.getLightLevel(LightType.BLOCK, pos);
                world.getLightLevel(LightType.SKY, pos);
            }
        }
    }

    private static void explosionBurst(ServerWorld world, Random rng) {
        // Stay inside chunks -1..0 (forceload -8..8). Center within [-8,7] in
        // each axis, sphere radius 4 -> reaches [-12..11]; light BFS up to 15
        // more blocks. We accept some light queries straddling chunk +/-1
        // (always-loaded by chunk ticket from forceload).
        int cx = rng.nextInt(12) - 6, cy = 100, cz = rng.nextInt(12) - 6;
        int r = 4;
        java.util.List<BlockPos> placed = new java.util.ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx*dx + dy*dy + dz*dz <= r*r) {
                        BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                        world.setBlockState(pos, Blocks.STONE.getDefaultState(), 2);
                        placed.add(pos);
                    }
                }
            }
        }
        for (BlockPos pos : placed) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        }
        world.getLightLevel(LightType.BLOCK, new BlockPos(cx + r + 1, cy, cz));
        world.getLightLevel(LightType.SKY, new BlockPos(cx, cy + r + 1, cz));
    }

    private static int worldgenCounter = 0;
    private static void worldgenStreaming(ServerWorld world, Random rng) {
        int counter = worldgenCounter++;
        // Simulates server processing chunk activation across multiple Y
        // layers as new chunks "stream in". We can't forceload new chunks
        // from inside the bench tick (main-thread deadlock), so we sweep
        // Y layers within the preloaded -8..8 chunk band (chunks -1..0).
        // Each iter places 9 glowstones across 3 Y layers in a 3x3 grid,
        // exercising the full sky+block light propagation per layer — the
        // work the server does each tick when chunks transition to FULL.
        int baseCx = -1 + (counter % 2);
        int baseCz = -1 + ((counter / 2) % 2);
        int yOff = 70 + ((counter * 3) % 60);
        java.util.List<BlockPos> placed = new java.util.ArrayList<>();
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    int x = (baseCx << 4) + dx * 5 + 1;
                    int z = (baseCz << 4) + dz * 5 + 1;
                    BlockPos pos = new BlockPos(x, yOff + dy * 8, z);
                    world.setBlockState(pos, Blocks.GLOWSTONE.getDefaultState(), 2);
                    placed.add(pos);
                    world.getLightLevel(LightType.BLOCK, pos);
                    world.getLightLevel(LightType.SKY, pos);
                }
            }
        }
        for (BlockPos pos : placed) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        }
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
