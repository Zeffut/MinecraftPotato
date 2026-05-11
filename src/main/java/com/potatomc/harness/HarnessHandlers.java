package com.potatomc.harness;

import com.sun.net.httpserver.HttpHandler;

import java.util.function.BiConsumer;

public final class HarnessHandlers {

    private HarnessHandlers() {}

    public static void bindAll(BiConsumer<String, HttpHandler> bind) {
        // /cmd and /light registered in later tasks
    }
}
