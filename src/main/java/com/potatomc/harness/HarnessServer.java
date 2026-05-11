package com.potatomc.harness;

import com.potatomc.PotatoMC;
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

    private HarnessServer() {}

    public static void startIfEnabled() {
        if (!"true".equals(System.getProperty("potatomc.dev"))) {
            PotatoMC.LOGGER.info("[harness] dev flag absent — HTTP harness disabled");
            return;
        }
        if (http != null) return;
        int port = Integer.parseInt(System.getProperty("potatomc.harness.port", "25585"));
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            http.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "potato-harness-http");
                t.setDaemon(true);
                return t;
            }));
            register("/health", HarnessServer::handleHealth);
            HarnessHandlers.bindAll(HarnessServer::register);
            http.start();
            PotatoMC.LOGGER.info("[harness] listening on 127.0.0.1:{}", port);
        } catch (IOException e) {
            PotatoMC.LOGGER.error("[harness] failed to start", e);
        }
    }

    public static void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
            PotatoMC.LOGGER.info("[harness] stopped");
        }
    }

    private static void register(String path, HttpHandler h) {
        http.createContext(path, wrap(h));
    }

    private static HttpHandler wrap(HttpHandler inner) {
        return exchange -> {
            try {
                inner.handle(exchange);
            } catch (Exception e) {
                PotatoMC.LOGGER.error("[harness] handler error on " + exchange.getRequestURI(), e);
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
        body.put("mod_version", "0.1.0");
        body.put("server_ready", ServerHolder.isReady());
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
