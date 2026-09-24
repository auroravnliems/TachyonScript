package dev.tachyonscript.cli;

import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles every TachyonScript example in the documentation, so examples cannot rot.
 * {@code ```tys} blocks are complete scripts; {@code ```tys-body} blocks are statements,
 * compiled inside a {@code player.join} handler.
 */
class DocumentationExamplesTest {

    private record Example(Path file, int line, String language, String code) {
        SourceFile source() {
            String name = file.getFileName() + ":" + line + ".tys";
            if (language.equals("tys-body")) {
                StringBuilder wrapped = new StringBuilder("event player.join {\n");
                code.lines().forEach(l -> wrapped.append("    ").append(l).append('\n'));
                return new SourceFile(name, wrapped.append("}\n").toString());
            }
            return new SourceFile(name, code);
        }
    }

    @Test
    void everyExampleCompiles() throws IOException {
        List<Example> examples = examples(Path.of("../docs"));
        examples.addAll(examples(Path.of("../README.md")));
        assertTrue(examples.size() >= 15, "found only " + examples.size() + " examples");
        List<String> failures = new ArrayList<>();
        Compiler compiler = new Compiler(StandardLibrary.registry());
        for (Example example : examples) {
            CompilationResult result = compiler.compile(List.of(example.source()));
            for (Diagnostic diagnostic : result.diagnostics().sorted()) {
                if (diagnostic.isError()) {
                    failures.add(example.file() + " line " + example.line() + ":\n"
                            + DiagnosticRenderer.plain().render(diagnostic));
                }
            }
        }
        assertEquals(List.of(), failures, () -> String.join("\n", failures));
    }

    private static List<Example> examples(Path root) throws IOException {
        List<Path> files;
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            }
        } else {
            files = Files.exists(root) ? List.of(root) : List.of();
        }
        List<Example> examples = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String fence = lines.get(i).strip();
                if (!fence.equals("```tys") && !fence.equals("```tys-body")) {
                    continue;
                }
                StringBuilder code = new StringBuilder();
                int start = i + 1;
                int indent = lines.get(i).indexOf('`');
                for (i = start; i < lines.size() && !lines.get(i).strip().equals("```"); i++) {
                    String line = lines.get(i);
                    code.append(line.length() >= indent ? line.substring(Math.min(indent, leading(line))) : line).append('\n');
                }
                examples.add(new Example(file, start + 1, fence.substring(3), code.toString()));
            }
        }
        return examples;
    }

    private static int leading(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }
}
