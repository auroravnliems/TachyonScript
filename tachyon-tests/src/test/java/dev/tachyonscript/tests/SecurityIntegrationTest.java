package dev.tachyonscript.tests;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadMode;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.ScriptSource;
import dev.tachyonscript.ir.Spans;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.security.*;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SecurityIntegrationTest {
    @TempDir Path directory;
    private static final String SAFE = "on load { log(\"active\") }\non unload { log(\"unsafe unload must not run\") }\n"
            + "command ping { log(\"ping\") }\nevery 1 second { log(\"tick\") }\nfunction exported(): int { return 7 }\n";
    private static final String BAD = "\n\nevent player.chat {\n    server.dispatch(\"give \" + message)\n}\n";

    @ParameterizedTest @EnumSource(LoadMode.class)
    void quarantineRetiresOldRuntimeExportsTasksCommandsAndDependentsInBothReloadModes(LoadMode mode) throws Exception {
        TestPlatform platform = new TestPlatform(); List<SecurityIncident> notifications = new ArrayList<>();
        EngineOptions defaults = EngineOptions.DEFAULT;
        var engineOptions = new EngineOptions(mode, defaults.compiler(), defaults.limits(), 0, false);
        var security = new SecurityService(SecurityOptions.defaults(), new SecurityAuditStore(directory), notifications::add, ignored -> { });
        ScriptEngine engine = new ScriptEngine(platform.registry(), platform, engineOptions, InternalErrorHandler.IGNORE, security);
        InMemoryScripts scripts = new InMemoryScripts().put("shop.tys", SAFE)
                .put("market.tys", "import shop\nfunction value(): int { return shop.exported() }\n");
        try {
            assertTrue(engine.load(scripts).failed().isEmpty());
            var previous = engine.generation().scripts().get("shop.tys");
            var exported = previous.linked().function("exported()").orElseThrow();
            var dependentExport = engine.generation().scripts().get("market.tys").linked().function("value()").orElseThrow();
            assertEquals(7, Interpreter.call(exported)); assertTrue(platform.scheduler().pending() > 0);
            scripts.put("shop.tys", BAD);
            // A distinct compiler failure must not cause strict rollback to resurrect a quarantined generation.
            scripts.put("syntax.tys", "event player.join { invalid !!! }\n");
            LoadReport report = engine.load(scripts);
            assertFalse(report.keptPrevious().contains("shop.tys"));
            assertFalse(engine.generation().scripts().containsKey("shop.tys"));
            assertFalse(engine.generation().scripts().containsKey("market.tys"));
            assertEquals(0, platform.scheduler().pending());
            assertFalse(platform.commandNames().contains("ping"));
            assertTrue(previous.securityRevoked());
            assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, assertThrows(ScriptRuntimeException.class, () -> Interpreter.call(exported)).kind());
            assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, assertThrows(ScriptRuntimeException.class, () -> Interpreter.call(dependentExport)).kind());
            assertFalse(platform.logs().stream().anyMatch(log -> log.contains("unsafe unload must not run")));
            var incident = notifications.stream().filter(i -> i.file().equals("shop.tys") && i.decision().deniesExecution()).findFirst().orElseThrow();
            assertEquals(4, incident.findings().getFirst().location().startLine());
            assertEquals(5, incident.findings().getFirst().location().startColumn());
            assertTrue(incident.dependencies().contains("market.tys"));
            assertTrue(SecurityMessages.admin(incident).stream().anyMatch(line -> line.contains("shop.tys:4:5")));
            assertTrue(security.blocked("shop.tys"));
        } finally { engine.shutdown(); }
    }
    @Test void runningCodeCannotCatchRevocationOrRunItsFinallyBlock() throws Exception {
        TestPlatform platform = new TestPlatform(); ScriptEngine engine = platform.engine();
        String code = "function spin(limit: int) { log(\"entered security loop\"); var n = 0; "
                + "try { while n < limit { n += 1 } } catch e { log(\"revocation caught\") } "
                + "finally { log(\"revocation finally\") } }\n";
        try {
            assertTrue(engine.load(new InMemoryScripts().put("running.tys", code)).failed().isEmpty());
            var function = engine.generation().scripts().get("running.tys").linked().function("spin(int)").orElseThrow();
            var running = CompletableFuture.supplyAsync(() -> {
                try { Interpreter.call(function, Integer.MAX_VALUE); return null; }
                catch (ScriptRuntimeException exception) { return exception.kind(); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (platform.logs().stream().noneMatch(line -> line.endsWith("entered security loop")) && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(platform.logs().stream().anyMatch(line -> line.endsWith("entered security loop"))); assertFalse(running.isDone());
            engine.security().blockNow("running.tys");
            assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, running.get(1, TimeUnit.SECONDS));
            assertFalse(platform.logs().stream().anyMatch(line -> line.endsWith("revocation caught")));
            assertFalse(platform.logs().stream().anyMatch(line -> line.endsWith("revocation finally")));
        } finally { engine.shutdown(); }
    }
    @Test void restoringDoesNotResurrectFunctionReferencesFromAnyPreviouslyRevokedVersion() throws Exception {
        TestPlatform platform = new TestPlatform(); ScriptEngine engine = platform.engine();
        var scripts = new InMemoryScripts().put("epoch.tys", "function value(): int { return 7 }\n");
        try {
            assertTrue(engine.load(scripts).failed().isEmpty());
            var old = engine.generation().scripts().get("epoch.tys").linked().function("value()").orElseThrow();
            scripts.put("epoch.tys", "function value(): int { return 8 }\n");
            assertTrue(engine.load(scripts).failed().isEmpty()); assertEquals(7, Interpreter.call(old));
            engine.securityDisable("epoch.tys", SecurityDecision.QUARANTINE, "test administrator");
            var incident = engine.security().audit().blocked().get(ScriptIdentity.of("epoch.tys"));
            engine.security().approve("epoch.tys", "test administrator"); engine.security().restore(incident.id(), "test administrator");
            assertTrue(engine.load(scripts).failed().isEmpty());
            assertEquals(8, Interpreter.call(engine.generation().scripts().get("epoch.tys").linked().function("value()").orElseThrow()));
            assertEquals(ScriptRuntimeException.Kind.SECURITY_REVOKED, assertThrows(ScriptRuntimeException.class, () -> Interpreter.call(old)).kind());
        } finally { engine.shutdown(); }
    }
    @Test void initialMaliciousSourceNeverRunsItsInitializerAndMapsIrToTheSameStatement() {
        TestPlatform platform = new TestPlatform(); ScriptEngine engine = platform.engine();
        InMemoryScripts scripts = new InMemoryScripts().put("initial.tys", "on load { log(\"must never run\") }\n" + BAD);
        try {
            engine.load(scripts);
            assertTrue(engine.generation().scripts().isEmpty());
            assertFalse(platform.logs().stream().anyMatch(line -> line.contains("must never run")));
            var incident = engine.security().audit().blocked().get(ScriptIdentity.of("initial.tys"));
            assertNotNull(incident); assertEquals(5, incident.findings().getFirst().location().startLine());
        } finally { engine.shutdown(); }
        ScriptEngine safe = platform.engine();
        try {
            var source = new InMemoryScripts().put("ir.tys", "on load {\n    server.dispatch(\"say safe\")\n}\n");
            assertTrue(safe.load(source).failed().isEmpty());
            var loaded = safe.generation().scripts().get("ir.tys");
            var node = safe.security().manifest("ir.tys").nodes().stream().filter(n -> n.capability() == Capability.CONSOLE_COMMAND).findFirst().orElseThrow();
            var call = loaded.compiled().ir().functions().stream().flatMap(f -> f.blocks().stream())
                    .flatMap(b -> b.instructions().stream()).filter(i -> i instanceof dev.tachyonscript.ir.Instruction.CallNative).findFirst().orElseThrow();
            assertEquals(node.span().startOffset(), Spans.start(call.span()));
            assertEquals(node.span().endOffset(), Spans.end(call.span()));
            var function = loaded.linked().function(loaded.compiled().bound().loadHooks().getFirst().key()).orElseThrow();
            assertTrue(java.util.Arrays.stream(function.unit().lineSpans()).anyMatch(span -> span == call.span()));
        } finally { safe.shutdown(); }
    }
    @Test void sourceEditsBetweenScanAndCommitDiscardOldFindingsAndRecompile() {
        TestPlatform platform = new TestPlatform(); ScriptEngine engine = platform.engine();
        AtomicReference<SourceFile> current = new AtomicReference<>(new SourceFile("race.tys", BAD));
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ScriptSource source = () -> {
            if (reads.incrementAndGet() == 2) current.set(new SourceFile("race.tys", "\n\non load { log(\"safe replacement\") }\n"));
            return List.of(current.get());
        };
        try {
            assertTrue(engine.load(source).failed().isEmpty());
            assertEquals(current.get().hash(), engine.generation().scripts().get("race.tys").hash());
            assertFalse(engine.security().blocked("race.tys"));
            assertTrue(engine.security().audit().incidents().isEmpty());
        } finally { engine.shutdown(); }
    }
    @Test void requiredQwenFailureKeepsEditedCodePendingAndRetainsPreviouslyApprovedRuntime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/qwen", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (unavailable.get()) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            var message = SecurityJson.object(SecurityJson.array(SecurityJson.object(SecurityJson.parse(new String(request, StandardCharsets.UTF_8))).get("messages")).get(1));
            var manifest = SecurityJson.object(SecurityJson.parse(SecurityJson.string(message, "content")));
            String content = SecurityJson.write(Map.of("scriptId", manifest.get("scriptId"), "sha256", manifest.get("sha256"), "decision", "ALLOW",
                    "severity", "INFO", "confidence", .99, "summary", "Reviewed", "findings", List.of()));
            byte[] response = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        TestPlatform platform = new TestPlatform();
        var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"), "local-test", "dummy",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, .8, .95, true, 1_048_576, 262_144);
        var service = new SecurityService(new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500),
                new SecurityAuditStore(directory), ignored -> { }, ignored -> { });
        ScriptEngine engine = new ScriptEngine(platform.registry(), platform, EngineOptions.DEFAULT, InternalErrorHandler.IGNORE, service);
        InMemoryScripts scripts = new InMemoryScripts().put("pending.tys", "on load { log(\"approved version\") }\n");
        try {
            var initial = CompletableFuture.supplyAsync(() -> engine.load(scripts));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!initial.isDone() && System.nanoTime() < deadline) { platform.scheduler().tick(1); Thread.sleep(2); }
            assertTrue(initial.get(1, TimeUnit.SECONDS).failed().isEmpty());
            String approvedHash = engine.generation().scripts().get("pending.tys").hash();
            scripts.put("pending.tys", "on load { log(\"unreviewed edit\") }\n"); unavailable.set(true);
            var changed = CompletableFuture.supplyAsync(() -> engine.load(scripts));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!changed.isDone() && System.nanoTime() < deadline) { platform.scheduler().tick(1); Thread.sleep(2); }
            var report = changed.get(1, TimeUnit.SECONDS); assertTrue(report.failed().contains("pending.tys"));
            assertEquals(approvedHash, engine.generation().scripts().get("pending.tys").hash());
            assertTrue(platform.logs().stream().noneMatch(line -> line.endsWith("unreviewed edit")));
            assertTrue(service.audit().incidents().stream().anyMatch(i -> i.action().equals("API_FAILURE") && i.findings().isEmpty()));
        } finally { engine.shutdown(); server.stop(0); }
    }
    @Test void slowAsyncQwenResultIsDiscardedAfterEditAndAiCannotSupplyLocations() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<SourceFile> current = new AtomicReference<>(new SourceFile("ai.tys", "@permission(\"admin.run\")\ncommand run(text: string) { server.dispatch(text) }\n"));
        String originalHash = current.get().hash();
        server.createContext("/qwen", exchange -> {
            var request = SecurityJson.object(SecurityJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            var messages = SecurityJson.array(request.get("messages"));
            var manifest = SecurityJson.object(SecurityJson.parse(SecurityJson.string(SecurityJson.object(messages.get(1)), "content")));
            String hash = SecurityJson.string(manifest, "sha256");
            if (hash.equals(originalHash)) {
                received.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            var nodes = SecurityJson.array(manifest.get("nodes"));
            var sink = nodes.stream().map(SecurityJson::object).filter(n -> "CONSOLE_COMMAND".equals(n.get("capability"))).findFirst();
            Object findings = hash.equals(originalHash) ? List.of(Map.of("id", "COMMAND", "nodeId", sink.orElseThrow().get("nodeId"), "category", "COMMAND_INJECTION",
                    "severity", "CRITICAL", "confidence", .99, "explanation", "Concrete dangerous command input", "evidence", "This node executes tainted command text", "line", 900)) : List.of();
            var answer = Map.of("scriptId", manifest.get("scriptId"), "sha256", hash, "decision", hash.equals(originalHash) ? "QUARANTINE" : "ALLOW",
                    "severity", hash.equals(originalHash) ? "CRITICAL" : "INFO", "confidence", .99, "summary", "Reviewed", "findings", findings);
            byte[] body = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", SecurityJson.write(answer)))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        TestPlatform platform = new TestPlatform();
        var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"), "test-model", "fake-key",
                Duration.ofSeconds(1), Duration.ofSeconds(4), 0, .8, .95, true, 1_048_576, 262_144);
        var options = new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
        var service = new SecurityService(options, new SecurityAuditStore(directory), ignored -> { }, ignored -> { });
        ScriptEngine engine = new ScriptEngine(platform.registry(), platform, EngineOptions.DEFAULT, InternalErrorHandler.IGNORE, service);
        try {
            assertFalse(engine.load(() -> List.of(current.get())).activated()); // No networking on the tick thread.
            CompletableFuture<LoadReport> load = CompletableFuture.supplyAsync(() -> engine.load(() -> List.of(current.get())));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(engine.generation().scripts().isEmpty());
            current.set(new SourceFile("ai.tys", "\n\non load { log(\"reviewed replacement\") }\n")); release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!load.isDone() && System.nanoTime() < deadline) { platform.scheduler().tick(1); Thread.sleep(5); }
            assertTrue(load.get(1, TimeUnit.SECONDS).failed().isEmpty());
            assertEquals(current.get().hash(), engine.generation().scripts().get("ai.tys").hash());
            assertFalse(service.blocked("ai.tys"));
            assertTrue(service.audit().incidents().stream().noneMatch(i -> i.decision().deniesExecution()));
        } finally { release.countDown(); engine.shutdown(); server.stop(0); }
    }
}
