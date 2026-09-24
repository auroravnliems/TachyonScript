package dev.tachyonscript.language.source;

/**
 * A human-facing position: 1-based line and 1-based column (in UTF-16 code units, which
 * is also what the Language Server Protocol uses by default).
 */
public record SourceLocation(int line, int column) {

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
