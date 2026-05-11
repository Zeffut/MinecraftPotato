# PotatoHarness v0 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Build a dev-only HTTP control plane embedded in the PotatoMC Fabric mod, plus a Bash CLI wrapper (`scripts/pmh`), allowing subagents and CI to drive a headless Minecraft dedicated server programmatically.

**Architecture:** `com.sun.net.httpserver.HttpServer` (JDK built-in) embedded in the mod, started only when system property `potatomc.dev=true` is present. Binds `127.0.0.1:25585`. All world-touching actions are dispatched to the main server thread via `MinecraftServer.execute(Runnable)` with `CompletableFuture` results. Shell wrapper handles server lifecycle (EULA accept, start, poll-until-ready, stop) and provides ergonomic sub-commands wrapping `curl`.

**Tech Stack:** Java 21, JDK built-in HTTP server (no third-party HTTP lib), Fabric API for ServerLifecycleEvents, Bash (POSIX-ish), `curl`, `jq` for JSON parsing in shell.

**v0 scope:** `/health`, `/cmd`, `/light`. CLI: `start`, `stop`, `cmd`, `light`, `health`, `logs`. Integration test script. Enough to drive a server, place blocks, read vanilla light. `/stats`, `/validate`, `/bench` are v1 (added once the lighting engine exists).

---

## File Structure

**New files:**
- `src/main/java/com/potatomc/harness/HarnessServer.java` — HTTP server lifecycle, endpoint registry
- `src/main/java/com/potatomc/harness/HarnessHandlers.java` — endpoint handler implementations
- `src/main/java/com/potatomc/harness/Json.java` — tiny zero-dep JSON encoder (writes only — we don't need a parser for v0 if we use form-style POST bodies, but we'll parse minimally for `/cmd`)
- `src/main/java/com/potatomc/harness/ServerHolder.java` — caches reference to active `MinecraftServer` (set on `ServerLifecycleEvents.SERVER_STARTED`)
- `scripts/pmh` — Bash CLI wrapper (executable)
- `scripts/test-pmh.sh` — integration test
- `.pmh/.gitkeep` — runtime state dir, created by CLI

**Modified files:**
- `src/main/java/com/potatomc/PotatoMC.java` — wire HarnessServer + ServerHolder into `onInitialize`
- `build.gradle` — add `-Dpotatomc.dev=true` to `runServer.jvmArgs` (already in `runClient`)
- `.gitignore` — exclude `.pmh/server.pid`, `.pmh/server.log`

**Tests:**
- `src/test/java/com/potatomc/harness/JsonTest.java`

---

## Task 0: Build verification

**Files:** (read-only)
- Read: `gradle.properties`, `build.gradle`

- [ ] **Step 1: Run build to confirm Fabric toolchain works**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL. If "Unsupported unpick version" or similar mapping error, STOP and report — versions in `gradle.properties` may need adjustment. Do not proceed to other tasks.

- [ ] **Step 2: Generate decompiled sources for Mixin reference**

```bash
./gradlew genSources
```

Expected: BUILD SUCCESSFUL. Used later when inspecting Minecraft internals.

- [ ] **Step 3: Commit (only if files changed; otherwise skip)**

```bash
git status --short
# If any modifications: git add -A && git commit -m "chore: verify build & gen sources"
```

---

## Task 1: Tiny JSON writer + parser

**Files:**
- Create: `src/main/java/com/potatomc/harness/Json.java`
- Test: `src/test/java/com/potatomc/harness/JsonTest.java`

