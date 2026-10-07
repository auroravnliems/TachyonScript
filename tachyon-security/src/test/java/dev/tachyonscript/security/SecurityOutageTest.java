package dev.tachyonscript.security;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.language.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SecurityOutageTest {
    @ParameterizedTest
    @ValueSource(ints = {401, 402, 429, 503, 200})
    void providerOutageDoesNotBlockSafeSourcesOrFloodRequestsAndAlerts(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/review", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] body = "malformed response with a remote-secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        List<SourceFile> files = new ArrayList<>();
        for (int i = 0; i < 31; i++) files.add(new SourceFile("safe" + i + ".tys", "on load { log(\"safe\") }"));
        files.add(new SourceFile("empty.tys", ""));
        files.add(new SourceFile("danger.tys", "event player.chat { server.dispatch(message) }"));
        files.add(new SourceFile("dependent.tys", "import danger\non load { log(\"dependent\") }"));
        var modules = SecurityAnalyzerTest.compile(files.toArray(SourceFile[]::new));
        var alerts = new ArrayList<SecurityIncident>();
        AtomicLong now = new AtomicLong(1);
        try (var service = new SecurityService(options(server, false), new SecurityAuditStore(null),
                alerts::add, fail -> { throw new AssertionError(fail); }, now::get)) {
            service.register(files);
            var batch = service.review(modules);
            assertTrue(batch.pending().isEmpty());
            assertEquals(Set.of("danger.tys", "dependent.tys"), batch.denied());
            service.commit(batch, modules);
            var failure = alerts.stream().filter(i -> i.action().equals("API_FAILURE")).findFirst().orElseThrow();
            assertTrue(failure.findings().isEmpty());
            assertTrue(failure.summary().contains("does not disable scripts"));
            assertFalse(SecurityMessages.console(failure).contains("remote-secret"));
            assertFalse(SecurityMessages.console(failure).contains("\n"));
            assertFalse(service.blocked("safe0.tys"));
            assertTrue(service.blocked("danger.tys"));
            assertEquals(1, requests.get(), "one failed request opens the provider cooldown for the entire batch");
            service.commit(service.review(modules), modules);
            assertEquals(1, requests.get(), "scanall must not bypass provider cooldown");
            assertEquals(1, alerts.stream().filter(i -> i.action().equals("API_FAILURE")).count());
            now.addAndGet(TimeUnit.MINUTES.toNanos(6));
            service.commit(service.review(modules), modules);
            assertEquals(2, requests.get());
            assertEquals(2, alerts.stream().filter(i -> i.action().equals("API_FAILURE")).count());
        } finally { server.stop(0); }
    }

    @Test
    void explicitPendingPolicyAndProviderRecoveryRemainAvailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger status = new AtomicInteger(503);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/review", exchange -> {
            requests.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (status.get() != 200) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            var messages = SecurityJson.array(SecurityJson.object(SecurityJson.parse(request)).get("messages"));
            var manifest = SecurityJson.object(SecurityJson.parse(SecurityJson.string(SecurityJson.object(messages.get(1)), "content")));
            String content = SecurityJson.write(Map.of("scriptId", manifest.get("scriptId"), "sha256", manifest.get("sha256"),
                    "decision", "ALLOW", "severity", "INFO", "confidence", 1, "summary", "Reviewed", "findings", List.of()));
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("content", content);
            message.put("tool_calls", null);
            message.put("function_call", null);
            byte[] response = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", message))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        SourceFile file = new SourceFile("safe.tys", "on load { log(\"safe\") }");
        var modules = SecurityAnalyzerTest.compile(file);
        AtomicLong now = new AtomicLong(1);
        try (var service = new SecurityService(options(server, true), new SecurityAuditStore(null), ignored -> { },
                fail -> { throw new AssertionError(fail); }, now::get)) {
            service.register(List.of(file));
            var pending = service.review(modules);
            assertEquals(Set.of("safe.tys"), pending.pending());
            assertTrue(pending.denied().isEmpty());
            service.commit(pending, modules);
            assertFalse(service.blocked(file.path()), "provider failure is never persisted as quarantine");
            status.set(200);
            now.addAndGet(TimeUnit.SECONDS.toNanos(61));
            var recovered = service.review(modules);
            assertTrue(recovered.pending().isEmpty());
            assertTrue(recovered.reviews().get(file.path()).failure().isEmpty());
            assertEquals(2, requests.get(), "valid envelopes may contain null tool-call fields");
        } finally { server.stop(0); }
    }

    @Test
    void aiCannotEscalateAnEstablishedCommandPermissionToAutomaticQuarantine() {
        SourceFile file = new SourceFile("admin.tys",
                "@permission(\"server.admin\")\ncommand run(text: string) { server.dispatch(text) }");
        var manifest = SecurityAnalyzerTest.analyze(file);
        var node = manifest.nodes().stream().filter(n -> n.capability() == Capability.CONSOLE_COMMAND).findFirst().orElseThrow();
        assertFalse(node.permission().isBlank());
        String response = SecurityJson.write(Map.of("scriptId", manifest.scriptId(), "sha256", manifest.sha256(),
                "decision", "QUARANTINE", "severity", "CRITICAL", "confidence", 1, "summary", "Untrusted command",
                "findings", List.of(Map.of("id", "cmd", "nodeId", node.id(), "category", "COMMAND_INJECTION",
                        "severity", "CRITICAL", "confidence", 1, "explanation", "Dynamic command", "evidence", "Tainted argument"))));
        try (var provider = new QwenSecurityProvider(SecurityOptions.defaults())) {
            var findings = provider.validate(response, manifest, file);
            assertEquals(SecurityDecision.WARN, SecurityPolicyEngine.decide(findings, SecurityOptions.defaults()));
            assertFalse(findings.getFirst().concreteEvidence());
        }
    }

    private static SecurityOptions options(HttpServer server, boolean required) {
        var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/review"),
                "local-test", "fake-local-key", Duration.ofSeconds(1), Duration.ofSeconds(1), 0,
                .8, .95, required, 1_048_576, 262_144);
        return new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
    }
}
