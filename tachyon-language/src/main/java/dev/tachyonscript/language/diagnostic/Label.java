package dev.tachyonscript.language.diagnostic;

import dev.tachyonscript.language.source.Span;

import java.util.Objects;

/**
 * A secondary source span with an explanation, e.g. "block opened here".
 *
 * @param span    span in the same file as the diagnostic
 * @param message short explanation; may be empty
 */
public record Label(Span span, String message) {

    public Label {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(message, "message");
    }
}
