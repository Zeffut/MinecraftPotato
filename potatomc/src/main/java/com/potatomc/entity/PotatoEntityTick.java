package com.potatomc.entity;

import com.potatomc.PotatoMC;

/**
 * Entity AI tick skip module entry point. Actual work is performed by
 * {@code MobEntityMixin} which consults {@link DistantEntityTracker}.
 */
public final class PotatoEntityTick {
    private static long skippedTicks = 0L;
    private static long totalTicks = 0L;

    private PotatoEntityTick() {}

    public static void init() {
        EntityTickSkipGuard.evaluate();
        if (EntityTickSkipGuard.isActive()) {
            PotatoMC.LOGGER.info("[PotatoMC] Entity module — distant-mob AI tick skip armed");
        }
    }

    public static void recordTick(boolean skipped) {
        totalTicks++;
        if (skipped) skippedTicks++;
    }

    public static long getSkippedTicks() { return skippedTicks; }
    public static long getTotalTicks() { return totalTicks; }
}
