package dev.tachyonscript.runtime.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A runtime error in a script, with a TachyonScript stack trace instead of a Java one.
 *
 * <pre>
 * TachyonRuntimeError: Division by zero.
 *   at combat.tys:48 (event entity.damage)
 *       let ratio = hits / misses
 *                   ^^^^^^^^^^^^^
 *   at combat.tys:12 (function rate)
 * </pre>
 *
 * <p>The Java cause (if any) is kept for debug mode. Java stack traces are not captured for
 * these exceptions: script errors are ordinary events and must stay cheap.
 */
public final class ScriptRuntimeException extends RuntimeException {

    /** Broad category, used for rate limiting and metrics. */
    public enum Kind {
        /** A native reported an expected failure ({@code ScriptError}). */
        SCRIPT,
        /** A host operation threw an unexpected exception. */
        NATIVE,
        DIVISION_BY_ZERO,
        CAST,
        INDEX,
        NULL,
        TIMEOUT,
        RECURSION,
        /** Invalid runtime state; indicates a TachyonScript bug. */
        INTERNAL
    }

    private final Kind kind;
    private final List<ScriptFrame> frames = new ArrayList<>();

    public ScriptRuntimeException(Kind kind, String message, Throwable cause) {
        super(message, cause, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Frames, innermost first. */
    public List<ScriptFrame> frames() {
        return Collections.unmodifiableList(frames);
    }

    /** Appends the next outer frame (called while the error unwinds through script calls). */
    public ScriptRuntimeException addFrame(ScriptFrame frame) {
        frames.add(frame);
        return this;
    }

    /** Readable report; includes the Java cause only when {@code debug} is set. */
    public String render(boolean debug) {
        StringBuilder out = new StringBuilder("TachyonRuntimeError: ").append(getMessage());
        for (int i = 0; i < frames.size(); i++) {
            ScriptFrame frame = frames.get(i);
            out.append("\n  at ").append(frame.path()).append(':').append(frame.line())
                    .append(" (").append(frame.function()).append(')');
            if (i == 0 && !frame.lineText().isBlank()) {
                String text = frame.lineText().replace('\t', ' ');
                out.append("\n      ").append(text);
                int column = Math.max(1, frame.column());
                int length = Math.max(1, Math.min(frame.length(), Math.max(1, text.length() - column + 1)));
                out.append("\n      ").append(" ".repeat(column - 1)).append("^".repeat(length));
            }
        }
        if (debug && getCause() != null) {
            out.append("\n  caused by: ").append(getCause());
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return render(false);
    }
}
