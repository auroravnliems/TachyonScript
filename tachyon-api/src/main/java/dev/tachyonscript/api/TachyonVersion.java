package dev.tachyonscript.api;

/**
 * Version identifiers of the TachyonScript runtime and of the language it accepts.
 *
 * <p>The two are versioned independently: many runtime releases can accept the same
 * language level, and caches or tooling that depend on language semantics key on
 * {@link #LANGUAGE_LEVEL} rather than on the runtime version.
 */
public final class TachyonVersion {

    /** Runtime (implementation) version. */
    public static final String RUNTIME = "0.1.0-SNAPSHOT";

    /** Language level accepted by this compiler. Incremented on incompatible language changes. */
    public static final int LANGUAGE_LEVEL = 1;

    /** Version of the IR format. Incremented whenever serialized or cached IR would change meaning. */
    public static final int IR_FORMAT = 1;

    private TachyonVersion() {
    }
}
