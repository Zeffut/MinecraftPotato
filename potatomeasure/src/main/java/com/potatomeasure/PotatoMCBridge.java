package com.potatomeasure;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reflection-based optional bridge to PotatoMC. PotatoMeasure does not import
 * any com.potatomc.* class at compile time so it can be installed standalone
 * (e.g. to bench Starlight or vanilla without PotatoMC).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class PotatoMCBridge {

    private static final Logger LOG = LoggerFactory.getLogger("potatomeasure-bridge");

    private static volatile boolean resolved;
    private static volatile boolean present;
    private static volatile Object engineInstance;
    private static volatile Method getLightLevel;
    private static volatile Object lightTypeBlock;
    private static volatile Object lightTypeSky;
    private static volatile Method runBypassed;
    private static volatile Method differentialRunAround;
    private static volatile Method engineTrackedSections;
    private static volatile Method compatIsActive;

    private PotatoMCBridge() {}

    public static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded("potatomc")) {
            LOG.info("[potatomeasure] PotatoMC absent — running in vanilla-bench mode");
            return;
        }
        try {
            Class<?> potatoMC = Class.forName("com.potatomc.PotatoMC");
            Field engineField = potatoMC.getField("LIGHT_ENGINE");
            engineInstance = engineField.get(null);

            Class<?> lightLevelAPI = Class.forName("com.potatomc.lighting.api.LightLevelAPI");
            Class<?> lightType = Class.forName("com.potatomc.lighting.api.LightLevelAPI$LightType");
            getLightLevel = lightLevelAPI.getMethod("getLightLevel", BlockPos.class, lightType);
            lightTypeBlock = Enum.valueOf((Class<Enum>) lightType, "BLOCK");
            lightTypeSky = Enum.valueOf((Class<Enum>) lightType, "SKY");

            Class<?> engineHolder = Class.forName("com.potatomc.lighting.bridge.EngineHolder");
            runBypassed = engineHolder.getMethod("runBypassed", Runnable.class);

            Class<?> compatGuard = Class.forName("com.potatomc.lighting.CompatGuard");
            compatIsActive = compatGuard.getMethod("isActive");

            Class<?> diffValidator = Class.forName("com.potatomc.debug.DifferentialValidator");
            differentialRunAround = diffValidator.getMethod(
                "runAround",
                Class.forName("net.minecraft.server.world.ServerWorld"),
                Class.forName("net.minecraft.util.math.Vec3d"),
                int.class);

            Class<?> engineClass = engineInstance.getClass();
            engineTrackedSections = engineClass.getMethod("trackedSectionsCount");

            present = true;
            LOG.info("[potatomeasure] PotatoMC engine bridged via reflection");
        } catch (Throwable t) {
            LOG.warn("[potatomeasure] PotatoMC detected but bridge failed: {}", t.getMessage());
            present = false;
        }
    }

    public static boolean isPresent() {
        if (!resolved) resolve();
        return present;
    }

    public static int getBlockLight(BlockPos pos) {
        if (!isPresent()) return 0;
        try { return (int) getLightLevel.invoke(engineInstance, pos, lightTypeBlock); }
        catch (Throwable t) { return 0; }
    }

    public static int getSkyLight(BlockPos pos) {
        if (!isPresent()) return 0;
        try { return (int) getLightLevel.invoke(engineInstance, pos, lightTypeSky); }
        catch (Throwable t) { return 0; }
    }

    public static void runBypassed(Runnable r) {
        if (!isPresent()) { r.run(); return; }
        try { runBypassed.invoke(null, r); }
        catch (Throwable t) { r.run(); }
    }

    public static boolean engineActive() {
        if (!isPresent()) return false;
        try { return (boolean) compatIsActive.invoke(null); }
        catch (Throwable t) { return false; }
    }

    public static int trackedSections() {
        if (!isPresent()) return 0;
        try { return (int) engineTrackedSections.invoke(engineInstance); }
        catch (Throwable t) { return 0; }
    }

    /**
     * Runs DifferentialValidator.runAround via reflection and unpacks the Report record.
     * Returns null if PotatoMC is not present or call failed.
     * Array layout: [totalBlocks, diffCount, maxDelta, blockDiffs, blockMaxDelta, skyDiffs, skyMaxDelta]
     */
    /**
     * Reflectively reads {@code PotatoLightEngine.flushStats} LongAdder fields
     * and returns a snapshot map. Empty map if PotatoMC isn't loaded or
     * reflection fails (shape stable so callers can rely on key presence).
     */
    public static Map<String, Long> flushStatsSnapshot() {
        if (!isPresent()) return Collections.emptyMap();
        try {
            Object stats = engineInstance.getClass().getField("flushStats").get(engineInstance);
            Class<?> sc = stats.getClass();
            Map<String, Long> out = new LinkedHashMap<>();
            for (String f : new String[]{
                "blockSeedNs", "blockPropagateNs", "removalPhaseNs",
                "skyIncrementalNs", "skyFullColumnNs", "flushCount", "pendingDrainedTotal"
            }) {
                Object adder = sc.getField(f).get(stats);
                out.put(f, (long) adder.getClass().getMethod("sum").invoke(adder));
            }
            return out;
        } catch (Throwable t) { return Collections.emptyMap(); }
    }

    /**
     * Reflectively reads {@code PropertyMapInterner} counters and returns a
     * snapshot map. Empty if PotatoMC isn't loaded or reflection fails.
     */
    public static Map<String, Long> memoryStatsSnapshot() {
        if (!isPresent()) return Collections.emptyMap();
        try {
            Class<?> interner = Class.forName("com.potatomc.memory.dedup.PropertyMapInterner");
            Map<String, Long> out = new LinkedHashMap<>();
            out.put("property_maps_interned", (long) interner.getMethod("internedCount").invoke(null));
            out.put("property_lookups", (long) interner.getMethod("lookupsCount").invoke(null));
            out.put("property_hits", (long) interner.getMethod("hitsCount").invoke(null));
            out.put("property_bytes_saved_estimate", (long) interner.getMethod("estimatedBytesSaved").invoke(null));
            return out;
        } catch (Throwable t) { return Collections.emptyMap(); }
    }

    public static int[] validate(Object serverWorld, Object center, int radius) {
        if (!isPresent()) return null;
        try {
            Object report = differentialRunAround.invoke(null, serverWorld, center, radius);
            Class<?> reportClass = report.getClass();
            int total = (int) reportClass.getMethod("totalBlocks").invoke(report);
            int diffs = (int) reportClass.getMethod("diffCount").invoke(report);
            int maxDelta = (int) reportClass.getMethod("maxDelta").invoke(report);
            int blockDiffs = (int) reportClass.getMethod("blockDiffs").invoke(report);
            int blockMaxDelta = (int) reportClass.getMethod("blockMaxDelta").invoke(report);
            int skyDiffs = (int) reportClass.getMethod("skyDiffs").invoke(report);
            int skyMaxDelta = (int) reportClass.getMethod("skyMaxDelta").invoke(report);
            return new int[]{ total, diffs, maxDelta, blockDiffs, blockMaxDelta, skyDiffs, skyMaxDelta };
        } catch (Throwable t) { return null; }
    }
}
