package com.potatomc.lighting;

import com.potatomc.lighting.api.LightLevelAPI;
import com.potatomc.lighting.propagation.WorldBFSWorker;
import com.potatomc.lighting.storage.MortonIndex;
import com.potatomc.lighting.storage.PackedLightStorage;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

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

    public void onBlockChanged(ServerWorld world, BlockPos pos, int emittedLight) {
        WorldBFSWorker w = worldWorkers.get();
        WorldBFSWorker.SectionAccess access = blockLightAccess(world);
        w.seed(access, pos.getX(), pos.getY(), pos.getZ(), emittedLight);
        w.propagate(access);
    }

    public void tick() {
        dirty.clear();
    }

    public int trackedSectionsCount() { return sections.size(); }

    private static SectionKey keyOf(BlockPos pos) {
        return new SectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
}
