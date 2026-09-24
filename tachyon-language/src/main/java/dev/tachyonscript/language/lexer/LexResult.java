package dev.tachyonscript.language.lexer;

import dev.tachyonscript.language.source.SourceFile;

import java.util.List;

/**
 * Output of the lexer.
 *
 * @param file     the lexed file
 * @param tokens   tokens, always terminated by exactly one {@link TokenKind#EOF}
 * @param comments comments in source order
 */
public record LexResult(SourceFile file, List<Token> tokens, List<Comment> comments) {

    public LexResult {
        tokens = List.copyOf(tokens);
        comments = List.copyOf(comments);
    }
}
