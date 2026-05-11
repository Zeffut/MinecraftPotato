package com.potatomc.harness;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class ServerHolder {

    private static volatile MinecraftServer server;

    private ServerHolder() {}

    public static void set(MinecraftServer s) { server = s; }
    public static void clear() { server = null; }
    public static MinecraftServer get() { return server; }
    public static boolean isReady() { return server != null; }

    /** Executes {@code task} on the main server thread, blocks up to {@code timeoutMs} for the result. */
    public static <T> T submitAndWait(Supplier<T> task, long timeoutMs) throws Exception {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("no server");
        CompletableFuture<T> f = new CompletableFuture<>();
        s.execute(() -> {
            try { f.complete(task.get()); }
            catch (Throwable t) { f.completeExceptionally(t); }
        });
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("server task timeout after " + timeoutMs + "ms", e);
        }
    }
}
