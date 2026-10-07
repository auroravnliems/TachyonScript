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
import dev.tachyonscript.language.syntax.Declaration;
import dev.tachyonscript.language.syntax.SourceUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compiles script files: lexing, parsing, binding, lowering, optimization and verification.
 *
 * <p>Files are processed in path order so that the same inputs always produce the same
 * output. A file may import other modules ({@code import economy}); modules are bound after
 * the modules they import, which are either among the files being compiled or given as
 * already compiled {@code available} modules. Import cycles are reported. A failure in one
 * file only affects the files that import it. Exceptions thrown by the compiler itself are
 * converted into an {@link DiagnosticCode#INTERNAL_ERROR} diagnostic for that file and handed
 * to the {@link InternalErrorHandler}; they are never disguised as script errors.
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

    /** A parsed file waiting to be bound. */
    private record Parsed(SourceFile file, SourceUnit unit, String name) {
    }

    /** Compiles files that import nothing but each other. */
    public CompilationResult compile(Collection<SourceFile> sources) {
        return compile(sources, Map.of());
    }

    /**
     * Compiles {@code sources}. {@code available} are compiled modules that are not part of
     * {@code sources} (for example unchanged scripts on a reload) which the sources may import,
     * by module name.
     */
    public CompilationResult compile(Collection<SourceFile> sources, Map<String, BoundModule> available) {
        List<SourceFile> files = new ArrayList<>(sources);
        files.sort(Comparator.comparing(SourceFile::path));
        DiagnosticCollector diagnostics = new DiagnosticCollector(options.errorLimit());
        long[] time = new long[6];
        int[] instructions = new int[1];
        Map<SourceFile, CompiledModule> results = new LinkedHashMap<>();
        files.forEach(file -> results.put(file, null));

        // 1. Parse every file.
        List<Parsed> parsed = new ArrayList<>();
        for (SourceFile file : files) {
            Parsed result = parse(file, diagnostics, time);
            if (result == null) {
                results.put(file, new CompiledModule(file, fallbackName(file), null, null));
            } else {
                parsed.add(result);
            }
        }

        // 2. Module names must be unique.
        Map<String, Parsed> byName = new LinkedHashMap<>();
        Set<String> failed = new HashSet<>();
        for (Parsed file : parsed) {
            String previous = byName.containsKey(file.name()) ? byName.get(file.name()).file().path()
                    : available.containsKey(file.name()) ? available.get(file.name()).file().path() : null;
            if (previous != null) {
                diagnostics.report(Diagnostic.builder(DiagnosticCode.DUPLICATE_DECLARATION, file.file(), moduleSpan(file.unit()),
                        "Module '" + file.name() + "' is also declared by " + previous + ".").build());
                results.put(file.file(), new CompiledModule(file.file(), file.name(), null, null));
                continue;
            }
            byName.put(file.name(), file);
        }

        // 3. Order: every module after the modules it imports; cycles are errors.
        List<Parsed> order = new ArrayList<>();
        Map<String, Integer> state = new HashMap<>();
        for (Parsed file : byName.values()) {
            visit(file, byName, state, order, new ArrayList<>(), diagnostics, failed);
        }

        // 4. Bind, lower and verify in that order.
        Map<String, BoundModule> bound = new HashMap<>(available);
        Set<String> known = new HashSet<>(available.keySet());
        known.addAll(byName.keySet());
        for (Parsed file : order) {
            if (failed.contains(file.name())) {
                results.put(file.file(), new CompiledModule(file.file(), file.name(), null, null));
                continue;
            }
            CompiledModule module = compileParsed(file, bound, failed, known, diagnostics, time, instructions);
            if (module.succeeded()) {
                bound.put(file.name(), module.bound());
            } else {
                failed.add(file.name());
            }
            results.put(file.file(), module);
        }
        CompilationTimings timings = new CompilationTimings(time[0], time[1], time[2], time[3], time[4], time[5],
                files.size(), instructions[0]);
        return new CompilationResult(new ArrayList<>(results.values()), diagnostics, timings);
    }

    /** Depth-first topological sort; {@code state}: 1 = in progress, 2 = done. */
    private static void visit(Parsed file, Map<String, Parsed> byName, Map<String, Integer> state, List<Parsed> order,
                              List<Parsed> path, DiagnosticCollector diagnostics, Set<String> failed) {
        Integer current = state.get(file.name());
        if (current != null && current == 2) {
            return;
        }
        if (current != null) {
            // A cycle: every module on it from 'file' onwards imports the next one.
            int start = path.indexOf(file);
            List<Parsed> cycle = path.subList(start, path.size());
            StringBuilder chain = new StringBuilder();
            cycle.forEach(member -> chain.append(member.name()).append(" -> "));
            chain.append(file.name());
            for (int i = 0; i < cycle.size(); i++) {
                Parsed member = cycle.get(i);
                Parsed next = i + 1 < cycle.size() ? cycle.get(i + 1) : file;
                if (failed.add(member.name())) {
                    diagnostics.report(Diagnostic.builder(DiagnosticCode.IMPORT_CYCLE, member.file(),
                                    importSpan(member.unit(), next.name()),
                                    "Modules import each other in a cycle: " + chain + ".")
                            .note("Move what both modules need into a third module that imports neither of them.")
                            .build());
                }
            }
            return;
        }
        state.put(file.name(), 1);
        path.add(file);
        for (String imported : Binder.importedModules(file.unit())) {
            Parsed dependency = byName.get(imported);
            if (dependency != null) {
                visit(dependency, byName, state, order, path, diagnostics, failed);
            }
        }
        path.removeLast();
        state.put(file.name(), 2);
        order.add(file);
    }

    private static Span importSpan(SourceUnit unit, String module) {
        for (Declaration.Import anImport : unit.imports()) {
            if (anImport.module().text().equals(module)) {
                return anImport.module().span();
            }
        }
        return moduleSpan(unit);
    }

    private static Span moduleSpan(SourceUnit unit) {
        return unit.module() != null ? unit.module().span() : Span.at(0);
    }

    private static String fallbackName(SourceFile file) {
        String path = file.path();
        return (path.endsWith(".tys") ? path.substring(0, path.length() - 4) : path).replace('/', '.');
    }

    private Parsed parse(SourceFile file, DiagnosticCollector diagnostics, long[] time) {
        if (file.length() > options.maxSourceLength()) {
            diagnostics.report(Diagnostic.builder(DiagnosticCode.SOURCE_TOO_LARGE, file, Span.at(0),
                    "Script is too large (" + file.length() + " characters; the limit is "
                            + options.maxSourceLength() + ").").build());
            return null;
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
            return new Parsed(file, unit, Binder.moduleName(unit));
        } catch (RuntimeException | StackOverflowError error) {
            internalError(phase, file, error, diagnostics);
            return null;
        }
    }

    private CompiledModule compileParsed(Parsed file, Map<String, BoundModule> modules, Set<String> failed,
                                         Set<String> known, DiagnosticCollector diagnostics, long[] time,
                                         int[] instructions) {
        String phase = "type checking";
        try {
            long start = System.nanoTime();
            BoundModule bound = Binder.bind(file.unit(), registry, diagnostics, modules, failed, known);
            time[2] += System.nanoTime() - start;
            if (diagnostics.hasErrors(file.file())) {
                return new CompiledModule(file.file(), file.name(), bound, null);
            }

            phase = "IR generation";
            start = System.nanoTime();
            IrModule ir = Lowering.lower(bound);
            time[3] += System.nanoTime() - start;

            if (options.optimize()) {
                phase = "optimization";
                start = System.nanoTime();
                ir = Optimizer.optimize(ir, options.disabledPasses(), null);
                time[4] += System.nanoTime() - start;
            }

            phase = "IR verification";
            start = System.nanoTime();
            IrVerifier.verify(ir);
            time[5] += System.nanoTime() - start;
            for (IrFunction function : ir.functions()) {
                instructions[0] += function.instructionCount();
            }
            return new CompiledModule(file.file(), file.name(), bound, ir);
        } catch (RuntimeException | StackOverflowError error) {
            internalError(phase, file.file(), error, diagnostics);
            return new CompiledModule(file.file(), file.name(), null, null);
        }
    }

    private void internalError(String phase, SourceFile file, Throwable error, DiagnosticCollector diagnostics) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        internalErrors.report(phase, file, error, id);
        diagnostics.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR, file, Span.at(0),
                        "Internal TachyonScript compiler error during " + phase + ".")
                .note("This is a bug in TachyonScript, not in your script. Please report it with the "
                        + "diagnostic ID and the log file.")
                .note("Compiler " + TachyonVersion.RUNTIME + " (language level " + TachyonVersion.LANGUAGE_LEVEL
                        + "), diagnostic ID " + id + ": " + error)
                .build());
    }
}
