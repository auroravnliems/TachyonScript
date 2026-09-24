package dev.tachyonscript.runtime.error;

/**
 * One frame of a TachyonScript stack trace.
 *
 * @param function display name, e.g. {@code event player.join} or {@code function reward}
 * @param path     script path relative to the scripts directory
 * @param line     1-based line (0 if unknown)
 * @param column   1-based column (0 if unknown)
 * @param lineText source text of the line (may be empty)
 * @param length   length of the highlighted span on that line
 */
public record ScriptFrame(String function, String path, int line, int column, String lineText, int length) {

    @Override
    public String toString() {
        return "at " + path + ":" + line + " (" + function + ")";
    }
}
