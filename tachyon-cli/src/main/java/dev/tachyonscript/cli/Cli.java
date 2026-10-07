package dev.tachyonscript.cli;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.CompiledModule;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.ir.IrPrinter;
import dev.tachyonscript.ir.opt.Optimizer;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.lexer.LexResult;
import dev.tachyonscript.language.lexer.Lexer;
import dev.tachyonscript.language.lexer.Token;
import dev.tachyonscript.language.parser.Parser;
import dev.tachyonscript.language.semantic.Binder;
import dev.tachyonscript.language.semantic.BoundPrinter;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.syntax.SourceUnit;
import dev.tachyonscript.language.syntax.SyntaxPrinter;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.runtime.bytecode.BytecodeCompiler;
import dev.tachyonscript.runtime.code.AssembledModule;
import dev.tachyonscript.runtime.code.CodeUnit;
import dev.tachyonscript.runtime.code.Disassembler;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;
import dev.tachyonscript.stdlib.StandardLibrary;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The {@code tys} command-line tool: checks scripts and prints the output of each compiler
 * stage, using the same compiler and standard library declarations as the plugin (no server
 * needed; natives are not linked).
 *
 * <p>Exit codes: 0 success, 1 compile errors, 2 usage or I/O errors.
 */
public final class Cli {

    static final int OK = 0;
    static final int COMPILE_ERRORS = 1;
    static final int USAGE = 2;

    private static final String USAGE_TEXT = """
            Usage: tys <command> [arguments]

            Commands:
              check <path>...                         Type-check scripts; a directory is read like the
                                                      plugin's scripts directory (recursively, skipping
                                                      names that start with '-'), a file on its own
              dump tokens|ast|bound|ir|code|passes|bytecode <file>
                                                      Print a compiler stage; passes shows verified
                                                      IR before optimization and after each pass;
                                                      bytecode shows the JVM classes of
                                                      runtime.backend: bytecode
              docs [--wiki]                           Print the standard library reference (Markdown;
                                                      --wiki: the API-Reference page of the wiki)
              version                                 Print version information
              help                                    Print this help

            Compiler options (check and dump):
              --no-optimize                           Disable the IR optimizer
              --disable-pass=<name>                   Disable a named pass (repeatable)
              --                                      Treat remaining arguments as paths/stages

            Exit codes: 0 success, 1 compile errors, 2 usage or I/O errors.
            Set NO_COLOR to disable colored output.
            """;

    private final PrintStream out;
    private final PrintStream err;
    private final boolean color;
    private final SymbolRegistry registry = StandardLibrary.registry();

    Cli(PrintStream out, PrintStream err, boolean color) {
        this.out = out;
        this.err = err;
        this.color = color;
    }

    public static void main(String[] args) {
        boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
        // Always UTF-8: scripts and the generated reference are UTF-8 text, and the platform
        // default would turn non-ASCII characters into '?' when output is redirected.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.exit(new Cli(out, err, color).run(args));
    }

    int run(String... args) {
        if (args.length == 0) {
            err.print(USAGE_TEXT);
            return USAGE;
        }
        List<String> rest = List.of(args).subList(1, args.length);
        try {
            return switch (args[0]) {
                case "check" -> check(rest);
                case "dump" -> dump(rest);
                case "docs" -> {
                    if (rest.size() > 1 || rest.size() == 1 && !rest.getFirst().equals("--wiki")) {
                        err.println("Usage: tys docs [--wiki]");
                        yield USAGE;
                    }
                    out.print(ReferenceGenerator.generate(registry, rest.isEmpty()
                            ? ReferenceGenerator.Target.DOCS : ReferenceGenerator.Target.WIKI));
                    yield OK;
                }
                case "version", "--version", "-v" -> {
                    out.println("tys " + TachyonVersion.RUNTIME + " (language level " + TachyonVersion.LANGUAGE_LEVEL
                            + ", IR format " + TachyonVersion.IR_FORMAT + ")");
                    yield OK;
                }
                case "help", "--help", "-h" -> {
                    out.print(USAGE_TEXT);
                    yield OK;
                }
                default -> {
                    err.println("Unknown command '" + args[0] + "'.");
                    err.print(USAGE_TEXT);
                    yield USAGE;
                }
            };
        } catch (IOException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return USAGE;
        }
    }

    // ================================================================= check

