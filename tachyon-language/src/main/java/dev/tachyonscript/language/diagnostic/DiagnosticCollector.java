package dev.tachyonscript.language.diagnostic;

import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects diagnostics during compilation.
 *
 * <p>Errors are capped per file so that a badly broken file cannot flood the console; once
 * the cap is reached a single {@link DiagnosticCode#TOO_MANY_DIAGNOSTICS} note is recorded
 * and further errors for that file are dropped. Not thread-safe: each compilation task
 * uses its own collector.
 */
public final class DiagnosticCollector {

    /** Default maximum number of errors reported per file. */
    public static final int DEFAULT_ERROR_LIMIT = 50;

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<SourceFile, Integer> errorsPerFile = new HashMap<>();
    private final int errorLimit;
    private int errorCount;
    private int warningCount;

    public DiagnosticCollector() {
        this(DEFAULT_ERROR_LIMIT);
    }

    public DiagnosticCollector(int errorLimit) {
        if (errorLimit < 1) {
            throw new IllegalArgumentException("errorLimit must be positive");
        }
        this.errorLimit = errorLimit;
    }

    public void report(Diagnostic diagnostic) {
        if (diagnostic.isError()) {
            int count = errorsPerFile.merge(diagnostic.file(), 1, Integer::sum);
            if (count > errorLimit) {
                if (count == errorLimit + 1) {
                    diagnostics.add(Diagnostic.builder(DiagnosticCode.TOO_MANY_DIAGNOSTICS, diagnostic.file(),
                            diagnostic.span(), "Too many errors in " + diagnostic.file().path()
                                    + "; further errors are not shown.").build());
                }
                errorCount++;
                return;
            }
            errorCount++;
        } else if (diagnostic.severity() == Severity.WARNING) {
            warningCount++;
        }
        diagnostics.add(diagnostic);
    }

    public void error(DiagnosticCode code, SourceFile file, Span span, String message) {
        report(Diagnostic.builder(code, file, span, message).build());
    }

    public void addAll(DiagnosticCollector other) {
        for (Diagnostic diagnostic : other.diagnostics) {
            report(diagnostic);
        }
    }

    /** Diagnostics in the order they were reported. */
    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /** Diagnostics sorted by file path and position, for stable output. */
    public List<Diagnostic> sorted() {
        List<Diagnostic> copy = new ArrayList<>(diagnostics);
        copy.sort((a, b) -> {
            int byPath = a.file().path().compareTo(b.file().path());
            if (byPath != 0) {
                return byPath;
            }
            return Integer.compare(a.span().start(), b.span().start());
        });
        return copy;
    }

    public boolean hasErrors() {
        return errorCount > 0;
    }

    public boolean hasErrors(SourceFile file) {
        return errorsPerFile.getOrDefault(file, 0) > 0;
    }

    /** Total errors, including those dropped because of the per-file limit. */
    public int errorCount() {
        return errorCount;
    }

    public int warningCount() {
        return warningCount;
    }
}
