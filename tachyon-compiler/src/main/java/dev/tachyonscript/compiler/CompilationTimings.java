package dev.tachyonscript.compiler;

import java.util.Locale;

/**
 * Time spent in each compilation stage, in nanoseconds, plus size figures.
 */
public record CompilationTimings(long lexing, long parsing, long binding, long lowering, long optimizing,
                                 long verifying, int files, int irInstructions) {

    public long total() {
        return lexing + parsing + binding + lowering + optimizing + verifying;
    }

    /** One-line summary, e.g. {@code parse 1.2 ms, check 0.8 ms, IR 0.5 ms, total 2.9 ms}. */
    public String summary() {
        return String.format(Locale.ROOT, "parse %.1f ms, check %.1f ms, IR %.1f ms, total %.1f ms",
                (lexing + parsing) / 1e6, binding / 1e6, (lowering + optimizing + verifying) / 1e6, total() / 1e6);
    }
}
