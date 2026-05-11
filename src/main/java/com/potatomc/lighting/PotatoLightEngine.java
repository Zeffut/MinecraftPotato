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

    public record SectionKey(int sx, int sy, int sz) {}

    public static final class SectionLightData {
        public final PackedLightStorage block = new PackedLightStorage();
        public final PackedLightStorage sky = new PackedLightStorage();
        public final boolean[] opaque = new boolean[PackedLightStorage.SECTION_SIZE];
    }

    public record PendingChange(int x, int y, int z, int emittedLight) {}

    private final ConcurrentHashMap<SectionKey, SectionLightData> sections = new ConcurrentHashMap<>();
    private final Set<SectionKey> dirty = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<WorldBFSWorker> worldWorkers = ThreadLocal.withInitial(WorldBFSWorker::new);
    private final ConcurrentLinkedQueue<PendingChange> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile ServerWorld pendingWorld;

    public SectionLightData getOrCreate(SectionKey key) {
        return sections.computeIfAbsent(key, k -> new SectionLightData());
    }

    public void markDirty(SectionKey key) { dirty.add(key); }

    @Override
    public int getLightLevel(BlockPos pos, LightType type) {
        if (!pending.isEmpty()) flushPending();
        SectionKey k = keyOf(pos);
        SectionLightData data = sections.get(k);
        if (data == null) return 0;
        int idx = MortonIndex.encode(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        return type == LightType.BLOCK ? data.block.get(idx) : data.sky.get(idx);
    }

    /**
     * World-space {@link WorldBFSWorker.SectionAccess} that reads opacity from
     * the live world (so freshly-loaded sections need no preseeded opaque[])
     * and reads/writes block-light through this engine's section storage.
     */
    public WorldBFSWorker.SectionAccess blockLightAccess(ServerWorld world) {
        return new WorldBFSWorker.SectionAccess() {
            private final BlockPos.Mutable scratch = new BlockPos.Mutable();

            @Override
            public boolean isOpaque(int x, int y, int z) {
                scratch.set(x, y, z);
                return world.getBlockState(scratch).isOpaqueFullCube();
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
        };
    }

    /**
     * Mirror of {@link #blockLightAccess(ServerWorld)} but read/writes the
     * SKY storage. Opacity source is identical (live world). Used for the
     * sky-light column rebuild + horizontal BFS.
     */
    public WorldBFSWorker.SectionAccess skyLightAccess(ServerWorld world) {
        return new WorldBFSWorker.SectionAccess() {
            private final BlockPos.Mutable scratch = new BlockPos.Mutable();

            @Override
            public boolean isOpaque(int x, int y, int z) {
                scratch.set(x, y, z);
                return world.getBlockState(scratch).isOpaqueFullCube();
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
        };
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
        // NOTE: sky-light recompute disabled in onBlockChanged for v0.1.
        // v0.2: incremental sky updates only when heightmap actually changed.
    }

    /**
     * Drains the pending queue and runs a single combined BFS pass over all
     * recorded changes. Removals (emittedLight == 0) trigger a simple-correct
     * darkening path: clear a 15-cube around each removal, then re-seed from
     * emitters inside the cube plus vanilla-truth boundary light.
     */
    public void flushPending() {
        if (pending.isEmpty()) return;
        if (!flushing.compareAndSet(false, true)) return;
        try {
            ServerWorld world = pendingWorld;
            if (world == null) { pending.clear(); return; }

            WorldBFSWorker w = worldWorkers.get();
            WorldBFSWorker.SectionAccess access = blockLightAccess(world);

            java.util.List<PendingChange> removals = new java.util.ArrayList<>();
            PendingChange p;
            while ((p = pending.poll()) != null) {
                if (p.emittedLight() > 0) {
                    w.seed(access, p.x(), p.y(), p.z(), p.emittedLight());
                } else {
                    removals.add(p);
                }
            }

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

            // Single combined BFS over all seeds
            w.propagate(access);
        } finally {
            flushing.set(false);
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
    public void recomputeSkyForColumn(ServerWorld world, int x, int z) {
        int worldTop = world.getTopYInclusive() + 1; // exclusive upper bound
        int worldBottom = world.getBottomY();

        // Query topY (vanilla heightmap): first y at-or-above which the column is open sky.
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);

        // Step 1: clear sky storage in the column over the world height range.
        for (int y = worldBottom; y < worldTop; y++) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = sections.get(k);
            if (d == null) continue;
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            d.sky.set(idx, 0);
        }

        // Step 2: directly write sky=15 to every cell at-or-above topY. Open-sky
        // cells are all 15 in vanilla — no attenuation between them — so writing
        // them directly is both correct and far cheaper than seeding-then-BFS.
        for (int y = topY; y < worldTop; y++) {
            SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
            SectionLightData d = getOrCreate(k);
            int idx = MortonIndex.encode(x & 15, y & 15, z & 15);
            d.sky.set(idx, 15);
            dirty.add(k);
        }

        // Step 3: seed BFS at the bottom open-sky cell so attenuation reaches
        // sideways into any shadowed neighbour column. Only one seed per column
        // is needed — neighbours seeded by their own column-rebuild loop pick up
        // the work; horizontal interactions converge naturally.
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