We use the JDK's built-in HTTP server (zero deps). We need a tiny JSON encoder for responses and a minimal parser for request bodies (only used for `/cmd` and similar). Keeping it ~80 lines avoids pulling Gson/Jackson.

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/com/potatomc/harness/JsonTest.java
package com.potatomc.harness;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void writeString() {
        assertEquals("\"hello\"", Json.write("hello"));
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
        assertEquals("\"a\\\\b\"", Json.write("a\\b"));
        assertEquals("\"a\\nb\"", Json.write("a\nb"));
    }

    @Test
    void writePrimitives() {
        assertEquals("true", Json.write(true));
        assertEquals("false", Json.write(false));
        assertEquals("null", Json.write(null));
        assertEquals("42", Json.write(42));
        assertEquals("3.14", Json.write(3.14));
    }

    @Test
    void writeList() {
        assertEquals("[1,2,3]", Json.write(List.of(1, 2, 3)));
        assertEquals("[]", Json.write(List.of()));
    }

    @Test
    void writeMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", "x");
        assertEquals("{\"a\":1,\"b\":\"x\"}", Json.write(m));
    }

    @Test
    void writeNested() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("xs", List.of(1, 2));
        assertEquals("{\"ok\":true,\"xs\":[1,2]}", Json.write(m));
    }

    @Test
    void parseSimpleObject() {
        Map<String, Object> r = Json.parseObject("{\"command\":\"hi\",\"n\":42}");
        assertEquals("hi", r.get("command"));
        assertEquals(42L, r.get("n"));
    }

    @Test
    void parseBoolAndNull() {
        Map<String, Object> r = Json.parseObject("{\"a\":true,\"b\":false,\"c\":null}");
        assertEquals(Boolean.TRUE, r.get("a"));
        assertEquals(Boolean.FALSE, r.get("b"));
        assertNull(r.get("c"));
        assertTrue(r.containsKey("c"));
    }

    @Test
    void parseNumberArray() {
        Map<String, Object> r = Json.parseObject("{\"xs\":[1,2,3]}");
        assertEquals(List.of(1L, 2L, 3L), r.get("xs"));
    }

    @Test
    void parseStringWithEscapes() {
        Map<String, Object> r = Json.parseObject("{\"s\":\"a\\\"b\\nc\"}");
        assertEquals("a\"b\nc", r.get("s"));
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests JsonTest
```

Expected: compile failure (Json class doesn't exist).

- [ ] **Step 3: Implement Json**

```java
// src/main/java/com/potatomc/harness/Json.java
package com.potatomc.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny zero-dep JSON encoder + minimal object/array/primitive parser.
 * Not a complete JSON impl — covers the request/response shapes the harness uses.
 */
public final class Json {

    private Json() {}

    // ---- writer ----

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, v);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Number n) { sb.append(n.toString()); return; }
        if (v instanceof CharSequence s) { writeString(sb, s.toString()); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, x);
            }
            sb.append(']');
            return;
        }
        writeString(sb, v.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- parser ----

    public static Map<String, Object> parseObject(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value();
        if (!(v instanceof Map<?, ?> m)) throw new IllegalArgumentException("not an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        return typed;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') return nul();
            return num();
        }

        Map<String, Object> obj() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String k = str();
                skipWs();
                expect(':');
                Object v = value();
                m.put(k, v);
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i-1));
            }
        }

        List<Object> arr() {
            expect('[');
            List<Object> xs = new ArrayList<>();
            skipWs();
            if (peek() == ']') { i++; return xs; }
            while (true) {
                xs.add(value());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') return xs;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i-1));
            }
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + n);
                    }
                } else sb.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }

        Boolean bool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("bad bool at " + i);
        }

        Object nul() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalArgumentException("bad null at " + i);
        }

        Number num() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || ".eE+-".indexOf(s.charAt(i)) >= 0)) i++;
            String n = s.substring(start, i);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

```bash
./gradlew test --tests JsonTest
```

Expected: 9 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/potatomc/harness/Json.java src/test/java/com/potatomc/harness/JsonTest.java
git commit -m "feat(harness): tiny JSON writer + parser"
```

---

## Task 2: ServerHolder (capture MinecraftServer ref)

**Files:**
- Create: `src/main/java/com/potatomc/harness/ServerHolder.java`

The HTTP handlers need a reference to the running `MinecraftServer` to dispatch tasks onto its thread. Fabric's `ServerLifecycleEvents.SERVER_STARTED` provides this. We cache it in a static holder.

- [ ] **Step 1: Implement**

```java
// src/main/java/com/potatomc/harness/ServerHolder.java
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
```

- [ ] **Step 2: Verify build**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/potatomc/harness/ServerHolder.java
git commit -m "feat(harness): ServerHolder for thread-safe server access"
```

---

## Task 3: HarnessServer (HTTP lifecycle) + /health endpoint

**Files:**
- Create: `src/main/java/com/potatomc/harness/HarnessServer.java`

JDK's `HttpServer` from `com.sun.net.httpserver`. Bind `127.0.0.1`, port from system property `potatomc.harness.port` (default 25585). Only starts if `System.getProperty("potatomc.dev")` is truthy.

- [ ] **Step 1: Implement**

```java
// src/main/java/com/potatomc/harness/HarnessServer.java
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
            // Additional endpoints registered by HarnessHandlers.bindAll() (Task 4+).
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
        body.put("mc_version", net.minecraft.SharedConstants.getGameVersion().getName());
        body.put("mod_version", "0.1.0");
        body.put("server_ready", ServerHolder.isReady());
        respond(ex, 200, Json.write(body));
    }

    // ---- helpers exposed for handlers ----

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
```

