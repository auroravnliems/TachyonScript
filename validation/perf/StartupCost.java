import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.security.SecurityAnalyzer;
import dev.tachyonscript.security.SecurityOptions;
import dev.tachyonscript.stdlib.StandardLibrary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Measures what a (re)load costs before activation for a real script archive: compilation,
 * the deterministic security analysis and assembly. Read-only: the archive is never extracted,
 * no script runs and nothing is sent anywhere. Only the scripts/ entries are read.
 *
 * <p>Usage: java -cp <module jars> validation/perf/StartupCost.java <archive.zip> [rounds]
 */
public class StartupCost {
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
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        long bytes = sources.stream().mapToLong(source -> source.content().length()).sum();
        System.out.printf(Locale.ROOT, "scripts=%d characters=%d%n", sources.size(), bytes);
        long registryStart = System.nanoTime();
        var registry = StandardLibrary.registry();
        System.out.printf(Locale.ROOT, "standard library registry: %.1f ms%n", (System.nanoTime() - registryStart) / 1e6);
        for (int round = 1; round <= rounds; round++) {
            long start = System.nanoTime();
            CompilationResult result = new Compiler(registry).compile(sources);
            long compiled = System.nanoTime();
            if (!result.succeeded()) throw new AssertionError("Archive did not compile");
            new SecurityAnalyzer(SecurityOptions.defaults()).analyze(result.modules().stream().map(m -> m.bound()).toList());
            long analyzed = System.nanoTime();
            int units = 0;
            for (var module : result.modules()) units += Assembler.assemble(module.ir()).units().size();
            long assembled = System.nanoTime();
            if (round == 1 || round == rounds || round % 5 == 0) {
                System.out.printf(Locale.ROOT, "round %2d: compile %.1f ms, security analysis %.1f ms, assembly %.1f ms (%d functions)%n",
                        round, (compiled - start) / 1e6, (analyzed - compiled) / 1e6, (assembled - analyzed) / 1e6, units);
            }
        }
    }
}
