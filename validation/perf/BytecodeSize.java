import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.bytecode.BytecodeCompiler;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.stdlib.StandardLibrary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Sizes of the JVM methods the bytecode backend generates for a real script archive, against
 * HotSpot's 8000-byte limit above which a method is never JIT-compiled. Read-only, no network.
 */
public class BytecodeSize {
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
        int functions = 0, huge = 0, instructions = 0;
        long bytes = 0;
        List<String> largest = new ArrayList<>();
        for (var module : result.modules()) {
            var assembled = Assembler.assemble(module.ir());
            for (var unit : assembled.units()) {
                int size = BytecodeCompiler.methodSize(BytecodeCompiler.generate(unit, assembled.source()));
                functions++;
                bytes += size;
                instructions += unit.code().length;
                if (size > BytecodeCompiler.JIT_LIMIT) huge++;
                largest.add(String.format(Locale.ROOT, "%6d bytes  %5d code ints  %s %s", size, unit.code().length,
                        assembled.source().path(), unit.key()));
            }
        }
        largest.sort(Comparator.reverseOrder());
        largest.stream().limit(12).forEach(System.out::println);
        // Load-time cost of the backend: generating and defining every class, cold and warm.
        for (int round = 1; round <= 10; round++) {
            long start = System.nanoTime();
            int defined = 0;
            for (var module : result.modules()) {
                var assembled = Assembler.assemble(module.ir());
                for (var unit : assembled.units()) {
                    dev.tachyonscript.runtime.interpreter.BytecodeBody.define(
                            BytecodeCompiler.generate(unit, assembled.source()), unit.key());
                    defined++;
                }
            }
            if (round == 1 || round == 10) {
                System.out.printf(Locale.ROOT, "round %d: generated and defined %d classes in %.1f ms%n", round, defined,
                        (System.nanoTime() - start) / 1e6);
            }
        }
        System.out.printf(Locale.ROOT, "functions=%d total=%d bytes, over the %d-byte JIT limit: %d; bytes per code int %.1f%n",
                functions, bytes, BytecodeCompiler.JIT_LIMIT, huge, bytes / (double) instructions);
    }
}
