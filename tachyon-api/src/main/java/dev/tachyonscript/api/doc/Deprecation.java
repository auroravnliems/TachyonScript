package dev.tachyonscript.api.doc;

import java.util.Objects;

/**
 * Marks a declaration as deprecated. The compiler reports a warning at each use and shows
 * the replacement.
 *
 * @param message        why the declaration is deprecated
 * @param replacement    what to use instead, as TachyonScript code (may be empty)
 * @param removalVersion runtime version in which the declaration will be removed (may be empty)
 */
public record Deprecation(String message, String replacement, String removalVersion) {

    public Deprecation {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(removalVersion, "removalVersion");
    }
}