- [ ] **Step 2: Add a stub HarnessHandlers so compilation works**

```java
// src/main/java/com/potatomc/harness/HarnessHandlers.java
package com.potatomc.harness;

import com.sun.net.httpserver.HttpHandler;

import java.util.function.BiConsumer;

public final class HarnessHandlers {

    private HarnessHandlers() {}

    /** Registers all endpoints other than /health on the provided binder. */
    public static void bindAll(BiConsumer<String, HttpHandler> bind) {
        // /cmd and /light added in Tasks 4 and 5.
    }
}
```

- [ ] **Step 3: Wire into PotatoMC.onInitialize**

Modify `src/main/java/com/potatomc/PotatoMC.java`:

```java
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
```

- [ ] **Step 4: Add JVM arg to build.gradle for runServer**

Modify `build.gradle`. Inside the `loom { runs { ... } }` block, ensure the `server` run has:

```gradle
        server {
            server()
            ideConfigGenerated true
            property 'potatomc.dev', 'true'
        }
```

(`property` is Loom's way of setting `-Dkey=value` for the run.)

Also add the same to the `client` run for parity:

```gradle
        client {
            client()
            ideConfigGenerated true
            property 'potatomc.dev', 'true'
            vmArgs(
                "-Xmx2G",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseG1GC",
                "-XX:+UseStringDeduplication",
                "-Dfabric.debug.disableClassPathIsolation=true"
            )
        }
```

- [ ] **Step 5: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL. If it fails on `com.sun.net.httpserver` import, ensure the module is added — JDK 21 includes it by default, but if the build uses module-info we may need to declare it. PotatoMC has no module-info, so this should just work.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/potatomc/harness/ src/main/java/com/potatomc/PotatoMC.java build.gradle
git commit -m "feat(harness): HTTP server + /health endpoint (dev-gated)"
```

---

## Task 4: /cmd endpoint

**Files:**
- Modify: `src/main/java/com/potatomc/harness/HarnessHandlers.java`

`/cmd` accepts `POST {"command": "<server console command>"}` and executes it as if typed on the server console. The command runs on the server thread via `ServerHolder.submitAndWait`.

- [ ] **Step 1: Implement /cmd handler**

Replace the contents of `HarnessHandlers.java`:

```java
// src/main/java/com/potatomc/harness/HarnessHandlers.java
package com.potatomc.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public final class HarnessHandlers {

    private HarnessHandlers() {}

    public static void bindAll(BiConsumer<String, HttpHandler> bind) {
        bind.accept("/cmd", HarnessHandlers::handleCmd);
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
            int result = ServerHolder.submitAndWait(() -> {
                MinecraftServer server = ServerHolder.get();
                ServerCommandSource src = server.getCommandSource();
                return server.getCommandManager().executeWithPrefix(src, s);
            }, 5000);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("result", result);
            body.put("command", s);
            HarnessServer.respond(ex, 200, Json.write(body));
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", e.getMessage());
            HarnessServer.respond(ex, 500, Json.write(body));
        }
    }
}
```

NB: in Yarn 1.21.x, `CommandManager.executeWithPrefix(ServerCommandSource, String)` is the entry point that mirrors typing a command in the console. Verify the exact name in genSources — if it's `execute(...)` instead, adjust.

- [ ] **Step 2: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/potatomc/harness/HarnessHandlers.java
git commit -m "feat(harness): /cmd endpoint (run server console commands)"
```

---

## Task 5: /light endpoint

**Files:**
- Modify: `src/main/java/com/potatomc/harness/HarnessHandlers.java`

`/light/{x}/{y}/{z}` returns vanilla block + sky light at that position. The `potato_*` fields are placeholders (set to vanilla values for now); when the lighting engine ships in v0.1, those fields will be wired up.

- [ ] **Step 1: Add light handler + path routing**

Replace the body of `HarnessHandlers.java`:

```java
// src/main/java/com/potatomc/harness/HarnessHandlers.java
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
            int result = ServerHolder.submitAndWait(() -> {
                MinecraftServer server = ServerHolder.get();
                ServerCommandSource src = server.getCommandSource();
                return server.getCommandManager().executeWithPrefix(src, s);
            }, 5000);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("result", result);
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
        String path = ex.getRequestURI().getPath(); // /light/X/Y/Z
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
                int vBlock = world.getLightLevel(LightType.BLOCK, pos);
                int vSky = world.getLightLevel(LightType.SKY, pos);
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("pos", java.util.List.of(x, y, z));
                b.put("vanilla_block", vBlock);
                b.put("vanilla_sky", vSky);
                b.put("potato_block", vBlock); // wired up when PotatoLightEngine lands
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
```

