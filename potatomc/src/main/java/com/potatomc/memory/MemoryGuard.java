package com.potatomc.memory;

import com.potatomc.PotatoMC;

/**
 * Kill-switch for the memory module. Honors:
 * <ul>
 *   <li>{@code -Dpotatomc.disabled=true} — disables everything (alias)</li>
 *   <li>{@code -Dpotatomc.memory.disabled=true} — disables memory wholesale</li>
 *   <li>{@code -Dpotatomc.memory.nibble.enabled=true} — opt-in for
 *       NibbleArrayInterner (off by default; canonical arrays are mutably
 *       shared and unsafe with vanilla in-place mutation).</li>
 * </ul>
 *
 * <p>Evaluated once from {@link PotatoMemory#init()}; mixins consult the
 * relevant getter on every interception.</p>
 */
public final class MemoryGuard {
    private static volatile boolean active = true;
    private static volatile boolean nibbleActive = false;
    private static boolean evaluated = false;

    private MemoryGuard() {}

    public static void evaluate() {
        if (evaluated) return;
        if ("true".equalsIgnoreCase(System.getProperty("potatomc.disabled"))
                || "true".equalsIgnoreCase(System.getProperty("potatomc.memory.disabled"))) {
            active = false;
            PotatoMC.LOGGER.warn("[PotatoMC] memory module disabled via kill-switch");
        } else {
            PotatoMC.LOGGER.info("[PotatoMC] memory module: actif");
        }
        if (active && "true".equalsIgnoreCase(System.getProperty("potatomc.memory.nibble.enabled"))) {
            nibbleActive = true;
            PotatoMC.LOGGER.warn("[PotatoMC] NibbleArrayInterner: ENABLED (opt-in, experimental, may break light data)");
        }
        evaluated = true;
    }

    public static boolean isActive() { return active; }
    public static boolean isNibbleActive() { return active && nibbleActive; }
}
