package dev.tachyonscript.engine;

import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.runtime.interpreter.RuntimeLimits;

import java.util.Objects;

/**
 * Engine configuration.
 *
 * @param mode               behaviour when some scripts fail
 * @param compiler           compiler limits
 * @param limits             runtime safety limits
 * @param slowThresholdNanos executions slower than this are reported (0 disables the check)
 * @param debug              include Java causes in runtime error reports
 */
public record EngineOptions(LoadMode mode, CompilerOptions compiler, RuntimeLimits limits, long slowThresholdNanos,
                            boolean debug) {

    public static final EngineOptions DEFAULT = new EngineOptions(LoadMode.LENIENT, CompilerOptions.DEFAULT,
            RuntimeLimits.DEFAULT, 5_000_000L, false);

    public EngineOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(limits, "limits");
        if (slowThresholdNanos < 0) {
            throw new IllegalArgumentException("slowThresholdNanos must not be negative");
        }
    }
}
