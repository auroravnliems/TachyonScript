package dev.tachyonscript.runtime.interpreter;

/**
 * Safety limits applied to every script execution.
 *
 * @param maxCallDepth       maximum nesting of script function calls (recursion limit)
 * @param maxExecutionNanos  maximum wall time of one top-level execution before it is
 *                           aborted as a runaway loop
 * @param loopCheckInterval  loop back edges between two clock checks (a power of two keeps
 *                           the check a cheap counter decrement)
 */
public record RuntimeLimits(int maxCallDepth, long maxExecutionNanos, int loopCheckInterval) {

    /** 128 nested calls, one second per execution, clock checked every 1024 iterations. */
    public static final RuntimeLimits DEFAULT = new RuntimeLimits(128, 1_000_000_000L, 1024);

    public RuntimeLimits {
        if (maxCallDepth < 1 || maxExecutionNanos < 1 || loopCheckInterval < 1) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }
}
