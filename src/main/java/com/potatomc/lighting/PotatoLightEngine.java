package com.potatomc.lighting;

import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.propagation.WorldBFSWorker;
import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PotatoLightEngine implements LightLevelAPI {

    public record SectionKey(int sx, int sy, int sz) {}

    public static final class SectionLightData {
        public final PackedLightStorage block = new PackedLightStorage();
        public final PackedLightStorage sky = new PackedLightStorage();
        public final boolean[] opaque = new boolean[PackedLightStorage.SECTION_SIZE];
    }

    private final ConcurrentHashMap<SectionKey, SectionLightData> sections = new ConcurrentHashMap<>();
    private final Set<SectionKey> dirty = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<WorldBFSWorker> worldWorkers = ThreadLocal.withInitial(WorldBFSWorker::new);

    public SectionLightData getOrCreate(SectionKey key) {
        return sections.computeIfAbsent(key, k -> new SectionLightData());
    }

    public void markDirty(SectionKey key) { dirty.add(key); }

    @Override
    public int getLightLevel(BlockPos pos, LightType type) {
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

    public void onBlockChanged(ServerWorld world, BlockPos pos, int emittedLight) {
        // Block light: seed at the modified position and BFS-propagate.
        WorldBFSWorker w = worldWorkers.get();
        WorldBFSWorker.SectionAccess access = blockLightAccess(world);
        w.seed(access, pos.getX(), pos.getY(), pos.getZ(), emittedLight);
        w.propagate(access);
        // NOTE: sky-light recompute disabled in onBlockChanged for v0.1.
        // Full-column rebuild × thousands of setBlockState during world gen → boot hang.
        // `recomputeSkyForColumn(world, pos.getX(), pos.getZ())` is intentionally NOT called here.
        // The method exists and is correct; callers should invoke it explicitly on demand
        // (e.g. via a /potatomc sky <x> <z> command). v0.2: incremental sky updates only
        // when the column's heightmap actually changed.
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