- [ ] **Step 2: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/potatomc/harness/HarnessHandlers.java
git commit -m "feat(harness): /light/X/Y/Z endpoint (vanilla read, potato placeholders)"
```

---

## Task 6: CLI wrapper — `scripts/pmh`

**Files:**
- Create: `scripts/pmh` (executable)
- Modify: `.gitignore`

The CLI handles server lifecycle (EULA, start, wait-until-ready, stop) and wraps endpoints via `curl`.

- [ ] **Step 1: Update .gitignore**

Append to `.gitignore`:

```
# PotatoHarness runtime state
.pmh/server.pid
.pmh/server.log
```

- [ ] **Step 2: Write the CLI**

```bash
# scripts/pmh
#!/usr/bin/env bash
set -euo pipefail

# PotatoHarness CLI — drives a headless Minecraft dev server.
# Requires: curl, jq, ./gradlew

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT_DIR/.pmh"
PID_FILE="$STATE_DIR/server.pid"
LOG_FILE="$STATE_DIR/server.log"
PORT="${PMH_PORT:-25585}"
BASE_URL="http://127.0.0.1:$PORT"

mkdir -p "$STATE_DIR"

err() { echo "pmh: $*" >&2; }
die() { err "$*"; exit 1; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "required tool '$1' not found"
}
require curl
require jq

cmd_start() {
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    err "server already running (pid $(cat "$PID_FILE"))"
    return 0
  fi
  # Auto-accept EULA
  mkdir -p "$ROOT_DIR/run"
  echo "eula=true" > "$ROOT_DIR/run/eula.txt"

  err "starting Minecraft dev server (logs: $LOG_FILE)"
  (cd "$ROOT_DIR" && nohup ./gradlew runServer --no-daemon >"$LOG_FILE" 2>&1 &)
  local gpid=$!
  echo "$gpid" > "$PID_FILE"

  err "waiting for /health on $BASE_URL (up to 180s) ..."
  for _ in $(seq 1 180); do
    if curl -fsS "$BASE_URL/health" >/dev/null 2>&1; then
      err "server ready"
      curl -fsS "$BASE_URL/health" | jq .
      return 0
    fi
    sleep 1
  done
  die "server didn't come up — check $LOG_FILE"
}

cmd_stop() {
  if curl -fsS -X POST "$BASE_URL/shutdown" >/dev/null 2>&1; then
    err "shutdown via HTTP requested"
  fi
  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid=$(cat "$PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
      err "killing gradle process $pid"
      pkill -P "$pid" 2>/dev/null || true
      kill "$pid" 2>/dev/null || true
      for _ in 1 2 3 4 5; do
        kill -0 "$pid" 2>/dev/null || { rm -f "$PID_FILE"; return 0; }
        sleep 1
      done
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
}

cmd_health() {
  curl -fsS "$BASE_URL/health" | jq .
}

cmd_cmd() {
  local command="$*"
  [[ -n "$command" ]] || die "usage: pmh cmd <minecraft command>"
  local payload
  payload=$(jq -nc --arg c "$command" '{command:$c}')
  curl -fsS -X POST -H 'Content-Type: application/json' -d "$payload" "$BASE_URL/cmd" | jq .
}

cmd_place() {
  local block="${1:-}"; local x="${2:-}"; local y="${3:-}"; local z="${4:-}"
  [[ -n "$block" && -n "$x" && -n "$y" && -n "$z" ]] || die "usage: pmh place <block> <x> <y> <z>"
  cmd_cmd "setblock $x $y $z $block"
}

cmd_light() {
  local x="${1:-}"; local y="${2:-}"; local z="${3:-}"
  [[ -n "$x" && -n "$y" && -n "$z" ]] || die "usage: pmh light <x> <y> <z>"
  curl -fsS "$BASE_URL/light/$x/$y/$z" | jq .
}

cmd_logs() {
  [[ -f "$LOG_FILE" ]] || die "no log file yet"
  if [[ "${1:-}" == "-f" ]]; then tail -f "$LOG_FILE"; else tail -n 100 "$LOG_FILE"; fi
}

main() {
  local sub="${1:-}"
  shift || true
  case "$sub" in
    start)  cmd_start  "$@" ;;
    stop)   cmd_stop   "$@" ;;
    health) cmd_health "$@" ;;
    cmd)    cmd_cmd    "$@" ;;
    place)  cmd_place  "$@" ;;
    light)  cmd_light  "$@" ;;
    logs)   cmd_logs   "$@" ;;
    ""|-h|--help|help)
      cat <<'EOF'
