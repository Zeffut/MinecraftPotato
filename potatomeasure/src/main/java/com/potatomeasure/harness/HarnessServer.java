package com.potatomeasure.harness;

import com.potatomeasure.PotatoMeasure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class HarnessServer {

    private static volatile HttpServer http;
    private static volatile boolean prodMode = false;

    private HarnessServer() {}

    public static void startIfEnabled() {
        boolean dev = "true".equalsIgnoreCase(System.getProperty("potatomc.harness.dev"))
            || "true".equalsIgnoreCase(System.getProperty("potatomc.dev"));
        boolean prod = "true".equalsIgnoreCase(System.getProperty("potatomc.harness.prod"));
        String prodToken = System.getProperty("potatomc.harness.token");

        if (!dev && !prod) {
            PotatoMeasure.LOGGER.info("[harness] OFF (set -Dpotatomc.harness.dev=true for local dev, "
                + "or -Dpotatomc.harness.prod=true -Dpotatomc.harness.token=<TOKEN> for production)");
            return;
        }
        if (prod && (prodToken == null || prodToken.isEmpty())) {
            PotatoMeasure.LOGGER.error("[harness] PROD mode requires -Dpotatomc.harness.token=<TOKEN>");
            return;
        }
        if (http != null) return;

        prodMode = prod && !dev;
        String bind = prodMode ? "0.0.0.0" : "127.0.0.1";
        int port = Integer.parseInt(System.getProperty("potatomc.harness.port", "25585"));

        try {
            http = HttpServer.create(new InetSocketAddress(bind, port), 0);
            http.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "potato-harness-http");
                t.setDaemon(true);
                return t;
            }));
            register("/health", HarnessServer::handleHealth);
            HarnessHandlers.bindAll(HarnessServer::register);
            http.start();
            String mode = prodMode ? "PROD (0.0.0.0, token auth)" : "DEV (localhost, no auth)";
            PotatoMeasure.LOGGER.info("[harness] listening on {}:{} — mode: {}", bind, port, mode);
        } catch (IOException e) {
            PotatoMeasure.LOGGER.error("[harness] failed to start", e);
        }
    }

    public static void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
            PotatoMeasure.LOGGER.info("[harness] stopped");
        }
    }

    public static boolean isProdMode() {
        return prodMode;
    }

    private static void register(String path, HttpHandler h) {
        http.createContext(path, wrap(authWrap(h)));
    }

    private static HttpHandler authWrap(HttpHandler inner) {
        return exchange -> {
            if (prodMode) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                String expected = "Bearer " + System.getProperty("potatomc.harness.token");
                if (auth == null || !auth.equals(expected)) {
                    respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
            }
            inner.handle(exchange);
        };
    }

    private static HttpHandler wrap(HttpHandler inner) {
        return exchange -> {
            try {
                inner.handle(exchange);
            } catch (Exception e) {
                PotatoMeasure.LOGGER.error("[harness] handler error on " + exchange.getRequestURI(), e);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                respond(exchange, 500, Json.write(err));
            } finally {
                exchange.close();
            }
        };
    }

    private static void handleHealth(HttpExchange ex) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("mc_version", net.minecraft.SharedConstants.getGameVersion().name());
        body.put("mod_version", "1.0.0");
        body.put("server_ready", ServerHolder.isReady());
        body.put("mode", prodMode ? "prod" : "dev");
        long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        body.put("uptime_ms", uptimeMs);
        respond(ex, 200, Json.write(body));
    }

    public static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
