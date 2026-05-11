package com.potatomeasure.harness;

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
        bind.accept("/stats", HarnessHandlers::handleStats);
        bind.accept("/validate", HarnessHandlers::handleValidate);
        bind.accept("/shutdown", HarnessHandlers::handleShutdown);
        bind.accept("/bench/micro", HarnessHandlers::handleBenchMicro);
    }

    private static void handleBenchMicro(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HarnessServer.respond(ex, 405, "{\"error\":\"POST required\"}");
            return;
        }
        if (!ServerHolder.isReady()) {
            HarnessServer.respond(ex, 503, "{\"error\":\"server not ready\"}");
            return;
        }
        Map<String, Object> req = Json.parseObject(HarnessServer.readBody(ex));
        Object wObj = req.get("workload");
        String workloadName = wObj instanceof String s ? s : "single_block_update";
        int iterations = req.get("iterations") instanceof Number n ? n.intValue() : 1000;
        long seed = req.get("seed") instanceof Number n ? n.longValue() : 42L;

        com.potatomeasure.bench.Microbench.Workload wl;
        try {
            wl = com.potatomeasure.bench.Microbench.Workload.valueOf(workloadName.toUpperCase());
        } catch (Exception e) {
            HarnessServer.respond(ex, 400, "{\"error\":\"unknown workload: " + workloadName + "\"}");
            return;
        }

        try {
            final com.potatomeasure.bench.Microbench.Workload fwl = wl;
            final int fIters = iterations;
            final long fSeed = seed;
            final String fName = workloadName;
            Map<String, Object> body = ServerHolder.submitAndWait(() -> {
                ServerWorld world = ServerHolder.get().getOverworld();
                var rPotato = com.potatomeasure.bench.Microbench.run(world, fwl,
                    com.potatomeasure.bench.Microbench.Engine.POTATO, fIters, fSeed);
                var rVanilla = com.potatomeasure.bench.Microbench.run(world, fwl,
                    com.potatomeasure.bench.Microbench.Engine.VANILLA, fIters, fSeed);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("workload", fName);
                out.put("iterations", fIters);
                out.put("seed", fSeed);
                out.put("potato", com.potatomeasure.bench.Microbench.resultToJson(rPotato));
                out.put("vanilla", com.potatomeasure.bench.Microbench.resultToJson(rVanilla));
                double speedup = rPotato.totalNanos() > 0
                    ? (double) rVanilla.totalNanos() / (double) rPotato.totalNanos()
                    : 0.0;
                out.put("speedup_potato_over_vanilla", speedup);
                return out;
            }, 300_000);
            HarnessServer.respond(ex, 200, Json.write(body));
        } catch (Exception e) {
            HarnessServer.respond(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
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
                int[] vanilla = new int[2];
                com.potatomeasure.PotatoMCBridge.runBypassed(() -> {
                    vanilla[0] = world.getLightLevel(LightType.BLOCK, pos);
                    vanilla[1] = world.getLightLevel(LightType.SKY, pos);
                });
                int pBlock = com.potatomeasure.PotatoMCBridge.getBlockLight(pos);
                int pSky = com.potatomeasure.PotatoMCBridge.getSkyLight(pos);
                int mBlock = world.getLightLevel(LightType.BLOCK, pos);
                int mSky = world.getLightLevel(LightType.SKY, pos);
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("pos", java.util.List.of(x, y, z));
                b.put("vanilla_block", vanilla[0]);
                b.put("vanilla_sky", vanilla[1]);
                b.put("potato_block", pBlock);
                b.put("potato_sky", pSky);
                b.put("mixed_block", mBlock);
                b.put("mixed_sky", mSky);
                b.put("match", mBlock == pBlock && mSky == pSky);
                b.put("engine_active", com.potatomeasure.PotatoMCBridge.engineActive());
                return b;
            }, 5000);
            HarnessServer.respond(ex, 200, Json.write(body));
        } catch (Exception e) {
            HarnessServer.respond(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static void handleStats(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            HarnessServer.respond(ex, 405, "{\"error\":\"GET required\"}");
            return;
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("engine_active", com.potatomeasure.PotatoMCBridge.engineActive());
        b.put("sections_tracked", com.potatomeasure.PotatoMCBridge.trackedSections());
        b.put("server_ready", ServerHolder.isReady());
        b.put("potatomc_present", com.potatomeasure.PotatoMCBridge.isPresent());
        HarnessServer.respond(ex, 200, Json.write(b));
    }

    private static void handleValidate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HarnessServer.respond(ex, 405, "{\"error\":\"POST required\"}");
            return;
        }
        if (!ServerHolder.isReady()) {
            HarnessServer.respond(ex, 503, "{\"error\":\"server not ready\"}");
            return;
        }
        Map<String, Object> req = Json.parseObject(HarnessServer.readBody(ex));
        Object centerObj = req.get("center");
        Object radiusObj = req.get("radius");
        if (!(centerObj instanceof java.util.List<?> centerList) || centerList.size() != 3) {
            HarnessServer.respond(ex, 400, "{\"error\":\"center must be [x,y,z]\"}");
            return;
        }
        if (!(radiusObj instanceof Number)) {
            HarnessServer.respond(ex, 400, "{\"error\":\"radius must be int\"}");
            return;
        }
        int radius = ((Number) radiusObj).intValue();
        if (radius < 1 || radius > 16) {
            HarnessServer.respond(ex, 400, "{\"error\":\"radius must be 1..16\"}");
            return;
        }
        double cx = ((Number) centerList.get(0)).doubleValue();
        double cy = ((Number) centerList.get(1)).doubleValue();
        double cz = ((Number) centerList.get(2)).doubleValue();

        try {
            Map<String, Object> body = ServerHolder.submitAndWait(() -> {
                ServerWorld world = ServerHolder.get().getOverworld();
                int[] r = com.potatomeasure.PotatoMCBridge.validate(
                    world, new net.minecraft.util.math.Vec3d(cx, cy, cz), radius);
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("center", java.util.List.of(cx, cy, cz));
                b.put("radius", radius);
                if (r == null) {
                    b.put("error", "potatomc not present — validator unavailable");
                    b.put("pass", false);
                    return b;
                }
                // r = [totalBlocks, diffCount, maxDelta, blockDiffs, blockMaxDelta, skyDiffs, skyMaxDelta]
                b.put("total_blocks", r[0]);
                b.put("diff_count", r[1]);
                b.put("max_delta", r[2]);
                b.put("block_diffs", r[3]);
                b.put("block_max_delta", r[4]);
                b.put("sky_diffs", r[5]);
                b.put("sky_max_delta", r[6]);
                b.put("pass", r[2] <= 1);
                return b;
            }, 60000);
            HarnessServer.respond(ex, 200, Json.write(body));
        } catch (Exception e) {
            HarnessServer.respond(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
