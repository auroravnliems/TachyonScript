import com.sun.net.httpserver.HttpServer;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.security.QwenSecurityProvider;
import dev.tachyonscript.security.SecurityAnalyzer;
import dev.tachyonscript.security.SecurityJson;
import dev.tachyonscript.security.SecurityOptions;
import dev.tachyonscript.stdlib.StandardLibrary;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/**
 * Bytes each script's AI review would send, measured on a loopback stand-in for the provider
 * that answers ALLOW. Nothing leaves the machine and no script runs.
 */
public class ReviewRequestSize {
    public static void main(String[] args) throws Exception {
        List<SourceFile> sources = new ArrayList<>();
        try (ZipFile archive = new ZipFile(args[0])) {
            var entries = archive.stream().filter(e -> e.getName().startsWith("scripts/") && e.getName().endsWith(".tys"))
                    .sorted(Comparator.comparing(e -> e.getName())).toList();
            for (var entry : entries) {
                String path = entry.getName().substring("scripts/".length());
                if (Arrays.stream(path.split("/")).anyMatch(p -> p.startsWith("-"))) continue;
                try (var stream = archive.getInputStream(entry)) {
                    sources.add(new SourceFile(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        }
        AtomicLong bytes = new AtomicLong();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/qwen", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            bytes.addAndGet(request.length);
            requests.incrementAndGet();
            var message = SecurityJson.object(SecurityJson.array(SecurityJson.object(SecurityJson.parse(
                    new String(request, StandardCharsets.UTF_8))).get("messages")).get(1));
            var view = SecurityJson.object(SecurityJson.parse(SecurityJson.string(message, "content")));
            String content = SecurityJson.write(Map.of("scriptId", view.get("scriptId"), "sha256", view.get("sha256"),
                    "decision", "ALLOW", "severity", "INFO", "confidence", .99, "summary", "Reviewed", "findings", List.of()));
            byte[] response = SecurityJson.write(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var ai = new SecurityOptions.Ai(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen"),
                    "measure", "local", Duration.ofSeconds(2), Duration.ofSeconds(30), 0, .8, .95, false, 1_048_576, 262_144);
            var options = new SecurityOptions(true, ai, SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
            var result = new Compiler(StandardLibrary.registry()).compile(sources);
            var manifests = new SecurityAnalyzer(options).analyze(result.modules().stream().map(m -> m.bound()).toList());
            long total = 0;
            try (var provider = new QwenSecurityProvider(options)) {
                for (var entry : new TreeMap<>(manifests).entrySet()) {
                    long before = bytes.get();
                    int calls = requests.get();
                    SourceFile source = sources.stream().filter(s -> s.path().equals(entry.getKey())).findFirst().orElseThrow();
                    provider.review(entry.getValue(), source);
                    long sent = bytes.get() - before;
                    total += sent;
                    System.out.printf(Locale.ROOT, "%-45s %8d bytes in %d request(s), %d of %d nodes reviewable%n", entry.getKey(), sent,
                            requests.get() - calls, entry.getValue().nodes().stream().filter(QwenSecurityProvider::reviewable).count(),
                            entry.getValue().nodes().size());
                }
            }
            System.out.printf(Locale.ROOT, "TOTAL %d bytes in %d requests%n", total, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
