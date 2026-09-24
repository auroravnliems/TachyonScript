package dev.tachyonscript.compiler;

/**
 * Compilation limits and switches.
 *
 * @param maxSourceLength maximum characters per file; larger files are rejected before lexing
 * @param errorLimit      maximum errors reported per file
 * @param optimize        whether to run the IR optimizer
 */
public record CompilerOptions(int maxSourceLength, int errorLimit, boolean optimize) {

    /** 1 MiB of text per script file is far beyond any hand-written script. */
    public static final CompilerOptions DEFAULT = new CompilerOptions(1 << 20, 50, true);

    public CompilerOptions {
        if (maxSourceLength <= 0 || errorLimit <= 0) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }
}
