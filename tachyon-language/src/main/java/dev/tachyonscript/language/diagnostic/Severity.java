package dev.tachyonscript.language.diagnostic;

/** Severity of a diagnostic. Only {@link #ERROR} prevents a script from loading. */
public enum Severity {
    ERROR,
    WARNING,
    INFO,
    HINT
}
