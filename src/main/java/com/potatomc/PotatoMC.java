package com.potatomc;

import com.potatomc.harness.HarnessServer;
import com.potatomc.harness.ServerHolder;
import com.potatomc.lighting.CompatGuard;
import com.potatomc.lighting.PotatoLightEngine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
        if (CompatGuard.isActive()) {
            com.potatomc.lighting.bridge.EngineHolder.set(LIGHT_ENGINE);
            // Deferred batching: drain pending block-light changes once per tick.
            // Reads also trigger a synchronous flush (see PotatoLightEngine.getLightLevel).
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> LIGHT_ENGINE.flushPending()
            );
            // NOTE: aggressive CHUNK_LOAD pre-population disabled — caused server boot to hang
            // on spawn-area generation (~256 columns × 25 chunks × full-height BFS each).
            // Sky-light is now lazy: populated on the first block change in each column.
            // Cells never touched by setBlockState return potato_sky=0 (vanilla differs).
            // Proper fix: incremental sky-light updates only on opacity change in a column.
            // Tracked for v0.2.
        }
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, env) ->
                com.potatomc.debug.commands.PotatoMCCommand.register(dispatcher)
        );
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerHolder.set(server);
            HarnessServer.startIfEnabled();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            HarnessServer.stop();
            ServerHolder.clear();
        });
    }
}
