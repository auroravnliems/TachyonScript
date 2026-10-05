package dev.tachyonscript.security;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.language.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SecurityProviderAuditTest {
    @TempDir Path folder;
    private static SourceFile unsafe() { return new SourceFile("shop.tys", "\n\nevent player.chat {\n    server.dispatch(message)\n}\n"); }
    private static Map<String, Object> response(SecurityManifest manifest, List<Map<String, Object>> findings) {
        return Map.of("scriptId", manifest.scriptId(), "sha256", manifest.sha256(), "decision", "QUARANTINE",
                "severity", "CRITICAL", "confidence", .99, "summary", "Review", "findings", findings);
    }
    private static Map<String, Object> finding(SecurityNode node) {
        return Map.of("id", "CMD-001", "nodeId", node.id(), "category", "COMMAND_INJECTION", "severity", "CRITICAL",
                "confidence", .99, "explanation", "Untrusted command input reaches console execution.",
                "evidence", "Tainted data enters this concrete command sink.", "line", 999, "column", 999,
                "snippet", "fabricated model source");
    }
    @Test void modelLocationsAndSnippetsNeverOverrideCompilerEvidence() {
        SourceFile source = unsafe(); var manifest = SecurityAnalyzerTest.analyze(source);
        SecurityNode node = manifest.nodes().stream().filter(n -> n.capability() == Capability.CONSOLE_COMMAND).findFirst().orElseThrow();
        try (var provider = new QwenSecurityProvider(SecurityOptions.defaults())) {
            var findings = provider.validate(SecurityJson.write(response(manifest, List.of(finding(node)))), manifest, source);
            assertEquals(4, findings.getFirst().location().startLine()); assertEquals(5, findings.getFirst().location().startColumn());
            assertFalse(findings.getFirst().codeSnippet().contains("fabricated"));
            assertTrue(findings.getFirst().concreteEvidence());
            assertEquals(SecurityDecision.QUARANTINE, SecurityPolicyEngine.decide(findings, SecurityOptions.defaults()));
        }
    }
    @Test void ungroundedCriticalWrongNodeOrHarmlessNativeCannotAutoDisable() {
        SourceFile source = new SourceFile("safe.tys", "on load { server.dispatch(\"say hello\") }\n");
        var manifest = SecurityAnalyzerTest.analyze(source);
        var node = manifest.nodes().stream().filter(n -> n.capability() == Capability.CONSOLE_COMMAND).findFirst().orElseThrow();
        try (var provider = new QwenSecurityProvider(SecurityOptions.defaults())) {
            var vague = provider.validate(SecurityJson.write(response(manifest, List.of())), manifest, source);
            assertEquals(SecurityDecision.WARN, SecurityPolicyEngine.decide(vague, SecurityOptions.defaults()));
            assertEquals(-1, vague.getFirst().location().startLine());
            var harmless = provider.validate(SecurityJson.write(response(manifest, List.of(finding(node)))), manifest, source);
            assertFalse(harmless.getFirst().concreteEvidence());
            assertEquals(SecurityDecision.WARN, SecurityPolicyEngine.decide(harmless, SecurityOptions.defaults()));
            var foreign = new java.util.HashMap<>(finding(node)); foreign.put("nodeId", "node-from-another-revision");
            var unknown = provider.validate(SecurityJson.write(response(manifest, List.of(foreign))), manifest, source);
            assertEquals(-1, unknown.getFirst().location().startLine()); assertFalse(unknown.getFirst().concreteEvidence());
            var stale = new java.util.HashMap<>(response(manifest, List.of(finding(node)))); stale.put("sha256", "a".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> provider.validate(SecurityJson.write(stale), manifest, source));
            var wrongId = new java.util.HashMap<>(response(manifest, List.of(finding(node)))); wrongId.put("scriptId", ScriptIdentity.of("foreign.tys"));
            assertThrows(IllegalArgumentException.class, () -> provider.validate(SecurityJson.write(wrongId), manifest, source));
        }
    }
    @Test void credentialsAreRedactedFromEveryManifestFindingAndMessage() {
        String secret = "fake-password-ForLocalTestsOnly";
        SourceFile source = new SourceFile("credentials.tys", "let password = \"" + secret + "\"\non load {\n    web.post(\"https://api.example.com\", password, \"text/plain\", (status, body) => {})\n}\n");
        var manifest = SecurityAnalyzerTest.analyze(source);
        assertFalse(SecurityJson.write(manifest.toJson()).contains(secret));
        assertTrue(manifest.findings().stream().anyMatch(f -> f.category() == SecurityCategory.DATA_EXFILTRATION));
        SecurityIncident incident = incident(source, manifest.findings());
        assertFalse(SecurityMessages.detail(incident, true).contains(secret));
        assertTrue(SecurityMessages.discord(incident, false).stream().noneMatch(message -> message.contains("Đoạn mã")));
        assertTrue(SecurityMessages.discord(incident, true).stream().noneMatch(message -> message.contains(secret)));
    }
    @Test void auditAndQuarantineSurviveRestartWithExactSourceAndLocations() throws Exception {
        SourceFile source = unsafe(); var manifest = SecurityAnalyzerTest.analyze(source);
        SecurityIncident incident = incident(source, manifest.findings());
        try (SecurityAuditStore store = new SecurityAuditStore(folder)) { store.append(incident, source); }
        try (SecurityAuditStore restarted = new SecurityAuditStore(folder)) {
            assertEquals(incident, restarted.incident(incident.id()));
            assertEquals(incident, restarted.blocked().get(incident.scriptId()));
            assertEquals(source.hash(), restarted.snapshot(incident.id()).hash());
            assertEquals(source.content(), restarted.snapshot(incident.id()).content());
            assertEquals(4, restarted.incident(incident.id()).findings().getFirst().location().startLine());
            assertThrows(IOException.class, () -> restarted.append(incident, source));
        }
        String audit = Files.readString(folder.resolve("audit.jsonl"));
        Files.writeString(folder.resolve("audit.jsonl"), audit.replace("COMMAND_INJECTION", "PRIVILEGE_ESCALATION"));
        assertThrows(IOException.class, () -> new SecurityAuditStore(folder));
    }
    @Test void qwenHttpRetriesValidatesEnvelopeAndSendsOnlyRedactedManifest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger(); AtomicReference<String> request = new AtomicReference<>();
        var source = unsafe(); var manifest = SecurityAnalyzerTest.analyze(source);
        var node = manifest.nodes().stream().filter(n -> n.capability() == Capability.CONSOLE_COMMAND).findFirst().orElseThrow();
        server.createContext("/qwen", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (requests.incrementAndGet() == 1) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            assertEquals("Bearer fake-local-api-key", exchange.getRequestHeaders().getFirst("Authorization"));
            var boundedRequest = SecurityJson.object(SecurityJson.parse(request.get()));
            if (SecurityJson.integer(boundedRequest, "max_tokens") != 1024) {
                exchange.sendResponseHeaders(402, -1); exchange.close(); return;
            }
            String body = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                    Map.of("content", SecurityJson.write(response(manifest, List.of(finding(node)))))))));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var defaults = SecurityOptions.defaults();
            var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"), "test-model",
                    "fake-local-api-key", Duration.ofSeconds(1), Duration.ofSeconds(2), 1, .80, .95, true, 1_048_576, 262_144, 32, 1024);
            var options = new SecurityOptions(true, ai, defaults.discord(), Set.of(), Map.of(), 25_000, 500);
            try (var provider = new QwenSecurityProvider(options)) {
                assertEquals(4, provider.review(manifest, source).getFirst().location().startLine());
                assertEquals(2, requests.get());
                assertTrue(request.get().contains("json_object")); assertTrue(request.get().contains(node.id()));
                assertFalse(request.get().contains("fake-local-api-key"));
            }
        } finally { server.stop(0); }
    }
    @Test void providerTokenBudgetExhaustionNeverApprovesPartialJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var source = unsafe(); var manifest = SecurityAnalyzerTest.analyze(source);
        server.createContext("/qwen", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "length",
                    "message", Map.of("content", "{\"decision\":\"ALLOW\"}"))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"),
                    "local-test", "dummy", Duration.ofSeconds(1), Duration.ofSeconds(2), 0, .8, .95,
                    true, 1_048_576, 262_144, 32, 128);
            var options = new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
            try (var provider = new QwenSecurityProvider(options)) {
                assertThrows(IOException.class, () -> provider.review(manifest, source));
            }
        } finally { server.stop(0); }
    }
    @Test void aiBatchesCoverEveryNodeAndEveryHttpRequestRespectsItsByteLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SourceFile source = new SourceFile("large.tys", "on load {\n" + java.util.stream.IntStream.range(0, 80)
                .mapToObj(i -> "    server.dispatch(\"say safe " + i + "\")\n").collect(java.util.stream.Collectors.joining()) + "}\n");
        SecurityManifest manifest = SecurityAnalyzerTest.analyze(source);
        Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet(); AtomicInteger requests = new AtomicInteger();
        AtomicInteger largest = new AtomicInteger();
        server.createContext("/qwen", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes(); largest.accumulateAndGet(bytes.length, Math::max); requests.incrementAndGet();
            var request = SecurityJson.object(SecurityJson.parse(new String(bytes, StandardCharsets.UTF_8)));
            var part = SecurityJson.object(SecurityJson.parse(SecurityJson.string(SecurityJson.object(SecurityJson.array(request.get("messages")).get(1)), "content")));
            SecurityJson.array(part.get("nodes")).stream().map(SecurityJson::object).map(n -> SecurityJson.string(n, "nodeId")).forEach(seen::add);
            String content = SecurityJson.write(Map.of("scriptId", part.get("scriptId"), "sha256", part.get("sha256"), "decision", "ALLOW",
                    "severity", "INFO", "confidence", .99, "summary", "All supplied nodes reviewed", "findings", List.of()));
            byte[] body = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"), "local-test", "dummy",
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 0, .8, .95, true, 16_384, 16_384);
            var options = new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
            try (var provider = new QwenSecurityProvider(options)) { assertTrue(provider.review(manifest, source).isEmpty()); }
            assertTrue(requests.get() > 1); assertTrue(largest.get() <= 16_384);
            assertEquals(manifest.nodes().stream().map(SecurityNode::id).collect(java.util.stream.Collectors.toSet()), seen);
        } finally { server.stop(0); }
    }
    @Test void stagedWebhookNeverDeliversBeforeItsAuditCommit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); CountDownLatch delivered = new CountDownLatch(1);
        server.createContext("/webhook", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); exchange.close(); delivered.countDown();
        }); server.start();
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var options = new SecurityOptions.Discord(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook", true, Duration.ofSeconds(2), 0);
        try (var notifier = new DiscordSecurityNotifier(options, folder, ignored -> { }, ignored -> committed.get())) {
            notifier.enqueue(incident(unsafe(), SecurityAnalyzerTest.analyze(unsafe()).findings()));
            assertFalse(delivered.await(200, TimeUnit.MILLISECONDS)); assertTrue(notifier.pendingCount() > 0);
            committed.set(true); notifier.ready(); assertTrue(delivered.await(3, TimeUnit.SECONDS));
        } finally { server.stop(0); }
    }
    @Test void discordSplitsFullDetailDisablesMentionsConfirmsAndPersistsDelivery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger requests = new AtomicInteger(); CountDownLatch delivered = new CountDownLatch(1);
        server.createContext("/webhook", exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(exchange.getRequestURI().getQuery().contains("wait=true"));
            if (requests.incrementAndGet() == 1) { exchange.sendResponseHeaders(429, -1); exchange.close(); return; }
            messages.add(payload); exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); exchange.close(); delivered.countDown();
        });
        server.start();
        var options = new SecurityOptions.Discord(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook", true, Duration.ofSeconds(2), 1);
        var source = unsafe(); SecurityIncident incident = incident(source, SecurityAnalyzerTest.analyze(source).findings());
        try (var notifier = new DiscordSecurityNotifier(options, folder, ignored -> { })) {
            notifier.enqueue(incident); assertTrue(delivered.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (notifier.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, notifier.pendingCount());
            String combined = String.join("", messages);
            assertTrue(combined.contains("shop.tys:4:5")); assertTrue(combined.contains("Luồng dữ liệu"));
            for (String json : messages) {
                var message = SecurityJson.object(SecurityJson.parse(json));
                SecurityDiscordCardTest.assertValid(message);
                assertTrue(SecurityJson.string(message, "content").length() <= 2000);
                assertEquals(List.of(), SecurityJson.array(SecurityJson.object(message.get("allowed_mentions")).get("parse")));
            }
        } finally { server.stop(0); }
        try (var restarted = new DiscordSecurityNotifier(options, folder, ignored -> { })) { assertEquals(0, restarted.pendingCount()); }
    }
    @Test void pendingLegacyPlainTextPayloadIsDeliveredUnchangedAfterCardUpgrade() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch delivered = new CountDownLatch(1); AtomicReference<String> received = new AtomicReference<>();
        String payload = SecurityJson.write(Map.of("content", "Existing queued incident: shop.tys:4:5", "allowed_mentions", Map.of("parse", List.of())));
        SecurityAuditStore.appendFile(folder.resolve("discord-outbox.jsonl"), SecurityJson.write(Map.of("id",
                SecurityAuditStore.incidentId() + ".0", "payload", payload)) + "\n");
        server.createContext("/webhook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8)); exchange.close(); delivered.countDown();
        }); server.start();
        var options = new SecurityOptions.Discord(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook", true, Duration.ofSeconds(2), 0);
        try (var notifier = new DiscordSecurityNotifier(options, folder, ignored -> { })) {
            notifier.ready();
            assertTrue(delivered.await(3, TimeUnit.SECONDS)); assertEquals(payload, received.get());
        } finally { server.stop(0); }
    }
    @Test void editedQuarantineCanOnlyRestoreWithCurrentHashApprovalAndKeepsOriginalSnapshot() throws Exception {
        SourceFile original = unsafe(); var modules = SecurityAnalyzerTest.compile(original);
        try (var service = new SecurityService(SecurityOptions.defaults(), new SecurityAuditStore(folder), ignored -> { }, ignored -> { })) {
            service.register(List.of(original)); var batch = service.review(modules); service.commit(batch, modules);
            var incident = service.audit().blocked().get(ScriptIdentity.of(original.path()));
            SourceFile edited = new SourceFile(original.path(), "on load { log(\"repaired\") }\n");
            service.register(List.of(edited));
            assertThrows(IOException.class, () -> service.restore(incident.id(), "admin"));
            service.review(SecurityAnalyzerTest.compile(edited));
            service.approve(edited.path(), "admin"); service.restore(incident.id(), "admin");
            assertFalse(service.blocked(edited.path()));
            assertEquals(original.hash(), service.audit().snapshot(incident.id()).hash());
            service.register(List.of(new SourceFile(edited.path(), "\non load { log(\"repaired\") }\n")));
            assertFalse(service.audit().approved(ScriptIdentity.of(edited.path()), service.registeredFile(edited.path()).hash()));
        }
    }
    @Test void editedImporterInvalidatesALibraryApprovalEvenWhenTheLibraryHashIsUnchanged() throws Exception {
        SourceFile library = new SourceFile("lib.tys", "function run(value: string) { server.dispatch(value) }\n");
        SourceFile caller = new SourceFile("caller.tys", "import lib\nevent player.chat { lib.run(message) }\n");
        var modules = SecurityAnalyzerTest.compile(library, caller);
        try (var service = new SecurityService(SecurityOptions.defaults(), new SecurityAuditStore(folder), ignored -> { }, ignored -> { })) {
            service.register(List.of(library, caller)); service.commit(service.review(modules), modules);
            var incident = service.audit().blocked().get(ScriptIdentity.of(library.path())); assertNotNull(incident);
            service.approve(library.path(), "administrator"); service.restore(incident.id(), "administrator");
            assertFalse(service.review(modules).denied().contains(library.path()));
            SourceFile editedCaller = new SourceFile(caller.path(), "\n" + caller.content());
            service.register(List.of(library, editedCaller));
            assertThrows(IOException.class, () -> service.approve(library.path(), "administrator")); // Unreviewed related bytes.
            var changed = service.review(SecurityAnalyzerTest.compile(library, editedCaller));
            assertEquals(library.hash(), service.registeredFile(library.path()).hash());
            assertTrue(changed.denied().contains(library.path()));
        }
    }
    @Test void immutableReportRecoversAQuarantineAfterCrashBeforeJournalAppend() throws Exception {
        SourceFile source = unsafe(); var manifest = SecurityAnalyzerTest.analyze(source);
        SecurityIncident incident = incident(source, manifest.findings());
        Files.writeString(folder.resolve(incident.id() + ".report.json"), SecurityJson.write(incident.toJson()));
        Files.writeString(folder.resolve(incident.id() + ".source.tys"), source.content());
        try (var recovered = new SecurityAuditStore(folder)) {
            assertEquals(incident.id(), recovered.blocked().get(incident.scriptId()).id());
            assertEquals(source.hash(), recovered.snapshot(incident.id()).hash());
        }
        try (var restarted = new SecurityAuditStore(folder)) { assertEquals(1, restarted.incidents().size()); }
        assertFalse(Files.exists(folder.resolve("discord-outbox.jsonl"))); // No historical webhook payload reconstruction.
    }
    @Test void recoveredOldRestoreCannotUndoANewerJournalledQuarantine() throws Exception {
        SourceFile source = unsafe(); SecurityIncident denial = incident(source, SecurityAnalyzerTest.analyze(source).findings());
        try (var store = new SecurityAuditStore(folder)) { store.append(denial, source); }
        var older = new SecurityIncident(SecurityAuditStore.incidentId(), denial.timestamp().minusSeconds(1), denial.scriptId(), denial.file(), denial.sha256(),
                SecurityDecision.WARN, "RESTORE", SecuritySeverity.INFO, "Older restore interrupted before commit", List.of(), List.of(), "admin", denial.id());
        Files.writeString(folder.resolve(older.id() + ".report.json"), SecurityJson.write(older.toJson()));
        for (int restart = 0; restart < 2; restart++) try (var store = new SecurityAuditStore(folder)) {
            assertEquals(denial.id(), store.blocked().get(denial.scriptId()).id());
            assertEquals(2, store.incidents().size());
        }
    }
    private static SecurityIncident incident(SourceFile source, List<SecurityFinding> findings) {
        return new SecurityIncident(SecurityAuditStore.incidentId(), Instant.now(), ScriptIdentity.of(source.path()), source.path(), source.hash(),
                SecurityDecision.QUARANTINE, "AUTO_QUARANTINE", SecuritySeverity.CRITICAL, "Blocked", findings, List.of("market.tys"), "security-policy", "");
    }
}
