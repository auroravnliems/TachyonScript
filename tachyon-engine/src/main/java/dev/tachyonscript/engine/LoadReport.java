package dev.tachyonscript.engine;

import dev.tachyonscript.compiler.CompilationTimings;
import dev.tachyonscript.language.diagnostic.Diagnostic;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Outcome of a (re)load.
 *
 * @param activated     whether a new generation became active
 * @param generation    id of the active generation after the load
 * @param scripts       number of active scripts after the load
 * @param compiled      scripts compiled in this load
 * @param reused        unchanged scripts reused without recompiling
 * @param failed        paths of scripts that failed to compile or link
 * @param keptPrevious  failed scripts whose previous working version stays active
 * @param handlers      active event handlers after the load
 * @param diagnostics   compiler diagnostics (errors, warnings, hints)
 * @param linkProblems  link problems by script path
 * @param timings       compiler timings
 * @param totalNanos    wall time of the whole load
 * @param failure       why the load could not run at all (e.g. unreadable directory), or null
 */
public record LoadReport(boolean activated, long generation, int scripts, int compiled, int reused, List<String> failed,
                         List<String> keptPrevious, int handlers, List<Diagnostic> diagnostics,
                         Map<String, List<String>> linkProblems, CompilationTimings timings, long totalNanos,
                         String failure) {

    public LoadReport {
        failed = List.copyOf(failed);
        keptPrevious = List.copyOf(keptPrevious);
        diagnostics = List.copyOf(diagnostics);
        linkProblems = Map.copyOf(linkProblems);
    }

    public long errorCount() {
        return diagnostics.stream().filter(Diagnostic::isError).count()
                + linkProblems.values().stream().mapToLong(List::size).sum();
    }

    public long warningCount() {
        return diagnostics.stream().filter(d -> d.severity() == dev.tachyonscript.language.diagnostic.Severity.WARNING).count();
    }

    /** One concise summary line, e.g. {@code 28 scripts, 64 event handlers (3 compiled, 25 unchanged) in 41.7 ms}. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append(scripts).append(scripts == 1 ? " script, " : " scripts, ")
                .append(handlers).append(handlers == 1 ? " event handler" : " event handlers")
                .append(" (").append(compiled).append(" compiled, ").append(reused).append(" unchanged)")
                .append(String.format(Locale.ROOT, " in %.1f ms", totalNanos / 1e6));
        if (!failed.isEmpty()) {
            out.append("; ").append(failed.size()).append(failed.size() == 1 ? " script failed" : " scripts failed");
        }
        return out.toString();
    }
}
