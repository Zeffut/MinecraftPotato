package com.potatomc.memory;

import com.potatomc.PotatoMC;

/**
 * Memory module entry point. The actual interning is driven by mixins that
 * call into {@link com.potatomc.memory.dedup.PropertyMapInterner}; this class
 * just logs activation so operators can confirm the module is armed.
 */
public final class PotatoMemory {
    private PotatoMemory() {}

    public static void init() {
        PotatoMC.LOGGER.info("[PotatoMC] Memory module — PropertyMapInterner armed");
    }
}
