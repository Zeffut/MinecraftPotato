package com.potatomeasure;

import com.potatomeasure.harness.HarnessServer;
import com.potatomeasure.harness.ServerHolder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PotatoMeasure implements ModInitializer {
    public static final String MOD_ID = "potatomeasure";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[PotatoMeasure] Initialisation — outil de test harness");
        PotatoMCBridge.resolve();
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
