package dev.tachyonscript.compiler;

import dev.tachyonscript.ir.opt.Optimizer;

import java.util.Set;

/**
 * Compilation limits and switches.
 *
 * @param maxSourceLength maximum characters per file; larger files are rejected before lexing
 * @param errorLimit      maximum errors reported per file
 * @param optimize        whether to run the IR optimizer
 * @param disabledPasses  optimizer pass identifiers disabled for debugging
 */
public record CompilerOptions(int maxSourceLength, int errorLimit, boolean optimize, Set<String> disabledPasses) {

    /** 1 MiB of text per script file is far beyond any hand-written script. */
    public static final CompilerOptions DEFAULT = new CompilerOptions(1 << 20, 50, true);

    public CompilerOptions {
        disabledPasses = Set.copyOf(disabledPasses);
        if (maxSourceLength <= 0 || errorLimit <= 0) {
            throw new IllegalArgumentException("Limits must be positive");
        }
        for (String name : disabledPasses) {
            if (!Optimizer.passes().contains(name)) {
                throw new IllegalArgumentException("Unknown optimization pass: " + name);
            }
        }
    }

    /** Preserves the original constructor for compiler/engine consumers. */
    public CompilerOptions(int maxSourceLength, int errorLimit, boolean optimize) {
        this(maxSourceLength, errorLimit, optimize, Set.of());
    }

    public CompilerOptions withOptimization(boolean enabled) {
        return new CompilerOptions(maxSourceLength, errorLimit, enabled, disabledPasses);
    }

    public CompilerOptions withDisabledPasses(Set<String> disabled) {
        return new CompilerOptions(maxSourceLength, errorLimit, optimize, disabled);
    }
}
