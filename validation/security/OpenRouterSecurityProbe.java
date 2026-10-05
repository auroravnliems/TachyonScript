import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.security.Capability;
import dev.tachyonscript.security.QwenSecurityProvider;
import dev.tachyonscript.security.SecurityAnalyzer;
import dev.tachyonscript.security.SecurityCategory;
import dev.tachyonscript.security.SecurityDecision;
import dev.tachyonscript.security.SecurityFinding;
import dev.tachyonscript.security.SecurityJson;
import dev.tachyonscript.security.SecurityManifest;
import dev.tachyonscript.security.SecurityOptions;
import dev.tachyonscript.security.SecurityPolicyEngine;
import dev.tachyonscript.stdlib.StandardLibrary;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Opt-in live integration probe. Reviews synthetic sources; it never executes a script. */
public final class OpenRouterSecurityProbe {
    private OpenRouterSecurityProbe() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("Expected endpoint, model, report path and plugin JAR path");
        }
        String secret = System.getenv("OPENROUTER_API_KEY");
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("OPENROUTER_API_KEY is required; its value is never recorded");
        }
        var options = new SecurityOptions(true,
                new SecurityOptions.Ai(true, URI.create(arguments[0]), arguments[1], secret,
                        Duration.ofSeconds(5), Duration.ofSeconds(60), 0, .80, .95,
                        true, 1_048_576, 262_144),
                SecurityOptions.Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
        SourceFile safe = new SourceFile("live-safe.tys", "on load {\n    server.dispatch(\"say security-control\")\n}\n");
        SourceFile unsafe = new SourceFile("live-unsafe.tys", "// Unicode \ud83d\ude00 Ti\u1ebfng Vi\u1ec7t\r\n\r\n"
                + "event player.chat {\r\n    let cmd = \"give \" + message\r\n    server.dispatch(cmd)\r\n}\r\n");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", Instant.now().toString());
        report.put("endpoint", arguments[0]);
        report.put("model", arguments[1]);
        report.put("pluginSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(arguments[3])))));
        report.put("sourcePolicy", "Synthetic fixtures only; no server/user scripts are uploaded or executed");
        Map<String, Object> checks = new LinkedHashMap<>();
        List<SecurityFinding> unsafeFindings;
        try (var provider = new QwenSecurityProvider(options)) {
            var safeManifest = analyze(safe, options);
            System.out.println("Reviewing the synthetic safe control through the packaged provider...");
            var safeFindings = provider.review(safeManifest, safe);
            require(SecurityPolicyEngine.decide(safeFindings, options) == SecurityDecision.ALLOW,
                    "AI did not allow the safe constant-command control");
            report.put("safeFindings", safeFindings.stream().map(SecurityFinding::toJson).toList());
            checks.put("safeConstantCommandAllowed", true);

            var unsafeManifest = analyze(unsafe, options);
            System.out.println("Reviewing the synthetic command-injection fixture through the packaged provider...");
            unsafeFindings = provider.review(unsafeManifest, unsafe);
            SecurityFinding command = unsafeFindings.stream()
                    .filter(finding -> finding.category() == SecurityCategory.COMMAND_INJECTION)
                    .findFirst().orElseThrow(() -> new IOException("AI did not identify the known command-injection fixture"));
            var node = unsafeManifest.nodes().stream()
                    .filter(candidate -> candidate.capability() == Capability.CONSOLE_COMMAND)
                    .findFirst().orElseThrow();
            require(command.origin() == SecurityFinding.Origin.AI, "Expected an actual AI finding");
            require(command.scriptId().equals(unsafeManifest.scriptId()) && command.sha256().equals(unsafe.hash()),
                    "AI finding identity/revision mismatch");
            require(command.location().equals(node.span()) && command.nodeId().equals(node.id()),
                    "AI finding did not resolve the authoritative compiler node");
            require(command.location().file().equals("live-unsafe.tys")
                            && command.location().startLine() == 5 && command.location().startColumn() == 5
                            && command.location().endLine() == 5 && command.location().endColumn() == 25,
                    "AI finding source coordinates are incorrect");
            require(unsafe.content().substring(command.location().startOffset(), command.location().endOffset())
                            .equals("server.dispatch(cmd)"), "AI finding points to the wrong expression");
            require(command.source().equals("message") && command.sink().equals("server.dispatch"),
                    "AI finding lost compiler-backed source/sink evidence");
            require(command.flow().stream().anyMatch(step -> step.kind().equals("SOURCE")
                            && step.location().startLine() == 4 && step.location().startColumn() == 25)
                            && command.flow().stream().anyMatch(step -> step.kind().equals("CONCAT"))
                            && command.flow().stream().anyMatch(step -> step.kind().equals("SINK")
                            && step.location().startLine() == 5), "AI finding lost its complete taint path");
            require(command.codeSnippet().contains("5 |     server.dispatch(cmd)"),
                    "AI finding snippet does not match the reviewed local revision");
            require(SecurityPolicyEngine.decide(unsafeFindings, options) == SecurityDecision.QUARANTINE,
                    "AI finding is not eligible for automatic quarantine under the configured thresholds");
            checks.put("strictProviderJsonValidated", true);
            checks.put("matchingScriptIdAndSourceSha256", true);
            checks.put("authoritativeNodeAndExactSpan", true);
            checks.put("crlfUnicodeAndBlankLines", true);
            checks.put("localSnippetMatchesRevision", true);
            checks.put("completeCompilerTaintPath", true);
            checks.put("aiOnlyPolicyEligibleForQuarantine", true);
            report.put("unsafeFindings", unsafeFindings.stream().map(SecurityFinding::toJson).toList());
            report.put("vulnerableExpression", "server.dispatch(cmd)");
        }
        report.put("checks", checks);
        report.put("success", true);
        String json = SecurityJson.write(report);
        require(!json.contains(secret), "Secret appeared in the report");
        Path destination = Path.of(arguments[2]);
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.writeString(destination, json, StandardCharsets.UTF_8);
        System.out.println(SecurityJson.write(Map.of("success", true, "checks", checks.size(),
                "report", destination.toString(), "model", arguments[1],
                "location", unsafeFindings.stream().filter(finding -> finding.category() == SecurityCategory.COMMAND_INJECTION)
                        .findFirst().orElseThrow().location().display())));
    }

    private static SecurityManifest analyze(SourceFile source, SecurityOptions options) throws IOException {
        var compiled = new Compiler(StandardLibrary.registry()).compile(List.of(source));
        require(compiled.succeeded(), "Synthetic probe source failed compilation");
        return new SecurityAnalyzer(options).analyze(compiled.modules().stream().map(module -> module.bound()).toList())
                .get(source.path());
    }

    private static void require(boolean value, String message) throws IOException {
        if (!value) throw new IOException(message);
    }
}
