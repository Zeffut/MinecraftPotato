package com.potatomc.harness;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
        // Fast path: avoid self-submit deadlock when already on the server thread.
        if (s.isOnThread()) {
            return task.get();
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        s.execute(() -> {
            try { f.complete(task.get()); }
            catch (Throwable t) { f.completeExceptionally(t); }
        });
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("server task timeout after " + timeoutMs + "ms", e);
        }
    }
}
