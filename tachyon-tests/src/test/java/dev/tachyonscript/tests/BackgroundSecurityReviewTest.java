package dev.tachyonscript.tests;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.security.SecurityAuditStore;
import dev.tachyonscript.security.SecurityIncident;
import dev.tachyonscript.security.SecurityJson;
import dev.tachyonscript.security.SecurityOptions;
import dev.tachyonscript.security.SecurityService;
import dev.tachyonscript.testkit.InMemoryScripts;
import dev.tachyonscript.testkit.TestPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With {@code review-mode: background}, loading never waits for the AI provider: a new or
 * changed revision waits for its own review while the previous version keeps running, then
 * activates by itself; finished reviews are remembered across restarts.
 */
class BackgroundSecurityReviewTest {

    @TempDir
    Path directory;

    private final List<AutoCloseable> closing = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable resource : closing) {
            resource.close();
        }
    }

    /** A loopback chat-completions endpoint answering ALLOW, which can be held back or made to fail. */
    private static final class Provider implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final List<String> reviewedFiles = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile String holdFile = "";
        volatile boolean failing;
        private final HttpServer server;
        private final ExecutorService threads = Executors.newCachedThreadPool();

        Provider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(threads);
            server.createContext("/qwen", exchange -> {
                byte[] request = exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                var message = SecurityJson.object(SecurityJson.array(SecurityJson.object(SecurityJson.parse(
                        new String(request, StandardCharsets.UTF_8))).get("messages")).get(1));
                var manifest = SecurityJson.object(SecurityJson.parse(SecurityJson.string(message, "content")));
                String file = SecurityJson.string(manifest, "file");
                reviewedFiles.add(file);
                if (file.equals(holdFile)) {
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failing) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                String content = SecurityJson.write(Map.of("scriptId", manifest.get("scriptId"), "sha256", manifest.get("sha256"),
                        "decision", "ALLOW", "severity", "INFO", "confidence", .99, "summary", "Reviewed", "findings", List.of()));
                byte[] response = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        SecurityOptions options() {
            var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"),
                    "local-test", "dummy", Duration.ofSeconds(2), Duration.ofSeconds(15), 0, .8, .95, false,
                    1_048_576, 262_144, 32, 4096, true, 2);
            return new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
        }

        @Override
        public void close() {
            gate.countDown();
            server.stop(0);
            threads.shutdownNow();
        }
    }

    private record Harness(TestPlatform platform, ScriptEngine engine, SecurityService security,
                           List<LoadReport> activations, List<SecurityIncident> incidents) { }

    private Harness start(Provider provider) throws IOException {
        TestPlatform platform = new TestPlatform();
        List<SecurityIncident> incidents = new CopyOnWriteArrayList<>();
        SecurityService security = new SecurityService(provider.options(), new SecurityAuditStore(directory), incidents::add,
                error -> { });
        ScriptEngine engine = new ScriptEngine(platform.registry(), platform, EngineOptions.DEFAULT, InternalErrorHandler.IGNORE,
                security);
        List<LoadReport> activations = new CopyOnWriteArrayList<>();
        engine.onReviewActivation(activations::add);
        closing.add(engine::shutdown);
        return new Harness(platform, engine, security, activations, incidents);
    }

    /** Loads off the server thread (AI reviews never run on it) while the test ticks the server. */
    private static LoadReport load(Harness harness, InMemoryScripts scripts) throws Exception {
        CompletableFuture<LoadReport> loading = CompletableFuture.supplyAsync(() -> harness.engine().load(scripts));
        await(harness, loading::isDone);
        return loading.get(1, TimeUnit.SECONDS);
    }

    private static void await(Harness harness, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            harness.platform().scheduler().tick(1);
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "timed out");
    }

    private static boolean logged(Harness harness, String line) {
        return harness.platform().logs().stream().anyMatch(entry -> entry.endsWith(line));
    }

    @Test
    void loadingNeverWaitsForTheProviderAndReviewedRevisionsActivateByThemselves() throws Exception {
        Provider provider = new Provider();
        closing.add(provider);
        provider.holdFile = "slow.tys";
        provider.gate = new CountDownLatch(1);
        Harness harness = start(provider);
        InMemoryScripts scripts = new InMemoryScripts()
                .put("slow.tys", "on load { log(\"slow active\") }\n")
                .put("quick.tys", "on load { log(\"quick active\") }\n");

        LoadReport report = load(harness, scripts);
        assertTrue(report.activated(), report::toString);
        assertEquals(List.of("quick.tys", "slow.tys"), report.awaitingReview().stream().sorted().toList());
        assertTrue(report.summary().contains("await their AI security review"), report.summary());

        await(harness, () -> logged(harness, "quick active"));
        assertFalse(logged(harness, "slow active"), "nothing runs before its review");
        assertEquals(Set.of("slow.tys"), harness.engine().awaitingReview());

        provider.gate.countDown();
        await(harness, () -> logged(harness, "slow active"));
        assertTrue(harness.engine().awaitingReview().isEmpty());
        assertEquals(Set.of("quick.tys", "slow.tys"), harness.engine().generation().scripts().keySet());
        assertFalse(harness.activations().isEmpty(), "the host receives the activation report");
    }

    @Test
    void aRevisionUnderReviewKeepsThePreviousVersionRunningUntilItIsApproved() throws Exception {
        Provider provider = new Provider();
        closing.add(provider);
        Harness harness = start(provider);
        InMemoryScripts scripts = new InMemoryScripts().put("shop.tys", "on load { log(\"version 1\") }\n");
        load(harness, scripts);
        await(harness, () -> logged(harness, "version 1"));
        String first = harness.engine().generation().scripts().get("shop.tys").hash();

        provider.holdFile = "shop.tys";
        provider.gate = new CountDownLatch(1);
        scripts.put("shop.tys", "on load { log(\"version 2\") }\n");
        long started = System.nanoTime();
        LoadReport report = load(harness, scripts);
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "the reload does not wait for the provider");
        assertEquals(List.of("shop.tys"), report.awaitingReview());
        assertTrue(report.failed().isEmpty(), "waiting for a review is not a failure");
        assertEquals(first, harness.engine().generation().scripts().get("shop.tys").hash(), "version 1 keeps running");
        assertFalse(logged(harness, "version 2"));

        provider.gate.countDown();
        await(harness, () -> logged(harness, "version 2"));
        assertFalse(first.equals(harness.engine().generation().scripts().get("shop.tys").hash()));
    }

    @Test
    void anImporterActivatesTogetherWithTheModuleItWaitedFor() throws Exception {
        Provider provider = new Provider();
        closing.add(provider);
        provider.holdFile = "lib.tys";
        provider.gate = new CountDownLatch(1);
        Harness harness = start(provider);
        InMemoryScripts scripts = new InMemoryScripts()
                .put("lib.tys", "function greeting(): string {\n    log(\"lib used\")\n    return \"hello\"\n}\n")
                .put("user.tys", "import lib\non load { log(\"user says \" + lib.greeting()) }\n");
        LoadReport report = load(harness, scripts);
        assertEquals(List.of("lib.tys", "user.tys"), report.awaitingReview().stream().sorted().toList());
        // The importer's own review finishes first; it must still wait for its dependency.
        await(harness, () -> provider.reviewedFiles.contains("user.tys") && harness.security().reviewsRunning() == 1);
        Thread.sleep(200);
        harness.platform().scheduler().tick(1);
        assertFalse(logged(harness, "user says hello"));

        provider.gate.countDown();
        await(harness, () -> logged(harness, "user says hello"));
        assertTrue(harness.engine().awaitingReview().isEmpty());
    }

    @Test
    void finishedReviewsSurviveARestartSoUnchangedScriptsAreNotSentAgain() throws Exception {
        Provider provider = new Provider();
        closing.add(provider);
        InMemoryScripts scripts = new InMemoryScripts().put("kept.tys", "on load { log(\"kept active\") }\n");
        Harness first = start(provider);
        load(first, scripts);
        await(first, () -> logged(first, "kept active"));
        assertEquals(1, provider.requests.get());
        first.engine().shutdown();

        Harness second = start(provider);
        LoadReport report = load(second, scripts);
        assertTrue(report.awaitingReview().isEmpty(), "the stored review decides at once");
        assertTrue(logged(second, "kept active"));
        assertEquals(1, provider.requests.get(), "the provider is not asked again");
        assertEquals(1, second.security().storedReviews());
    }

    @Test
    void aProviderOutageInTheBackgroundStillActivatesUnderTheWarnPolicy() throws Exception {
        Provider provider = new Provider();
        closing.add(provider);
        provider.failing = true;
        Harness harness = start(provider);
        InMemoryScripts scripts = new InMemoryScripts()
                .put("one.tys", "on load { log(\"one active\") }\n")
                .put("two.tys", "on load { log(\"two active\") }\n");
        LoadReport report = load(harness, scripts);
        assertEquals(2, report.awaitingReview().size());
        await(harness, () -> logged(harness, "one active") && logged(harness, "two active"));
        assertTrue(harness.incidents().stream().anyMatch(incident -> incident.action().equals("API_FAILURE")));
        assertTrue(provider.requests.get() <= 2, "queued reviews do not each retry a failing provider");
    }
}
