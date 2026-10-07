import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.security.SecurityAnalyzer;
import dev.tachyonscript.security.SecurityJson;
import dev.tachyonscript.security.SecurityOptions;
import dev.tachyonscript.stdlib.StandardLibrary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Size of the security manifest each script would send to the AI reviewer. Read-only, no network. */
public class ManifestSize {
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
        var manifests = new SecurityAnalyzer(SecurityOptions.defaults()).analyze(result.modules().stream().map(m -> m.bound()).toList());
        long total = 0, nodes = 0;
        Map<String, Integer> byKind = new TreeMap<>();
        for (var entry : new TreeMap<>(manifests).entrySet()) {
            var manifest = entry.getValue();
            int bytes = SecurityJson.write(manifest.toJson()).getBytes(StandardCharsets.UTF_8).length;
            total += bytes;
            nodes += manifest.nodes().size();
            for (var node : manifest.nodes()) byKind.merge(node.capability().name(), 1, Integer::sum);
            System.out.printf(Locale.ROOT, "%-45s source %6d chars, manifest %7d bytes, %4d nodes%n", entry.getKey(),
                    sources.stream().filter(s -> s.path().equals(entry.getKey())).findFirst().orElseThrow().content().length(),
                    bytes, manifest.nodes().size());
        }
        System.out.printf(Locale.ROOT, "TOTAL manifest %d bytes, %d nodes; by capability %s%n", total, nodes, byKind);
    }
}
