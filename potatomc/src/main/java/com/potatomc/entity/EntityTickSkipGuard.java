package com.potatomc.entity;

import com.potatomc.PotatoMC;

/**
 * Kill-switch for the entity AI tick skip module. Honors:
 * <ul>
 *   <li>{@code -Dpotatomc.disabled=true} — disables everything (alias)</li>
 *   <li>{@code -Dpotatomc.entitytick.disabled=true} — disables entity tick skip only</li>
 * </ul>
 *
 * <p>Evaluated once from {@link PotatoEntityTick#init()}; the
 * {@code MobEntityMixin} consults {@link #isActive()} on every {@code mobTick}.</p>
 */
public final class EntityTickSkipGuard {
    // Opt-in (default OFF). Headless 200-mob bench showed regression: skipping
    // mobTick keeps mobs alive longer (despawn check lives there), and the
    // higher entity count outweighed the AI savings. Set the property to opt in.
    private static volatile boolean active = false;
    private static boolean evaluated = false;

    private EntityTickSkipGuard() {}

    public static void evaluate() {
        if (evaluated) return;
        if ("true".equalsIgnoreCase(System.getProperty("potatomc.disabled"))) {
            active = false;
            PotatoMC.LOGGER.warn("[PotatoMC] entity tick skip module disabled via kill-switch");
        } else if ("true".equalsIgnoreCase(System.getProperty("potatomc.entitytick.enabled"))) {
            active = true;
            PotatoMC.LOGGER.info("[PotatoMC] entity tick skip module: actif (opt-in, skip AI > 32 blocks from any player)");
        } else {
            active = false;
            PotatoMC.LOGGER.info("[PotatoMC] entity tick skip module: opt-in (set -Dpotatomc.entitytick.enabled=true to enable)");
        }
        evaluated = true;
    }

    public static boolean isActive() { return active; }
}
