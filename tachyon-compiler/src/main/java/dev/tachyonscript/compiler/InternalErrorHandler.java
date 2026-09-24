package dev.tachyonscript.compiler;

import dev.tachyonscript.language.source.SourceFile;

/**
 * Receives unexpected exceptions thrown by the compiler itself (compiler bugs). The user
 * sees a short "internal compiler error" diagnostic with {@code diagnosticId}; the handler is
 * responsible for keeping the full Java stack trace (for example in a log file).
 */
@FunctionalInterface
public interface InternalErrorHandler {

    /** Discards details (tests and tools that surface diagnostics only). */
    InternalErrorHandler IGNORE = (phase, file, error, diagnosticId) -> {
    };

    void report(String phase, SourceFile file, Throwable error, String diagnosticId);
}