    private int check(List<String> arguments) throws IOException {
        CompilationArguments parsed = CompilationArguments.parse(arguments);
        List<String> paths = parsed.positional();
        if (paths.isEmpty()) {
            err.println("Usage: tys check <path>...");
            return USAGE;
        }
        int files = 0;
        int errors = 0;
        int warnings = 0;
        long nanos = 0;
        for (String argument : paths) {
            Path path = Path.of(argument);
            Batch batch = Batch.read(path);
            if (batch.files().isEmpty()) {
                continue;
            }
            CompilationResult result = compiler(parsed.options()).compile(batch.files());
            DiagnosticRenderer renderer = new DiagnosticRenderer(color, batch.displayPrefix());
            boolean unknownModule = false;
            for (Diagnostic diagnostic : result.diagnostics().sorted()) {
                out.println(renderer.render(diagnostic));
                unknownModule |= diagnostic.code() == DiagnosticCode.UNKNOWN_MODULE;
            }
            if (unknownModule && !Files.isDirectory(path)) {
                // A file is compiled on its own, so the modules it imports are not part of the check.
                Path parent = path.toAbsolutePath().getParent();
                out.println("Note: " + argument + " was checked on its own. To include the modules it imports, "
                        + "check its folder: tys check " + (parent == null ? "." : Batch.slashes(parent)));
                out.println();
            }
            files += batch.files().size();
            errors += result.diagnostics().errorCount();
            warnings += result.diagnostics().warningCount();
            nanos += result.timings().total();
        }
        out.printf(Locale.ROOT, "Checked %d %s: %d %s, %d %s (%.1f ms)%n", files, files == 1 ? "file" : "files",
                errors, errors == 1 ? "error" : "errors", warnings, warnings == 1 ? "warning" : "warnings", nanos / 1e6);
        return errors > 0 ? COMPILE_ERRORS : OK;
    }

    /**
     * Files compiled together. A directory is compiled as one scripts directory (paths
     * relative to it, as on the server); a single file on its own.
     */
    private record Batch(List<SourceFile> files, String displayPrefix) {

        static Batch read(Path path) throws IOException {
            if (Files.isDirectory(path)) {
                List<Path> found;
                try (Stream<Path> walk = Files.walk(path)) {
                    found = walk.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(".tys"))
                            .filter(file -> enabled(path, file))
                            .sorted()
                            .toList();
                }
                List<SourceFile> files = new ArrayList<>(found.size());
                for (Path file : found) {
                    files.add(new SourceFile(slashes(path.relativize(file)), Files.readString(file)));
                }
                String prefix = slashes(path);
                return new Batch(files, prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/");
            }
            if (!Files.isRegularFile(path)) {
                throw new IOException("No such file or directory: " + path);
            }
            Path parent = path.getParent();
            return new Batch(List.of(new SourceFile(path.getFileName().toString(), Files.readString(path))),
                    parent == null ? "" : slashes(parent) + "/");
        }

        private static boolean enabled(Path root, Path file) {
            for (Path part : root.relativize(file)) {
                if (part.toString().startsWith("-")) {
                    return false;
                }
            }
            return true;
        }

        private static String slashes(Path path) {
            return path.toString().replace('\\', '/');
        }
    }

    // ================================================================= dump

