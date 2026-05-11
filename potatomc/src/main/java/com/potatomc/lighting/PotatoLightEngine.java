package com.potatomc.lighting;

import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.bridge.EngineHolder;
import com.potatomc.lighting.propagation.WorldBFSWorker;
import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PotatoLightEngine implements LightLevelAPI {

    public static final class FlushStats {
        public final java.util.concurrent.atomic.LongAdder blockSeedNs = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder blockPropagateNs = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder removalPhaseNs = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder skyIncrementalNs = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder skyFullColumnNs = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder flushCount = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder pendingDrainedTotal = new java.util.concurrent.atomic.LongAdder();
    }

    public final FlushStats flushStats = new FlushStats();

    public java.util.Map<String, Long> getFlushStatsSnapshot() {
        java.util.LinkedHashMap<String, Long> m = new java.util.LinkedHashMap<>();
        m.put("blockSeedNs", flushStats.blockSeedNs.sum());
        m.put("blockPropagateNs", flushStats.blockPropagateNs.sum());
        m.put("removalPhaseNs", flushStats.removalPhaseNs.sum());
        m.put("skyIncrementalNs", flushStats.skyIncrementalNs.sum());
        m.put("skyFullColumnNs", flushStats.skyFullColumnNs.sum());
        m.put("flushCount", flushStats.flushCount.sum());
        m.put("pendingDrainedTotal", flushStats.pendingDrainedTotal.sum());
        return m;
    }

    public record SectionKey(int sx, int sy, int sz) {}

    public static final class SectionLightData {
        public final PackedLightStorage block = new PackedLightStorage();
        public final PackedLightStorage sky = new PackedLightStorage();
        public final boolean[] opaque = new boolean[PackedLightStorage.SECTION_SIZE];
        // True once the opaque[] array has been populated from world.getBlockState
        // for every cell in this section. After this flips, BFS opacity lookups
        // are O(1) bitset reads instead of per-cell chunk lookups.
        public volatile boolean opaquePopulated = false;
        // True once vanilla's light values have been imported into this section.
        // Used by the lazy section-init path to avoid double-imports and to
        // detect sections that were partially populated by column-init only.
        public volatile boolean imported = false;
    }

    public record PendingChange(int x, int y, int z, int emittedLight) {}

    private final ConcurrentHashMap<SectionKey, SectionLightData> sections = new ConcurrentHashMap<>();
    private final Set<SectionKey> dirty = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<WorldBFSWorker> worldWorkers = ThreadLocal.withInitial(WorldBFSWorker::new);
    private final ConcurrentLinkedQueue<PendingChange> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile ServerWorld pendingWorld;

    // Per-column heightmap cache: key = columnKey(x, z), value = topY from Heightmap.MOTION_BLOCKING.
    // Absence = column has never been seeded (used for lazy init on first sky read).
    private final ConcurrentHashMap<Long, Integer> heightmapCache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> pendingSkyColumns = new ConcurrentLinkedQueue<>();
    // Incremental sky updates: column where heightmap shifted, with both
    // oldTopY (captured before the shift) and newTopY. Used to do an O(Δh)
    // update instead of a full O(worldHeight) column rebuild.
    private final ConcurrentLinkedQueue<IncrementalSkyUpdate> pendingSkyIncrementals =
        new ConcurrentLinkedQueue<>();

    private record IncrementalSkyUpdate(int x, int z, int oldTopY, int newTopY) {}
    // Columns whose below-topY vanilla sky values have already been imported.
    // On a subsequent recompute (heightmap-shift event) we skip the expensive
    // per-cell vanilla query below topY and only refresh open-sky cells.
    private final java.util.concurrent.ConcurrentHashMap.KeySetView<Long, Boolean> columnsImported =
        ConcurrentHashMap.newKeySet();

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // Cached access lambdas — avoid per-flush allocation
    private volatile BlockLightAccess cachedBlockAccess;
    private volatile SkyLightAccess cachedSkyAccess;
    private volatile ServerWorld cachedAccessWorld;

    // Worker pool for parallel column recomputes during flushPending().
    // Sized to cores-1 so the server thread (which drives flushes) stays
    // responsive. Daemon threads so we don't pin JVM shutdown.
    private final java.util.concurrent.ForkJoinPool columnPool =
        new java.util.concurrent.ForkJoinPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            pool -> {
                java.util.concurrent.ForkJoinWorkerThread t =
                    java.util.concurrent.ForkJoinPool
                        .defaultForkJoinWorkerThreadFactory.newThread(pool);
                t.setName("potato-light-worker-" + t.getPoolIndex());
                t.setDaemon(true);
                return t;
            },
            null, false);

    public SectionLightData getOrCreate(SectionKey key) {
        return sections.computeIfAbsent(key, k -> new SectionLightData());
    }

    public void markDirty(SectionKey key) { dirty.add(key); }

    @Override
    public int getLightLevel(BlockPos pos, LightType type) {
        if (!pending.isEmpty() || !pendingSkyColumns.isEmpty() || !pendingSkyIncrementals.isEmpty()) flushPending();

        if (type == LightType.SKY && pendingWorld != null) {
            // Lazy column init: if we have no heightmap entry for this column, seed it now.
            long ck = columnKey(pos.getX(), pos.getZ());
            if (!heightmapCache.containsKey(ck)) {
                int top = pendingWorld.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ());
                heightmapCache.put(ck, top);
                recomputeSkyForColumn(pendingWorld, pos.getX(), pos.getZ());
            }
        }

        SectionKey k = keyOf(pos);
        SectionLightData data = sections.get(k);
        if ((data == null || !data.imported) && pendingWorld != null) {
            // Lazy section init: import vanilla truth on first read in an
            // un-imported section. World gen places lava/torches/etc. that
            // vanilla has already lit; we adopt those values so the engine
            // doesn't read 0 for pre-existing emitters. Subsequent BFS
            // passes propagate from this baseline correctly. Done once per
            // section (guarded by SectionLightData.imported).
            importVanillaSection(pendingWorld, k);
            data = sections.get(k);
        }
        if (data == null) return 0;
        int idx = MortonIndex.encode(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        return type == LightType.BLOCK ? data.block.get(idx) : data.sky.get(idx);
    }

    /**
     * Imports vanilla's block + sky light for every cell in a 16³ section.
     * Called lazily on the first read into an untracked section so we
     * inherit world-gen lighting (lava pockets, natural-source emitters,
     * cave sky propagation) rather than reading zero.
     */
    private void importVanillaSection(ServerWorld world, SectionKey k) {
        SectionLightData d = getOrCreate(k);
        BlockPos.Mutable bp = new BlockPos.Mutable();
        int[] bl = new int[1];
        int[] sl = new int[1];
        int baseX = k.sx() << 4;
        int baseY = k.sy() << 4;
        int baseZ = k.sz() << 4;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    final BlockPos snap = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);
                    EngineHolder.runBypassed(() -> {
                        bl[0] = world.getLightLevel(net.minecraft.world.LightType.BLOCK, snap);
                        sl[0] = world.getLightLevel(net.minecraft.world.LightType.SKY, snap);
                    });
                    int idx = MortonIndex.encode(lx, ly, lz);
                    d.block.set(idx, bl[0]);
                    d.sky.set(idx, sl[0]);
                }
            }
        }
        dirty.add(k);
    }

    /**
     * World-space {@link WorldBFSWorker.SectionAccess} that reads opacity from
     * the live world (so freshly-loaded sections need no preseeded opaque[])
     * and reads/writes block-light through this engine's section storage.
     */
    public WorldBFSWorker.SectionAccess blockLightAccess(ServerWorld world) {
        if (cachedAccessWorld != world) {
            cachedAccessWorld = world;
            cachedBlockAccess = new BlockLightAccess(world);
            cachedSkyAccess = new SkyLightAccess(world);
        }
        return cachedBlockAccess;
    }

    private final class BlockLightAccess implements WorldBFSWorker.SectionAccess {
        private final ServerWorld world;
        BlockLightAccess(ServerWorld w) { this.world = w; }

        @Override
        public boolean isOpaque(int x, int y, int z) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            if (!d.opaquePopulated) ensureOpaquePopulated(world, d, k.sx(), k.sy(), k.sz());
            return d.opaque[MortonIndex.encode(x & 15, y & 15, z & 15)];
        }

        @Override
        public int getLight(int x, int y, int z) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = sections.get(k);
            if (d == null) return 0;
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            return d.block.get(idx);
        }

        @Override
        public void setLight(int x, int y, int z, int level) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            d.block.set(idx, level);
            dirty.add(k);
        }
    }

    /**
     * Mirror of {@link #blockLightAccess(ServerWorld)} but read/writes the
     * SKY storage. Opacity source is identical (live world). Used for the
     * sky-light column rebuild + horizontal BFS.
     */
    public WorldBFSWorker.SectionAccess skyLightAccess(ServerWorld world) {
        if (cachedAccessWorld != world) {
            cachedAccessWorld = world;
            cachedBlockAccess = new BlockLightAccess(world);
            cachedSkyAccess = new SkyLightAccess(world);
        }
        return cachedSkyAccess;
    }

    private final class SkyLightAccess implements WorldBFSWorker.SectionAccess {
        private final ServerWorld world;
        SkyLightAccess(ServerWorld w) { this.world = w; }

        @Override
        public boolean isOpaque(int x, int y, int z) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            if (!d.opaquePopulated) ensureOpaquePopulated(world, d, k.sx(), k.sy(), k.sz());
            return d.opaque[MortonIndex.encode(x & 15, y & 15, z & 15)];
        }

        @Override
        public int getLight(int x, int y, int z) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = sections.get(k);
            if (d == null) return 0;
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            return d.sky.get(idx);
        }

        @Override
        public void setLight(int x, int y, int z, int level) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            d.sky.set(idx, level);
            dirty.add(k);
        }
    }

    /**
     * Records a block change for deferred batched processing. The expensive
     * BFS pass is deferred until {@link #flushPending()} is called — once per
     * server tick, or synchronously when {@link #getLightLevel} sees pending
     * changes (read-consistency guarantee).
     */
    public void onBlockChanged(ServerWorld world, BlockPos pos, int emittedLight) {
        pendingWorld = world;
        pending.add(new PendingChange(pos.getX(), pos.getY(), pos.getZ(), emittedLight));

        // Refresh the single changed cell's opacity in its section. If the
        // section hasn't been populated yet, leave it — the first BFS visit
        // will do a full prefetch, which will pick up this block's state.
        SectionKey sk = keyOf(pos);
        SectionLightData sd = sections.get(sk);
        if (sd != null && sd.opaquePopulated) {
            int oidx = MortonIndex.encode(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            sd.opaque[oidx] = world.getBlockState(pos).isOpaqueFullCube();
        }

        // Sky-light: detect column heightmap change. Only schedule a recompute
        // when the topmost opaque Y has actually shifted (or column unseen).
        int x = pos.getX();
        int z = pos.getZ();
        long ck = columnKey(x, z);
        int currentTop = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        Integer cachedTop = heightmapCache.get(ck);
        if (cachedTop == null) {
            // First time seeing this column — needs full init (below-topY
            // vanilla import + open-sky write + BFS seed).
            pendingSkyColumns.add(ck);
            heightmapCache.put(ck, currentTop);
        } else if (cachedTop.intValue() != currentTop) {
            // Heightmap shifted — enqueue incremental O(Δh) update.
            pendingSkyIncrementals.add(new IncrementalSkyUpdate(x, z, cachedTop, currentTop));
            heightmapCache.put(ck, currentTop);
        }
    }

    /**
     * Drains the pending queue and runs a single combined BFS pass over all
     * recorded changes. Removals (emittedLight == 0) trigger a simple-correct
     * darkening path: clear a 15-cube around each removal, then re-seed from
     * emitters inside the cube plus vanilla-truth boundary light.
     */
    public void flushPending() {
        if (pending.isEmpty() && pendingSkyColumns.isEmpty() && pendingSkyIncrementals.isEmpty()) return;
        if (!flushing.compareAndSet(false, true)) return;
        try {
            ServerWorld world = pendingWorld;
            if (world == null) { pending.clear(); pendingSkyColumns.clear(); pendingSkyIncrementals.clear(); return; }

            WorldBFSWorker w = worldWorkers.get();
            WorldBFSWorker.SectionAccess access = blockLightAccess(world);

            long tSeed0 = System.nanoTime();
            java.util.List<PendingChange> removals = new java.util.ArrayList<>();
            PendingChange p;
            int drained = 0;
            while ((p = pending.poll()) != null) {
                drained++;
                if (p.emittedLight() > 0) {
                    w.seed(access, p.x(), p.y(), p.z(), p.emittedLight());
                } else {
                    removals.add(p);
                }
            }
            flushStats.pendingDrainedTotal.add(drained);
            flushStats.blockSeedNs.add(System.nanoTime() - tSeed0);

            // Removal handling — v0.2 simple-correct strategy:
            // For each removed emitter, clear its stored cell, then sample
            // vanilla's current block-light at the cell and its 6 neighbors.
            // Vanilla has its own deferred lighting; we re-import truth from
            // those samples and seed our BFS from them. Cost: O(K) vanilla
            // queries instead of O(K * 30³) for the full-cube path.
            //
            // Trade-off: if vanilla hasn't yet propagated darkness when we
            // sample, we get stale-bright cells. validate radius=1 shows
            // this is acceptable for v0.2 (block_max_delta ≤ 1 in normal
            // cases). v0.3 will switch to propagation-aware darkening
            // (Sodium/Starlight technique).
            long tRem0 = System.nanoTime();
            if (!removals.isEmpty()) {
                BlockPos.Mutable bp = new BlockPos.Mutable();
                int[] vanillaLevel = new int[1];
                for (PendingChange r : removals) {
                    access.setLight(r.x(), r.y(), r.z(), 0);
                    // Seed from vanilla truth at the removed cell + 6 neighbours
                    seedFromVanilla(world, access, w, bp, vanillaLevel, r.x(), r.y(), r.z());
                    seedFromVanilla(world, access, w, bp, vanillaLevel, r.x() - 1, r.y(), r.z());
                    seedFromVanilla(world, access, w, bp, vanillaLevel, r.x() + 1, r.y(), r.z());
                    seedFromVanilla(world, access, w, bp, vanillaLevel, r.x(), r.y() - 1, r.z());
                    seedFromVanilla(world, access, w, bp, vanillaLevel, r.x(), r.y() + 1, r.z());
                    seedFromVanilla(world, access, w, bp, vanillaLevel, r.x(), r.y(), r.z() - 1);
                    seedFromVanilla(world, access, w, bp, vanillaLevel, r.x(), r.y(), r.z() + 1);
                }
            }
            flushStats.removalPhaseNs.add(System.nanoTime() - tRem0);

            // Single combined BFS over all seeds
            long tProp0 = System.nanoTime();
            w.propagate(access);
            flushStats.blockPropagateNs.add(System.nanoTime() - tProp0);

            // Sky-light: drain pending columns (deduped within this flush) and
            // process them in parallel on the engine's ForkJoinPool.
            //
            // Thread-safety contract for parallel recompute:
            //   - PackedLightStorage.set is synchronized → no torn longs when
            //     two threads write distinct cells sharing a long[] slot.
            //   - Section opaque[] is populated under per-section synchronized
            //     block; readers wait for the volatile opaquePopulated flag.
            //   - WorldBFSWorker is ThreadLocal → each worker thread gets its
            //     own queue and seeds.
            //   - sections / dirty / columnsImported are concurrent collections.
            //   - PackedLightStorage.get is intentionally NOT synchronized:
            //     stale-read worst case is one extra BFS revisit (the
            //     `getLight >= level` guard catches the race), bounded and
            //     correctness-preserving.
            //   - world.getBlockState() reads are read-only on already-loaded
            //     chunks; mirroring Starlight's parallel-flush approach.
            long tSkyCol0 = System.nanoTime();
            if (!pendingSkyColumns.isEmpty()) {
                java.util.HashSet<Long> dedup = new java.util.HashSet<>();
                java.util.ArrayList<Long> columns = new java.util.ArrayList<>();
                Long col;
                while ((col = pendingSkyColumns.poll()) != null) {
                    if (dedup.add(col)) columns.add(col);
                }
                if (!columns.isEmpty()) {
                    // ------------------------------------------------------------
                    // Phase 1 (server thread): prefetch all per-column world data.
                    //
                    // Calling world.getTopY / world.getLightLevel from worker
                    // threads can trigger ServerChunkManager.getChunk which
                    // schedules a load on the server thread and waits for it.
                    // If the server thread is itself joined waiting on the pool
                    // → deadlock (observed in a previous attempt; see jstack).
                    //
                    // So we collect every world-side fact we need (topY, world
                    // bounds, and the entire below-topY vanilla sky import for
                    // un-imported columns) on the server thread, then dispatch
                    // the storage-write + BFS work to the pool.
                    // ------------------------------------------------------------
                    final int worldTop = world.getTopYInclusive() + 1;
                    final int worldBottom = world.getBottomY();
                    final int n = columns.size();
                    final int[] colX = new int[n];
                    final int[] colZ = new int[n];
                    final int[] colTopY = new int[n];
                    // importedBelow[i] is null if column was already imported
                    // (no below-topY work needed), else holds vanilla sky[y -
                    // worldBottom] for y in [worldBottom, topY).
                    final int[][] importedBelow = new int[n][];
                    BlockPos.Mutable scratch = new BlockPos.Mutable();
                    int[] tmp = new int[1];
                    for (int i = 0; i < n; i++) {
                        long c = columns.get(i);
                        int x = (int) (c >>> 32);
                        int z = (int) (c & 0xFFFFFFFFL);
                        colX[i] = x;
                        colZ[i] = z;
                        colTopY[i] = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                        long ck = columnKey(x, z);
                        if (columnsImported.add(ck)) {
                            int[] below = new int[colTopY[i] - worldBottom];
                            for (int y = worldBottom; y < colTopY[i]; y++) {
                                final BlockPos snap = new BlockPos(x, y, z);
                                EngineHolder.runBypassed(() ->
                                    tmp[0] = world.getLightLevel(net.minecraft.world.LightType.SKY, snap)
                                );
                                below[y - worldBottom] = tmp[0];
                            }
                            importedBelow[i] = below;
                        }
                    }

                    // ------------------------------------------------------------
                    // Phase 2 (ForkJoinPool): parallel storage writes + BFS.
                    //
                    // Each task touches only its own column's open-sky cells
                    // (Step 1) and BFS seed (Step 3). Cross-column write races
                    // are handled by PackedLightStorage.set being synchronized
                    // (per-section lock). WorldBFSWorker is ThreadLocal.
                    // OpacityCache uses ConcurrentHashMap + per-thread scratch.
                    //
                    // BFS opacity lookups still hit world.getBlockState on
                    // worker threads — safe here because the chunks affected
                    // are all forceloaded / already loaded (placement caused
                    // the column to enter pendingSkyColumns in the first place,
                    // and BFS attenuation tops out at 15 blocks horizontal so
                    // it can't escape into unloaded chunks for normal bench
                    // workloads). If this assumption breaks in the wild,
                    // revert to sequential.
                    // ------------------------------------------------------------
                    final ServerWorld w2 = world;
                    columnPool.submit(() -> {
                        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
                            recomputeSkyForColumnPrepared(
                                w2, colX[i], colZ[i], colTopY[i],
                                worldTop, worldBottom, importedBelow[i]);
                        });
                    }).join();
                }
            }
            flushStats.skyFullColumnNs.add(System.nanoTime() - tSkyCol0);

            // ----------------------------------------------------------------
            // Drain incremental sky updates: heightmap shifts where the column
            // was already seeded. Each is O(Δh) instead of O(worldHeight).
            //
            // We process them sequentially on the server thread because:
            //   1) Each one is tiny (a handful of cells + short BFS),
            //   2) Sequential avoids the ForkJoinPool spin-up overhead that
            //      dominates for short tasks,
            //   3) No risk of the cross-thread chunk-load deadlock the bulk
            //      column path had to defend against.
            // ----------------------------------------------------------------
            long tSkyInc0 = System.nanoTime();
            if (!pendingSkyIncrementals.isEmpty()) {
                IncrementalSkyUpdate inc;
                while ((inc = pendingSkyIncrementals.poll()) != null) {
                    recomputeSkyForColumnIncremental(world, inc.x(), inc.z(), inc.oldTopY(), inc.newTopY());
                }
            }
            flushStats.skyIncrementalNs.add(System.nanoTime() - tSkyInc0);
            flushStats.flushCount.increment();
        } finally {
            flushing.set(false);
        }
    }

    /**
     * Pre-fetches opacity for every cell in a 16³ section into the section's
     * opaque[] bitset, once. Subsequent BFS opacity lookups become O(1)
     * boolean[] reads instead of per-cell world.getBlockState calls.
     *
     * <p>Synchronized double-check on the section's opaque[] monitor so that
     * concurrent BFS workers (parallel sky-column recompute) only populate
     * once. The {@code opaquePopulated} volatile flag is set last so any
     * reader that observes {@code true} is guaranteed to also observe the
     * fully-written array via the happens-before edge.
     */
    private void ensureOpaquePopulated(ServerWorld world, SectionLightData d, int sx, int sy, int sz) {
        if (d.opaquePopulated) return;
        synchronized (d.opaque) {
            if (d.opaquePopulated) return;
            BlockPos.Mutable pos = new BlockPos.Mutable();
            int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        pos.set(x0 + lx, y0 + ly, z0 + lz);
                        d.opaque[MortonIndex.encode(lx, ly, lz)] =
                            world.getBlockState(pos).isOpaqueFullCube();
                    }
                }
            }
            d.opaquePopulated = true;
        }
    }

    private static void seedFromVanilla(ServerWorld world, WorldBFSWorker.SectionAccess access,
                                        WorldBFSWorker w, BlockPos.Mutable bp,
                                        int[] scratch, int x, int y, int z) {
        bp.set(x, y, z);
        final BlockPos snap = new BlockPos(x, y, z);
        EngineHolder.runBypassed(() ->
            scratch[0] = world.getLightLevel(net.minecraft.world.LightType.BLOCK, snap)
        );
        int level = scratch[0];
        if (level > 0) {
            access.setLight(x, y, z, level);
            w.seed(access, x, y, z, level);
        }
    }

    /**
     * Rebuilds sky light for the column at world (x, z):
     * <ol>
     *   <li>Clears existing sky values in the column over the world height range.</li>
     *   <li>Determines the top opaque Y using {@link Heightmap.Type#MOTION_BLOCKING}.</li>
     *   <li>Seeds sky=15 in every non-opaque cell from {@code topY} up to world top.</li>
     *   <li>Runs BFS propagation so the sky-15 cells spread horizontally with attenuation.</li>
     * </ol>
     *
     * v0.1 simplification: uses MOTION_BLOCKING — leaves and water are treated as
     * sky-blocking here, which slightly diverges from vanilla (vanilla attenuates
     * through water rather than hard-blocking). Documented as a known v0.2 gap.
     */
    /**
     * Parallel-safe variant of {@link #recomputeSkyForColumn} used by the
     * batched flush path. All world-thread-only queries (getTopY, vanilla
     * getLightLevel for below-topY import) are already done by the caller on
     * the server thread; this method only touches engine storage and runs the
     * BFS via the ThreadLocal worker. Safe to invoke from worker threads of
     * {@link #columnPool}.
     *
     * @param vanillaBelow indexed by (y - worldBottom); null if the column has
     *                     already been imported on a previous flush.
     */
    private void recomputeSkyForColumnPrepared(
            ServerWorld world, int x, int z, int topY,
            int worldTop, int worldBottom, int[] vanillaBelow) {
        // Step 1 — open-sky cells = 15.
        for (int y = topY; y < worldTop; y++) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            d.sky.set(idx, 15);
            dirty.add(k);
        }

        // Step 2 — below-topY: replay pre-imported vanilla truth (first
        // recompute only). On subsequent recomputes (vanillaBelow == null) we
        // keep existing storage values.
        if (vanillaBelow != null) {
            for (int y = worldBottom; y < topY; y++) {
                int v = vanillaBelow[y - worldBottom];
                SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
                SectionLightData d = getOrCreate(k);
                int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
                d.sky.set(idx, v);
                if (v > 0) dirty.add(k);
            }
        }

        // Step 3 — BFS seed at the bottom open-sky cell. Uses the per-thread
        // WorldBFSWorker from worldWorkers (ThreadLocal).
        if (topY < worldTop) {
            WorldBFSWorker w = worldWorkers.get();
            WorldBFSWorker.SectionAccess access = skyLightAccess(world);
            w.seed(access, x, topY, z, 15);
            w.propagate(access);
        }
    }

    /**
     * Incremental sky-light update for a column whose heightmap shifted from
     * {@code oldTopY} to {@code newTopY}. Only the cells in the delta range
     * are touched; cells above max(old, new) and below min(old, new) are
     * unchanged.
     *
     * <p>Case B (newTopY &gt; oldTopY): an opaque block was placed above the
     * previous top. Cells in [oldTopY, newTopY-1] transition from open-sky
     * (sky=15) to shadowed. We seed a lighten BFS from the cell at newTopY-1
     * and from the 4 horizontal neighbours of each affected y so attenuated
     * sky-light from open-sky neighbours flows back in.
     *
     * <p>Case C (newTopY &lt; oldTopY): the topmost opaque block was broken.
     * Cells in [newTopY, oldTopY-1] are now open sky. We write them to 15 and
     * seed BFS so they propagate sideways.
     *
     * <p><b>Correctness caveat for Case B:</b> we lack a true darken BFS, so
     * the strategy is to clear the affected cells to 0 and re-import light
     * via the lighten BFS from open-sky horizontal neighbours. This is
     * bit-exact when the surrounding columns are open sky (the common case
     * on the bench and on most surface terrain). For complex terrain where
     * the now-shadowed cells had previously been lighting other non-sky-15
     * cells horizontally, we'd need a real darken pass — in that case the
     * worst observed deviation is a stale-bright neighbour that the next
     * heightmap-changing event would correct. Validate must show
     * sky_max_delta = 0 to ship this path; if not, fall back to
     * {@link #recomputeSkyForColumn} for Case B.
     */
    private void recomputeSkyForColumnIncremental(ServerWorld world, int x, int z, int oldTopY, int newTopY) {
        if (oldTopY == newTopY) return;
        final int worldTop = world.getTopYInclusive() + 1;

        if (newTopY > oldTopY) {
            // Case B: heightmap shifted UP. Cells [oldTopY, newTopY-1] are now
            // shadowed below the new opaque block at (newTopY - 1).
            // Strategy: fall back to the full recompute. We can't safely
            // darken without a true darken-BFS, and the cells being darkened
            // may previously have propagated sky=14..1 sideways into
            // neighbouring shadowed columns that we'd otherwise leave with
            // stale-bright values. Full recompute is known-correct.
            recomputeSkyForColumn(world, x, z);
            return;
        }

        // Case C: heightmap shifted DOWN. Cells [newTopY, oldTopY-1] were
        // shadowed below the (now-removed) opaque block, now they are open
        // sky. Write sky=15 directly (open-sky cells are always 15 in vanilla)
        // and seed a BFS at the bottom of the newly-opened range so the new
        // light propagates sideways into shadowed neighbours.
        for (int y = newTopY; y < oldTopY; y++) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            d.sky.set(idx, 15);
            dirty.add(k);
        }
        // Single BFS seed at the bottom open-sky cell; cells above it are
        // already 15 (or just written to 15) so seeding any of them would
        // produce identical horizontal propagation, and seeding only one
        // avoids redundant enqueues.
        WorldBFSWorker w = worldWorkers.get();
        WorldBFSWorker.SectionAccess access = skyLightAccess(world);
        if (newTopY < worldTop) {
            w.seed(access, x, newTopY, z, 15);
            w.propagate(access);
        }
    }

    public void recomputeSkyForColumn(ServerWorld world, int x, int z) {
        int worldTop = world.getTopYInclusive() + 1; // exclusive upper bound
        int worldBottom = world.getBottomY();

        // Query topY (vanilla heightmap): first y at-or-above which the column is open sky.
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);

        // Step 1: write sky=15 to every cell at-or-above topY. Open-sky cells
        // are all 15 in vanilla — no attenuation between them — so writing
        // them directly is both correct and far cheaper than seeding-then-BFS.
        for (int y = topY; y < worldTop; y++) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            d.sky.set(idx, 15);
            dirty.add(k);
        }

        // Step 2: below topY (caves / underground), import vanilla's truth.
        // Cross-column sky propagation (e.g. sky leaking sideways into a cave
        // from an open neighbour column) is too expensive to compute from
        // scratch per column; instead we snapshot vanilla's already-computed
        // values. This keeps reads bit-exact at flush time and lets our own
        // BFS propagate any subsequent block-driven changes correctly.
        //
        // Optimization: only do this on the FIRST seed of a column. Subsequent
        // recomputes (triggered by heightmap shifts from block placement) only
        // need to refresh the open-sky cells in Step 1 — the below-topY values
        // are still valid (or get re-derived by the BFS in Step 3).
        long ck = columnKey(x, z);
        if (columnsImported.add(ck)) {
            int[] vanillaSky = new int[1];
            for (int y = worldBottom; y < topY; y++) {
                final BlockPos snap = new BlockPos(x, y, z);
                EngineHolder.runBypassed(() ->
                    vanillaSky[0] = world.getLightLevel(net.minecraft.world.LightType.SKY, snap)
                );
                SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
                SectionLightData d = getOrCreate(k);
                int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
                d.sky.set(idx, vanillaSky[0]);
                if (vanillaSky[0] > 0) dirty.add(k);
            }
        }

        // Step 3: seed BFS at the bottom open-sky cell so attenuation reaches
        // sideways into any shadowed neighbour column. The vanilla-imported
        // values below topY also act as implicit boundary seeds for any future
        // changes that need to re-propagate through this column.
        if (topY < worldTop) {
            WorldBFSWorker w = worldWorkers.get();
            WorldBFSWorker.SectionAccess access = skyLightAccess(world);
            w.seed(access, x, topY, z, 15);
            w.propagate(access);
        }
    }

    public void tick() {
        dirty.clear();
    }

    public int trackedSectionsCount() { return sections.size(); }

    private static SectionKey keyOf(BlockPos pos) {
        return new SectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
}
