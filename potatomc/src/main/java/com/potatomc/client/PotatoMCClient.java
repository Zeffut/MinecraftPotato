package com.potatomc.client;

import com.potatomc.PotatoMC;
import net.fabricmc.api.ClientModInitializer;

public final class PotatoMCClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PotatoMC.LOGGER.info("[PotatoMC] Client init — pipeline de rendu patate prêt");
    }
}