    private int dump(List<String> arguments) throws IOException {
        CompilationArguments parsed = CompilationArguments.parse(arguments);
        List<String> args = parsed.positional();
        if (args.size() != 2) {
            err.println("Usage: tys dump tokens|ast|bound|ir|code|passes|bytecode <file> [compiler options]");
            return USAGE;
        }
        String stage = args.get(0);
        Path path = Path.of(args.get(1));
        if (!Files.isRegularFile(path)) {
            throw new IOException("No such file: " + path);
        }
        SourceFile file = new SourceFile(path.getFileName().toString(), Files.readString(path));
        Path parent = path.getParent();
        DiagnosticRenderer renderer = new DiagnosticRenderer(color, parent == null ? "" : parent.toString().replace('\\', '/') + "/");
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        String text = switch (stage) {
            case "tokens" -> tokens(Lexer.lex(file, diagnostics));
            case "ast" -> SyntaxPrinter.print(Parser.parse(Lexer.lex(file, diagnostics), diagnostics));
            case "bound" -> {
                SourceUnit unit = Parser.parse(Lexer.lex(file, diagnostics), diagnostics);
                yield BoundPrinter.print(Binder.bind(unit, registry, diagnostics));
            }
            case "ir", "code", "passes", "bytecode" -> {
                CompilerOptions options = stage.equals("passes") ? parsed.options().withOptimization(false) : parsed.options();
                CompilationResult result = compiler(options).compile(List.of(file));
                diagnostics.addAll(result.diagnostics());
                CompiledModule module = result.modules().getFirst();
                if (!module.succeeded()) {
                    yield null;
                }
                if (stage.equals("passes")) {
                    StringBuilder snapshots = new StringBuilder("=== input ===\n").append(IrPrinter.print(module.ir()));
                    if (parsed.options().optimize()) {
                        Optimizer.optimize(module.ir(), parsed.options().disabledPasses(), (pass, ir) ->
                                snapshots.append("\n=== ").append(pass).append(" ===\n").append(IrPrinter.print(ir)));
                    }
                    yield snapshots.toString();
                }
                if (stage.equals("bytecode")) {
                    yield bytecode(Assembler.assemble(module.ir()));
                }
                yield stage.equals("ir") ? IrPrinter.print(module.ir()) : Disassembler.disassemble(Assembler.assemble(module.ir()));
            }
            default -> {
                err.println("Unknown stage '" + stage + "'. Use tokens, ast, bound, ir, code, passes or bytecode.");
                yield null;
            }
        };
        for (Diagnostic diagnostic : diagnostics.sorted()) {
            err.println(renderer.render(diagnostic));
        }
        if (text == null) {
            return diagnostics.hasErrors() ? COMPILE_ERRORS : USAGE;
        }
        out.print(text.endsWith("\n") ? text : text + "\n");
        return diagnostics.hasErrors() ? COMPILE_ERRORS : OK;
    }

    /** The JVM class of every function, as the bytecode backend would define it. */
    private static String bytecode(AssembledModule module) {
        StringBuilder out = new StringBuilder();
        for (CodeUnit unit : module.units()) {
            byte[] bytes = BytecodeCompiler.generate(unit, module.source());
            int size = BytecodeCompiler.methodSize(bytes);
            out.append("=== ").append(unit.displayName()).append(" [").append(unit.key()).append("]: ").append(size)
                    .append(" bytes of JVM code")
                    .append(size > BytecodeCompiler.JIT_LIMIT ? " (over the JIT limit: runs in the interpreter)" : "")
                    .append(" ===\n");
            StringWriter text = new StringWriter();
            new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(text)), 0);
            out.append(text).append('\n');
        }
        return out.toString();
    }

    private Compiler compiler(CompilerOptions options) {
        return new Compiler(registry, options, InternalErrorHandler.IGNORE);
    }

    private record CompilationArguments(List<String> positional, CompilerOptions options) {
        static CompilationArguments parse(List<String> arguments) {
            List<String> positional = new ArrayList<>();
            var disabled = new LinkedHashSet<String>();
            boolean optimize = true;
            boolean literal = false;
            for (String argument : arguments) {
                if (literal) {
                    positional.add(argument);
                } else if (argument.equals("--")) {
                    literal = true;
                } else if (argument.equals("--no-optimize")) {
                    optimize = false;
                } else if (argument.startsWith("--disable-pass=")) {
                    disabled.add(argument.substring("--disable-pass=".length()));
                } else if (argument.startsWith("--")) {
                    throw new IllegalArgumentException("Unknown compiler option '" + argument + "'. Available passes: "
                            + String.join(", ", Optimizer.passes()));
                } else {
                    positional.add(argument);
                }
            }
            return new CompilationArguments(List.copyOf(positional), CompilerOptions.DEFAULT
                    .withOptimization(optimize).withDisabledPasses(disabled));
        }
    }

    private static String tokens(LexResult lexed) {
        StringBuilder text = new StringBuilder();
        SourceFile file = lexed.file();
        for (Token token : lexed.tokens()) {
            text.append(String.format(Locale.ROOT, "%4d:%-4d %-16s", file.lineOf(token.start()), file.columnOf(token.start()),
                    token.kind()));
            if (!token.text().isEmpty() && !token.text().equals("\n")) {
                text.append(' ').append(token.text().replace("\n", "\\n"));
            }
            text.append('\n');
        }
        return text.toString();
    }
}
