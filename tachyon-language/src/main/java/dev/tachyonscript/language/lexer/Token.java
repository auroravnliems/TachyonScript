package dev.tachyonscript.language.lexer;

import dev.tachyonscript.language.source.Span;

/**
 * A lexical token.
 *
 * @param kind  token kind
 * @param start start offset (inclusive)
 * @param end   end offset (exclusive)
 * @param text  exact source text of the token
 * @param value decoded value: {@code Long} magnitude for integer literals, {@code Float} or
 *              {@code Double} for floating literals, the unescaped {@code String} for string
 *              literals and template parts; {@code null} otherwise
 */
public record Token(TokenKind kind, int start, int end, String text, Object value) {

    public Span span() {
        return new Span(start, end);
    }

    public boolean is(TokenKind other) {
        return kind == other;
    }

    /** Whether this is an identifier with exactly the given text (for contextual keywords). */
    public boolean isIdentifier(String identifier) {
        return kind == TokenKind.IDENTIFIER && text.equals(identifier);
    }

    @Override
    public String toString() {
        return kind + (kind == TokenKind.NEWLINE || kind == TokenKind.EOF ? "" : "(" + text + ")") + "@" + start;
    }
}
