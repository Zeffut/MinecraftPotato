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
            // Pre-populate sky-light per column when a chunk loads on the server,
            // so reads succeed even without a preceding block change.
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
                net.minecraft.util.math.ChunkPos cp = chunk.getPos();
                int startX = cp.getStartX();
                int startZ = cp.getStartZ();
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        LIGHT_ENGINE.recomputeSkyForColumn(world, startX + dx, startZ + dz);
                    }
                }
            });
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