pmh — PotatoHarness CLI

  pmh start                          Start headless Minecraft dev server (auto EULA)
  pmh stop                           Stop the server
  pmh health                         Ping /health
  pmh cmd "<server command>"         Run a /command as server console
  pmh place <block> <x> <y> <z>      Shortcut for setblock
  pmh light <x> <y> <z>              Read light at position (vanilla + potato)
  pmh logs [-f]                      Tail the server log

Env:
  PMH_PORT  override the HTTP port (default 25585)
EOF
      ;;
    *) die "unknown subcommand: $sub (try pmh help)" ;;
  esac
}

main "$@"
```

- [ ] **Step 3: Make it executable**

```bash
chmod +x scripts/pmh
```

- [ ] **Step 4: Commit**

```bash
git add scripts/pmh .gitignore
git commit -m "feat(harness): scripts/pmh CLI wrapper"
```

---

## Task 7: /shutdown endpoint

**Files:**
- Modify: `src/main/java/com/potatomc/harness/HarnessHandlers.java`

The CLI calls `POST /shutdown` to ask the server for a graceful stop. We dispatch `server.stop(false)` on the server thread.

- [ ] **Step 1: Add handler and register**

In `HarnessHandlers.bindAll`, add:

```java
        bind.accept("/shutdown", HarnessHandlers::handleShutdown);
```

Add the method:

```java
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
        // Stop on the server thread AFTER replying, so the client gets the response.
        ServerHolder.get().execute(() -> ServerHolder.get().stop(false));
    }
```

- [ ] **Step 2: Verify build**

```bash
./gradlew build -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/potatomc/harness/HarnessHandlers.java
git commit -m "feat(harness): /shutdown endpoint"
```

---

## Task 8: Integration test script

**Files:**
- Create: `scripts/test-pmh.sh`

End-to-end test: start server, hit endpoints, validate JSON shape, stop.

- [ ] **Step 1: Write script**

```bash
# scripts/test-pmh.sh
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PMH="$ROOT/scripts/pmh"

cleanup() {
  "$PMH" stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[test] starting server"
"$PMH" start

echo "[test] /health"
HEALTH=$("$PMH" health)
echo "$HEALTH" | jq -e '.status == "ok"' >/dev/null
echo "$HEALTH" | jq -e '.mc_version' >/dev/null

echo "[test] /cmd"
RES=$("$PMH" cmd 'say hello-from-test')
echo "$RES" | jq -e '.success == true' >/dev/null

echo "[test] /light at spawn"
LIGHT=$("$PMH" light 0 100 0)
echo "$LIGHT" | jq -e '.vanilla_sky | numbers' >/dev/null
echo "$LIGHT" | jq -e '.match == true' >/dev/null

echo "[test] place glowstone + read light"
"$PMH" place glowstone 0 100 0 >/dev/null
sleep 1 # let the light propagate
LIGHT2=$("$PMH" light 1 100 0)
echo "$LIGHT2" | jq -e '.vanilla_block >= 14' >/dev/null

echo "[test] PASS"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/test-pmh.sh
```

- [ ] **Step 3: Smoke run the test (gating)**

```bash
./scripts/test-pmh.sh
```

Expected: prints "[test] PASS" within ~3 minutes. First run may take longer due to Minecraft asset downloads.

If it fails: read `.pmh/server.log` for diagnostics. Common issues:
- EULA: should be auto-handled, but verify `run/eula.txt` contains `eula=true`.
- Port already in use: another server is running. `pmh stop` or `pkill -f runServer`.
- Mappings / classpath: rerun `./gradlew genSources && ./gradlew build`.

- [ ] **Step 4: Commit**

```bash
git add scripts/test-pmh.sh
git commit -m "test(harness): end-to-end integration test"
```

---

## Definition of done — v0

- All Tasks 0–8 committed.
- `./gradlew test` passes (JsonTest green).
- `./gradlew build -x test` passes.
- `scripts/test-pmh.sh` passes in ≤ 3 min on a fresh checkout.
- The harness HTTP server **does not start** when `potatomc.dev` is not set (verify via `./gradlew build`-built jar — no port bound).

## Deferred to v1

- `/stats` (depends on `PotatoLightEngine`)
- `/validate` (depends on `DifferentialValidator`)
- `/world/load`, `/world/new` (depends on dynamic world management)
- `/bench/*` (depends on engine + harness)
- JSON pretty-print flag on CLI (current default is jq-formatted)
