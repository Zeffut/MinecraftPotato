package com.potatomc.memory;

import com.potatomc.PotatoMC;

/**
 * Kill-switch for the memory module (PropertyMapInterner). Honors:
 * <ul>
 *   <li>{@code -Dpotatomc.disabled=true} — disables everything (alias)</li>
 *   <li>{@code -Dpotatomc.memory.disabled=true} — disables memory only</li>
 * </ul>
 *
 * <p>Evaluated once from {@link PotatoMemory#init()}; the {@code StateMixin}
 * consults {@link #isActive()} on every constructor invocation.</p>
 */
public final class MemoryGuard {
    private static volatile boolean active = true;
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
        evaluated = true;
    }

    public static boolean isActive() { return active; }
}
