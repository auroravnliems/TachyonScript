package dev.tachyonscript.api.value;

import java.util.Objects;

/**
 * An error caught by a script ({@code catch e { ... }}): the value of {@code e}.
 *
 * <p>It keeps the original exception so that {@code throw e} rethrows the same error with its
 * script stack trace.
 */
public final class ScriptFailure {

    private final String message;
    private final String kind;
    private final String location;
    private final RuntimeException exception;

    /**
     * @param message   the error message shown to users
     * @param kind      category in lower case, e.g. {@code thrown}, {@code index}, {@code division},
     *                  {@code null}, {@code cast}, {@code native}, {@code script}
     * @param location  where the error happened, {@code path:line}, or an empty string
     * @param exception the original exception (rethrown by {@code throw e})
     */
    public ScriptFailure(String message, String kind, String location, RuntimeException exception) {
        this.message = Objects.requireNonNull(message, "message");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.location = Objects.requireNonNull(location, "location");
        this.exception = Objects.requireNonNull(exception, "exception");
    }

    public String message() {
        return message;
    }

    public String kind() {
        return kind;
    }

    public String location() {
        return location;
    }

    public RuntimeException exception() {
        return exception;
    }

    @Override
    public String toString() {
        return message;
    }
}
