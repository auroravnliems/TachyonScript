package dev.tachyonscript.security;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.language.source.SourceFile;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the AI reviewer receives: every relevant piece of code once, compactly, and nothing else. */
class AiReviewViewTest {

    private static final String SCRIPT = """
            function reward(player: Player, amount: double) {
                let rounded = math.floor(amount)
                economy.deposit(player, rounded)
                player.send("You received {rounded}")
            }
            event player.join {
                reward(player, 10.0)
            }
            event player.quit {
                reward(player, 1.0)
            }
            command pay(target: Player, amount: double) {
                reward(target, amount)
            }
            """;

    /** Reviews one script against a loopback provider answering ALLOW; returns the views it received. */
    private static List<Map<String, Object>> views(SourceFile source) throws Exception {
        SecurityManifest manifest = SecurityAnalyzerTest.analyze(source);
        List<Map<String, Object>> views = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/qwen", exchange -> {
            var request = SecurityJson.object(SecurityJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            var view = SecurityJson.object(SecurityJson.parse(SecurityJson.string(
                    SecurityJson.object(SecurityJson.array(request.get("messages")).get(1)), "content")));
            views.add(view);
            String content = SecurityJson.write(Map.of("scriptId", view.get("scriptId"), "sha256", view.get("sha256"),
                    "decision", "ALLOW", "severity", "INFO", "confidence", .99, "summary", "Reviewed", "findings", List.of()));
            byte[] body = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"),
                    "local-test", "dummy", Duration.ofSeconds(1), Duration.ofSeconds(5), 0, .8, .95, false, 1_048_576, 262_144);
            var options = new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
            try (var provider = new QwenSecurityProvider(options)) {
                assertTrue(provider.review(manifest, source).isEmpty());
            }
        } finally {
            server.stop(0);
        }
        return views;
    }

    @Test
    void sharedCodeIsSentOnceWithEveryContextAndSource() throws Exception {
        var views = views(new SourceFile("shared.tys", SCRIPT));
        assertEquals(1, views.size());
        var nodes = SecurityJson.array(views.getFirst().get("nodes")).stream().map(SecurityJson::object).toList();
        var deposits = nodes.stream().filter(node -> "economy.deposit".equals(node.get("target"))).toList();
        assertEquals(1, deposits.size(), "one deposit in the source, one node for the reviewer");
        var deposit = deposits.getFirst();
        String contexts = SecurityJson.write(deposit.get("contexts"));
        assertTrue(contexts.contains("event player.join") && contexts.contains("event player.quit") && contexts.contains("command /pay"), contexts);
        String evidence = SecurityJson.write(deposit);
        assertTrue(evidence.contains("command argument amount"), "the untrusted amount reaches the reviewer: " + evidence);
        assertFalse(evidence.contains("\"location\""), "positions are compact line:column text");
    }

    @Test
    void untaintedPureCallsAndReadsAreCountedNotSentButActionsAlwaysAre() throws Exception {
        var source = new SourceFile("quiet.tys", """
                event player.join {
                    let name = player.name
                    let size = math.max(1, 2)
                    player.send("Welcome {name} {size}")
                    economy.deposit(player, 5.0)
                }
                """);
        var manifest = SecurityAnalyzerTest.analyze(source);
        assertTrue(manifest.nodes().stream().anyMatch(node -> !QwenSecurityProvider.reviewable(node)));
        var view = views(source).getFirst();
        var targets = SecurityJson.array(view.get("nodes")).stream().map(SecurityJson::object)
                .map(node -> String.valueOf(node.get("target"))).toList();
        assertTrue(targets.contains("economy.deposit"), targets.toString());
        assertTrue(targets.contains("CommandSender.send") || targets.stream().anyMatch(target -> target.endsWith(".send")), targets.toString());
        assertFalse(targets.contains("math.max"), targets.toString());
        assertTrue(SecurityJson.integer(view, "omittedNodes") > 0);
    }

    @Test
    void scriptsWithNothingToReviewNeverContactTheProvider() throws Exception {
        var source = new SourceFile("math.tys", """
                function area(width: int, height: int): int {
                    return math.max(0, width) * math.max(0, height)
                }
                """);
        assertFalse(QwenSecurityProvider.needsReview(SecurityAnalyzerTest.analyze(source)));
        assertTrue(views(source).isEmpty());
    }
}
