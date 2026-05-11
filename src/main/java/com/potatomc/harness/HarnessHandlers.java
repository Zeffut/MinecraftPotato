package com.potatomc.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public final class HarnessHandlers {

    private HarnessHandlers() {}

    public static void bindAll(BiConsumer<String, HttpHandler> bind) {
        bind.accept("/cmd", HarnessHandlers::handleCmd);
        bind.accept("/light/", HarnessHandlers::handleLight);
        bind.accept("/shutdown", HarnessHandlers::handleShutdown);
    }

    private static void handleShutdown(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HarnessServer.respond(ex, 405, "{\"error\":\"POST required\"}");
            return;
        }
        if (!ServerHolder.isReady()) {
            HarnessServer.respond(ex, 503, "{\"error\":\"server not ready\"}");
            return;
        }
        HarnessServer.respond(ex, 200, "{\"shutdown\":true}");
        // Stop AFTER replying — schedule on server thread.
        var server = ServerHolder.get();
        server.execute(() -> server.stop(false));
    }

    private static void handleCmd(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HarnessServer.respond(ex, 405, "{\"error\":\"POST required\"}");
            return;
        }
        if (!ServerHolder.isReady()) {
            HarnessServer.respond(ex, 503, "{\"error\":\"server not ready\"}");
            return;
        }
        Map<String, Object> req = Json.parseObject(HarnessServer.readBody(ex));
        Object cmd = req.get("command");
        if (!(cmd instanceof String s) || s.isBlank()) {
            HarnessServer.respond(ex, 400, "{\"error\":\"missing or empty 'command'\"}");
            return;
        }
        try {
            ServerHolder.submitAndWait(() -> {
                MinecraftServer server = ServerHolder.get();
                ServerCommandSource src = server.getCommandSource();
                server.getCommandManager().parseAndExecute(src, s);
                return 0;
            }, 5000);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("command", s);
            HarnessServer.respond(ex, 200, Json.write(body));
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", e.getMessage());
            HarnessServer.respond(ex, 500, Json.write(body));
        }
    }

    private static void handleLight(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            HarnessServer.respond(ex, 405, "{\"error\":\"GET required\"}");
            return;
        }
        if (!ServerHolder.isReady()) {
            HarnessServer.respond(ex, 503, "{\"error\":\"server not ready\"}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 5) {
            HarnessServer.respond(ex, 400, "{\"error\":\"expected /light/X/Y/Z\"}");
            return;
        }
        int x, y, z;
        try {
            x = Integer.parseInt(parts[2]);
            y = Integer.parseInt(parts[3]);
            z = Integer.parseInt(parts[4]);
        } catch (NumberFormatException nfe) {
            HarnessServer.respond(ex, 400, "{\"error\":\"X/Y/Z must be integers\"}");
            return;
        }
        try {
            Map<String, Object> body = ServerHolder.submitAndWait(() -> {
                MinecraftServer server = ServerHolder.get();
                ServerWorld world = server.getOverworld();
                BlockPos pos = new BlockPos(x, y, z);
                var lp = world.getLightingProvider();
                int vBlock = lp.get(LightType.BLOCK).getLightLevel(pos);
                int vSky = lp.get(LightType.SKY).getLightLevel(pos);
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("pos", java.util.List.of(x, y, z));
                b.put("vanilla_block", vBlock);
                b.put("vanilla_sky", vSky);
                b.put("potato_block", vBlock);
                b.put("potato_sky", vSky);
                b.put("match", true);
                return b;
            }, 5000);
            HarnessServer.respond(ex, 200, Json.write(body));
        } catch (Exception e) {
            HarnessServer.respond(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
