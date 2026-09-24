package dev.tachyonscript.language.lexer;

import dev.tachyonscript.language.source.Span;

/**
 * A comment, kept as trivia so that doc comments can be attached to declarations and a
 * formatter can preserve comments.
 *
 * @param span the comment including its delimiters
 * @param kind line, block or documentation comment
 * @param text the comment content without delimiters
 */
public record Comment(Span span, Kind kind, String text) {

    public enum Kind {
        /** {@code // ...} */
        LINE,
        /** {@code /* ... * /} */
        BLOCK,
        /** {@code /// ...}: documentation of the following declaration. */
        DOC
    }
}
