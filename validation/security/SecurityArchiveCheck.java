import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.security.SecurityAnalyzer;
import dev.tachyonscript.security.SecurityJson;
import dev.tachyonscript.security.SecurityOptions;
import dev.tachyonscript.security.SecurityPolicyEngine;
import dev.tachyonscript.stdlib.StandardLibrary;
import dev.tachyonscript.language.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

/** Read-only replay of a supplied script archive; never activates code or contacts AI/webhooks. */
public class SecurityArchiveCheck {
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
        var result = new Compiler(StandardLibrary.registry()).compile(sources);
        if (!result.succeeded()) {
            result.diagnostics().diagnostics().forEach(d -> System.out.println(d.code() + " " + d.location()));
            throw new AssertionError("Archive did not compile");
        }
        var options = SecurityOptions.defaults();
        var manifests = new SecurityAnalyzer(options).analyze(result.modules().stream().map(m -> m.bound()).toList());
        List<Object> summary = new ArrayList<>();
        Set<String> denied = new TreeSet<>();
        for (var entry : new TreeMap<>(manifests).entrySet()) {
            var manifest = entry.getValue();
            var decision = SecurityPolicyEngine.decide(manifest.findings(), options);
            if (decision.deniesExecution()) denied.add(entry.getKey());
            summary.add(Map.of("file", entry.getKey(), "sha256", manifest.sha256(), "decision", decision.name(),
                    "rules", manifest.findings().stream().map(f -> f.ruleId() + ":" + f.severity() + ":" + f.location().startLine())
                        .distinct().sorted().toList()));
        }
        System.out.println(SecurityJson.write(Map.of("compiled", sources.size(), "denied", denied, "scripts", summary,
                "execution", "compile and deterministic analysis only; no script execution or network")));
        if (!denied.equals(Set.of("08_GUIs/GUI_DynamicJson.tys")))
            throw new AssertionError("Unexpected quarantine set: " + denied);
    }
}
