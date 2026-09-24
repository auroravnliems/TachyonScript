package dev.tachyonscript.compiler;

import dev.tachyonscript.language.diagnostic.DiagnosticCollector;

import java.util.List;

/**
 * Output of a {@link Compiler} run: per-file modules, all diagnostics and timings.
 */
public record CompilationResult(List<CompiledModule> modules, DiagnosticCollector diagnostics,
                                CompilationTimings timings) {

    public CompilationResult {
        modules = List.copyOf(modules);
    }

    /** Whether every file compiled without errors. */
    public boolean succeeded() {
        return !diagnostics.hasErrors() && modules.stream().allMatch(CompiledModule::succeeded);
    }

    public List<CompiledModule> successfulModules() {
        return modules.stream().filter(CompiledModule::succeeded).toList();
    }
}
