package com.potatomc;

import com.potatomc.harness.HarnessServer;
import com.potatomc.harness.ServerHolder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PotatoMC implements ModInitializer {
    public static final String MOD_ID = "potatomc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[PotatoMC] Initialisation — optimisation extrême activée");
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
