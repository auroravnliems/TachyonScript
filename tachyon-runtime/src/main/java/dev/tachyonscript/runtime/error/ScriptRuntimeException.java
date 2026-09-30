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

    /** Broad category, used for rate limiting, metrics and {@code catch}. */
    public enum Kind {
        /** A native reported an expected failure ({@code ScriptError}). */
        SCRIPT("script", true),
        /** A host operation threw an unexpected exception. */
        NATIVE("native", true),
        DIVISION_BY_ZERO("division", true),
        CAST("cast", true),
        INDEX("index", true),
        NULL("null", true),
        /** The script raised the error with {@code throw}. */
        THROWN("thrown", true),
        /** The execution ran too long; cannot be caught, so a runaway script always stops. */
        TIMEOUT("timeout", false),
        /** Calls nested too deeply; cannot be caught. */
        RECURSION("recursion", false),
        /** Invalid runtime state; indicates a TachyonScript bug. Cannot be caught. */
        INTERNAL("internal", false);

        private final String scriptName;
        private final boolean catchable;

        Kind(String scriptName, boolean catchable) {
            this.scriptName = scriptName;
            this.catchable = catchable;
        }

        /** The name scripts see as {@code error.kind}. */
        public String scriptName() {
            return scriptName;
        }

        /** Whether {@code try}/{@code catch} can handle errors of this kind. */
        public boolean isCatchable() {
            return catchable;
        }
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

    /**
     * A new exception with the same kind, message, cause and frames: used when a caught error
     * is thrown again, so that an error value shared between threads is never changed.
     */
    public ScriptRuntimeException copy() {
        ScriptRuntimeException copy = new ScriptRuntimeException(kind, getMessage(), getCause());
        copy.frames.addAll(frames);
        return copy;
    }

    /** Where the error happened, {@code path:line}, or an empty string when unknown. */
    public String location() {
        if (frames.isEmpty()) {
            return "";
        }
        ScriptFrame frame = frames.getFirst();
        return frame.path() + ":" + frame.line();
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
            // A function calling itself shows up once, not once per call (up to the recursion limit).
            int same = 0;
            while (i + 1 < frames.size() && sameCall(frames.get(i + 1), frame)) {
                i++;
                same++;
            }
            if (same > 0) {
                out.append("\n  ... ").append(same).append(same == 1 ? " more call" : " more calls")
                        .append(" at the same place");
            }
        }
        if (debug && getCause() != null) {
            out.append("\n  caused by: ").append(getCause());
        }
        return out.toString();
    }

    private static boolean sameCall(ScriptFrame a, ScriptFrame b) {
        return a.line() == b.line() && a.path().equals(b.path()) && a.function().equals(b.function());
    }

    @Override
    public String toString() {
        return render(false);
    }
}
