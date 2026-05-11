package com.potatomc.lighting;

import com.potatomc.PotatoMC;
import net.fabricmc.loader.api.FabricLoader;

public final class CompatGuard {

    private static final String[] CONFLICTING_MODS = {"starlight", "phosphor"};
    private static volatile boolean active = true;
    private static boolean evaluated = false;

    private CompatGuard() {}

    public static void evaluate() {
        if (evaluated) return;
        boolean killSwitch = "true".equalsIgnoreCase(System.getProperty("potatomc.disabled"))
                || "true".equalsIgnoreCase(System.getProperty("potatomc.lighting.disabled"));
        boolean optIn = "true".equalsIgnoreCase(System.getProperty("potatomc.lighting.experimental"));
        if (killSwitch || !optIn) {
            active = false;
            if (killSwitch) {
                PotatoMC.LOGGER.warn("[PotatoMC] lighting module disabled via kill-switch");
            } else {
                PotatoMC.LOGGER.info("[PotatoMC] lighting module OFF by default (experimental). Set -Dpotatomc.lighting.experimental=true to enable.");
            }
            evaluated = true;
            return;
        }
        FabricLoader fl = FabricLoader.getInstance();
        for (String id : CONFLICTING_MODS) {
            if (fl.isModLoaded(id)) {
                active = false;
                PotatoMC.LOGGER.warn("[PotatoMC] Mod '{}' détecté — moteur lumière custom désactivé (compat)", id);
            }
        }
        if (active) PotatoMC.LOGGER.info("[PotatoMC] lighting module: ACTIVE (experimental opt-in)");
        evaluated = true;
    }

    public static boolean isActive() { return active; }
}
