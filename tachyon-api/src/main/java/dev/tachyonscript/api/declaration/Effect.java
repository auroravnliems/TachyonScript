package dev.tachyonscript.api.declaration;

/**
 * Side-effect classification of a native operation.
 *
 * <p>Effects are metadata for the compiler: they enable optimizations (only {@link #PURE}
 * operations may be evaluated ahead of time) and future safety diagnostics. An operation
 * without any declared effect is treated conservatively, as if it could do anything.
 */
public enum Effect {
    /**
     * The result depends only on the arguments and the call has no observable side effect.
     * The compiler or linker may evaluate the call once when every argument is constant.
     */
    PURE,
    /** Reads server or world state. */
    READS_WORLD,
    /** Modifies server or world state. */
    MODIFIES_WORLD,
    /** Performs external I/O (files, network, databases). Never allowed on tick threads. */
    IO
}
