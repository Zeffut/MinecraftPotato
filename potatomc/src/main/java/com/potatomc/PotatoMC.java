package com.potatomc;

import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PotatoMC implements ModInitializer {
    public static final String MOD_ID = "potatomc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final PotatoLightEngine LIGHT_ENGINE = new PotatoLightEngine();

    @Override
    public void onInitialize() {
        LOGGER.info("[PotatoMC] Initialisation — optimisation extrême activée");
        CompatGuard.evaluate();
        com.potatomc.memory.PotatoMemory.init();
        if (CompatGuard.isActive()) {
            com.potatomc.lighting.bridge.EngineHolder.set(LIGHT_ENGINE);
            // Deferred batching: drain pending block-light changes once per tick.
            // Reads also trigger a synchronous flush (see PotatoLightEngine.getLightLevel).
            ServerTickEvents.END_SERVER_TICK.register(server -> LIGHT_ENGINE.flushPending());
            // NOTE: aggressive CHUNK_LOAD pre-population disabled — caused server boot to hang
            // on spawn-area generation. Sky-light is now lazy: populated on first block change.
            // Tracked for v0.2.
        }
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, env) ->
                com.potatomc.debug.commands.PotatoMCCommand.register(dispatcher)
        );
    }
}
