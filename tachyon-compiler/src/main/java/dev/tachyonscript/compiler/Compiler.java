package dev.tachyonscript.compiler;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.ir.opt.Optimizer;
import dev.tachyonscript.ir.verify.IrVerifier;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.lexer.LexResult;
import dev.tachyonscript.language.lexer.Lexer;
import dev.tachyonscript.language.parser.Parser;
import dev.tachyonscript.language.semantic.Binder;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;
import dev.tachyonscript.language.syntax.SourceUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compiles script files: lexing, parsing, binding, lowering, optimization and verification.
 *
 * <p>Files are processed in path order so that the same inputs always produce the same
 * output. Each file is compiled independently; a failure in one file never affects the
 * others. Exceptions thrown by the compiler itself are converted into an
 * {@link DiagnosticCode#INTERNAL_ERROR} diagnostic for that file and handed to the
 * {@link InternalErrorHandler}; they are never disguised as script errors.
 *
 * <p>A {@code Compiler} is immutable and thread-safe; each {@link #compile} call uses its
 * own state.
 */
public final class Compiler {

    private final SymbolRegistry registry;
    private final CompilerOptions options;
    private final InternalErrorHandler internalErrors;

    public Compiler(SymbolRegistry registry, CompilerOptions options, InternalErrorHandler internalErrors) {
        this.registry = registry;
        this.options = options;
        this.internalErrors = internalErrors;
    }

    public Compiler(SymbolRegistry registry) {
        this(registry, CompilerOptions.DEFAULT, InternalErrorHandler.IGNORE);
    }

    public CompilationResult compile(Collection<SourceFile> sources) {
        List<SourceFile> files = new ArrayList<>(sources);
        files.sort(Comparator.comparing(SourceFile::path));
        DiagnosticCollector diagnostics = new DiagnosticCollector(options.errorLimit());
        long[] time = new long[6];
        int[] instructions = new int[1];
        List<CompiledModule> modules = new ArrayList<>();
        Map<String, SourceFile> moduleNames = new HashMap<>();
        for (SourceFile file : files) {
            CompiledModule module = compileFile(file, diagnostics, time, instructions);
            SourceFile previous = moduleNames.putIfAbsent(module.name(), file);
            if (previous != null) {
                diagnostics.report(Diagnostic.builder(DiagnosticCode.DUPLICATE_DECLARATION, file, Span.at(0),
                        "Module '" + module.name() + "' is also declared by " + previous.path() + ".").build());
                module = new CompiledModule(file, module.name(), module.bound(), null);
            }
            modules.add(module);
        }
        CompilationTimings timings = new CompilationTimings(time[0], time[1], time[2], time[3], time[4], time[5],
                files.size(), instructions[0]);
        return new CompilationResult(modules, diagnostics, timings);
    }

    private CompiledModule compileFile(SourceFile file, DiagnosticCollector diagnostics, long[] time, int[] instructions) {
        String path = file.path();
        String fallbackName = (path.endsWith(".tys") ? path.substring(0, path.length() - 4) : path).replace('/', '.');
        if (file.length() > options.maxSourceLength()) {
            diagnostics.report(Diagnostic.builder(DiagnosticCode.SOURCE_TOO_LARGE, file, Span.at(0),
                    "Script is too large (" + file.length() + " characters; the limit is "
                            + options.maxSourceLength() + ").").build());
            return new CompiledModule(file, fallbackName, null, null);
        }
        String phase = "lexing";
        try {
            long start = System.nanoTime();
            LexResult lexed = Lexer.lex(file, diagnostics);
            time[0] += System.nanoTime() - start;

            phase = "parsing";
            start = System.nanoTime();
            SourceUnit unit = Parser.parse(lexed, diagnostics);
            time[1] += System.nanoTime() - start;
            String name = Binder.moduleName(unit);

            phase = "type checking";
            start = System.nanoTime();
            BoundModule bound = Binder.bind(unit, registry, diagnostics);
            time[2] += System.nanoTime() - start;
            if (diagnostics.hasErrors(file)) {
                return new CompiledModule(file, name, bound, null);
            }

            phase = "IR generation";
            start = System.nanoTime();
            IrModule ir = Lowering.lower(bound);
            time[3] += System.nanoTime() - start;

            if (options.optimize()) {
                phase = "optimization";
                start = System.nanoTime();
                ir = Optimizer.optimize(ir);
                time[4] += System.nanoTime() - start;
            }

            phase = "IR verification";
            start = System.nanoTime();
            IrVerifier.verify(ir);
            time[5] += System.nanoTime() - start;
            for (IrFunction function : ir.functions()) {
                instructions[0] += function.instructionCount();
            }
            return new CompiledModule(file, name, bound, ir);
        } catch (RuntimeException | StackOverflowError error) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            internalErrors.report(phase, file, error, id);
            diagnostics.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR, file, Span.at(0),
                            "Internal TachyonScript compiler error during " + phase + ".")
                    .note("This is a bug in TachyonScript, not in your script. Please report it with the "
                            + "diagnostic ID and the log file.")
                    .note("Compiler " + TachyonVersion.RUNTIME + " (language level " + TachyonVersion.LANGUAGE_LEVEL
                            + "), diagnostic ID " + id + ": " + error)
                    .build());
            return new CompiledModule(file, fallbackName, null, null);
        }
    }
}
