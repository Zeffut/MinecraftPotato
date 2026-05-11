package com.potatomc.lighting.bridge;

import com.potatomc.lighting.PotatoLightEngine;

public final class EngineHolder {

    private static volatile PotatoLightEngine engine;
    private static final ThreadLocal<Boolean> bypass = ThreadLocal.withInitial(() -> false);

    private EngineHolder() {}

    public static void set(PotatoLightEngine e) { engine = e; }
    public static PotatoLightEngine get() { return bypass.get() ? null : engine; }

    public static void runBypassed(Runnable task) {
        bypass.set(true);
        try { task.run(); } finally { bypass.set(false); }
    }
}
