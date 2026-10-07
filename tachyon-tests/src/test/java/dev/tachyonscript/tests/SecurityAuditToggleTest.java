package dev.tachyonscript.tests;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.security.ScriptIdentity;
import dev.tachyonscript.security.SecurityAuditStore;
import dev.tachyonscript.security.SecurityJson;
import dev.tachyonscript.security.SecurityOptions;
import dev.tachyonscript.security.SecurityService;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning the AI reviewer off and on again, with background reviews, configuration reloads,
 * edits and server restarts in between, must never leave a security journal that fails its
 * hash chain or a write-ahead report that cannot be read.
 */
class SecurityAuditToggleTest {

    @TempDir
    Path directory;

    /** A loopback reviewer answering after a random delay with one finding on the first command node. */
    private static final class Provider implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService threads = Executors.newCachedThreadPool();
        private final Random random = new Random(7);

        Provider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(threads);
            server.createContext("/qwen", exchange -> {
                byte[] request = exchange.getRequestBody().readAllBytes();
                var message = SecurityJson.object(SecurityJson.array(SecurityJson.object(SecurityJson.parse(
                        new String(request, StandardCharsets.UTF_8))).get("messages")).get(1));
                var manifest = SecurityJson.object(SecurityJson.parse(SecurityJson.string(message, "content")));
                List<Object> findings = new ArrayList<>();
                for (Object node : SecurityJson.array(manifest.get("nodes"))) {
                    var value = SecurityJson.object(node);
                    if ("CONSOLE_COMMAND".equals(value.get("capability"))) {
                        findings.add(Map.of("id", "AI-1", "nodeId", value.get("nodeId"), "category", "COMMAND_INJECTION",
                                "severity", "MEDIUM", "confidence", .9, "explanation", "Dynamic console command.",
                                "evidence", "The command text comes from a parameter."));
                        break;
                    }
                }
                try {
                    Thread.sleep(random.nextInt(40));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String content = SecurityJson.write(Map.of("scriptId", manifest.get("scriptId"), "sha256", manifest.get("sha256"),
                        "decision", findings.isEmpty() ? "ALLOW" : "WARN", "severity", findings.isEmpty() ? "INFO" : "MEDIUM",
                        "confidence", .9, "summary", "Reviewed", "findings", findings));
                byte[] response = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        SecurityOptions options(boolean ai) {
            var reviewer = new SecurityOptions.Ai(ai, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"),
                    "local-test", "dummy", Duration.ofSeconds(2), Duration.ofSeconds(15), 0, .8, .95, false,
                    1_048_576, 262_144, 32, 4096, true, 2);
            return new SecurityOptions(true, reviewer, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
        }

        @Override
        public void close() {
            server.stop(0);
            threads.shutdownNow();
        }
    }

    private static void tickUntil(TestPlatform platform, BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            platform.scheduler().tick(1);
            Thread.sleep(1);
        }
    }

    @Test
    void turningTheReviewerOffAndOnAcrossRestartsKeepsTheJournalVerifiable() throws Exception {
        Random random = new Random(42);
        InMemoryScripts scripts = new InMemoryScripts()
                .put("admin.tys", "@permission(\"admin.run\")\ncommand run(value: string) {\n    server.dispatch(value)\n}\n")
                .put("tools.tys", "function send(text: string) {\n    server.dispatch(text)\n}\n")
                .put("user.tys", "import tools\n@permission(\"admin.say\")\ncommand say(text: string) {\n    tools.send(\"say \" + text)\n}\n")
                .put("plain.tys", "on load { log(\"plain\") }\n");
        try (Provider provider = new Provider()) {
            boolean ai = true;
            // Two restarts in a few hundred left an empty report behind before storage writes were shielded.
            for (int cycle = 0; cycle < 60; cycle++) {
                TestPlatform platform = new TestPlatform();
                List<String> recoveries = new ArrayList<>();
                SecurityService security = new SecurityService(provider.options(ai), new SecurityAuditStore(directory, recoveries::add),
                        incident -> { }, error -> { });
                assertEquals(List.of(), recoveries, "cycle " + cycle + ": the store had to recover");
                ScriptEngine engine = new ScriptEngine(platform.registry(), platform, EngineOptions.DEFAULT,
                        InternalErrorHandler.IGNORE, security);
                CompletableFuture<?> loading = CompletableFuture.runAsync(() -> engine.load(scripts));
                tickUntil(platform, loading::isDone, 10_000);
                if (random.nextBoolean()) {
                    String path = List.of("admin.tys", "tools.tys", "user.tys").get(random.nextInt(3));
                    scripts.put(path, scripts.get(path) + "// edit " + cycle + "\n");
                    CompletableFuture<?> reloading = CompletableFuture.runAsync(() -> engine.reload(scripts, Set.of(path)));
                    tickUntil(platform, reloading::isDone, 10_000);
                }
                // "/tys security reload" after the reviewer was switched in config.yml.
                ai = !ai;
                boolean switched = ai;
                CompletableFuture<?> rescan = CompletableFuture.runAsync(() -> {
                    try {
                        security.reloadOptions(provider.options(switched));
                        engine.scanSecurity(scripts);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                if (random.nextBoolean()) tickUntil(platform, rescan::isDone, 10_000);
                CompletableFuture<?> another = random.nextBoolean() ? CompletableFuture.runAsync(() -> engine.load(scripts))
                        : CompletableFuture.completedFuture(null);
                // Restart at an arbitrary moment, often while background reviews and their follow-ups run.
                tickUntil(platform, () -> false, random.nextInt(60));
                engine.shutdownOnPlatformThread();
                tickUntil(platform, () -> rescan.isDone() && another.isDone(), 10_000);
                assertJournalVerifies(cycle);
            }
        }
    }

    /** Recomputes the hash chain exactly like the store and checks that every report can be read. */
    private void assertJournalVerifies(int cycle) throws IOException {
        String previous = "0".repeat(64);
        int line = 0;
        Path journal = directory.resolve("audit.jsonl");
        if (Files.exists(journal)) {
            try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
                for (String text; (text = reader.readLine()) != null; ) {
                    line++;
                    var value = SecurityJson.object(SecurityJson.parse(text));
                    var event = SecurityJson.object(value.get("incident"));
                    String hash = ScriptIdentity.hash(previous + SecurityJson.write(event)
                            + (value.containsKey("stateApplied") ? "|stateApplied=" + value.get("stateApplied") : ""));
                    assertEquals(previous, value.get("previousHash"), "cycle " + cycle + ": record " + line + " does not follow its predecessor");
                    assertEquals(hash, value.get("hash"), "cycle " + cycle + ": record " + line + " was changed");
                    previous = hash;
                }
            }
        }
        try (var files = Files.list(directory)) {
            for (Path report : files.filter(path -> path.getFileName().toString().endsWith(".report.json")).toList()) {
                assertTrue(Files.size(report) > 0, "cycle " + cycle + ": empty write-ahead report " + report.getFileName());
                SecurityJson.parse(Files.readString(report, StandardCharsets.UTF_8));
            }
        }
    }
}
