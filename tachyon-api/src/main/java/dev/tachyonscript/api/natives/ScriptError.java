package dev.tachyonscript.api.natives;

/**
 * An expected failure raised by a native function, reported to the script author with the
 * script location and without a Java stack trace (for example "Unknown world 'arena'").
 *
 * <p>The stack trace is not captured: these errors are part of normal operation and must
 * stay cheap.
 */
public class ScriptError extends RuntimeException {

    public ScriptError(String message) {
        super(message, null, false, false);
    }

    public ScriptError(String message, Throwable cause) {
        super(message, cause, false, false);
    }
}
