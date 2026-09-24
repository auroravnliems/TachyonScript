package dev.tachyonscript.language.lexer;

import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.source.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static dev.tachyonscript.language.lexer.TokenKind.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {

    private record Lexed(LexResult result, DiagnosticCollector diagnostics) {
        List<TokenKind> kinds() {
            return result.tokens().stream().map(Token::kind).toList();
        }

        List<DiagnosticCode> codes() {
            return diagnostics.diagnostics().stream().map(Diagnostic::code).toList();
        }

        Token token(int index) {
            return result.tokens().get(index);
        }
    }

    private static Lexed lex(String source) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        return new Lexed(Lexer.lex(new SourceFile("test.tys", source), diagnostics), diagnostics);
    }

    private static List<TokenKind> kinds(String source) {
        Lexed lexed = lex(source);
        assertFalse(lexed.diagnostics.hasErrors(), () -> "unexpected diagnostics: " + lexed.diagnostics.diagnostics());
        return lexed.kinds();
    }

    @Test
    void lexesEventDeclaration() {
        assertEquals(List.of(IDENTIFIER, IDENTIFIER, DOT, IDENTIFIER, LBRACE, NEWLINE,
                        IDENTIFIER, DOT, IDENTIFIER, LPAREN, STRING_LITERAL, RPAREN, NEWLINE, RBRACE, EOF),
                kinds("event player.join {\n    player.send(\"hi\")\n}"));
    }

    @Test
    void keywordsAreReservedButDeclarationWordsAreContextual() {
        Lexed lexed = lex("let var const function return if else while for in break continue true false null is as event command");
        List<TokenKind> expected = List.of(LET, VAR, CONST, FUNCTION, RETURN, IF, ELSE, WHILE, FOR, IN, BREAK, CONTINUE,
                TRUE, FALSE, NULL, IS, AS, IDENTIFIER, IDENTIFIER, EOF);
        assertEquals(expected, lexed.kinds());
    }

    @Test
    void lexesAllOperators() {
        assertEquals(List.of(PLUS, MINUS, STAR, SLASH, PERCENT, BANG, EQ, EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ,
                        AMP_AMP, PIPE_PIPE, QUESTION, QUESTION_QUESTION, QUESTION_DOT, DOT, DOT_DOT, DOT_DOT_LT,
                        PLUS_EQ, MINUS_EQ, STAR_EQ, SLASH_EQ, PERCENT_EQ, COLON, SEMICOLON, COMMA, ARROW, EOF),
                kinds("+ - * / % ! = == != < <= > >= && || ? ?? ?. . .. ..< += -= *= /= %= : ; , =>"));
    }

    @Test
    void lexesNumberLiterals() {
        Lexed lexed = lex("123 123L 1.5 1.5f 2e3 0xFF 0b101 1_000_000 7d");
        assertEquals(List.of(INT_LITERAL, LONG_LITERAL, DOUBLE_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, INT_LITERAL,
                INT_LITERAL, INT_LITERAL, DOUBLE_LITERAL, EOF), lexed.kinds());
        assertEquals(123L, lexed.token(0).value());
        assertEquals(123L, lexed.token(1).value());
        assertEquals(1.5, lexed.token(2).value());
        assertEquals(1.5f, lexed.token(3).value());
        assertEquals(2000.0, lexed.token(4).value());
        assertEquals(255L, lexed.token(5).value());
        assertEquals(5L, lexed.token(6).value());
        assertEquals(1_000_000L, lexed.token(7).value());
        assertEquals(7.0, lexed.token(8).value());
        assertFalse(lexed.diagnostics.hasErrors());
    }

    @Test
    void rangeIsNotAFraction() {
        assertEquals(List.of(INT_LITERAL, DOT_DOT, INT_LITERAL, EOF), kinds("1..10"));
        assertEquals(List.of(INT_LITERAL, DOT_DOT_LT, INT_LITERAL, EOF), kinds("0..<10"));
    }

    @Test
    void reportsMalformedAndOutOfRangeNumbers() {
        assertEquals(List.of(DiagnosticCode.INVALID_NUMBER), lex("12ab").codes());
        assertEquals(List.of(DiagnosticCode.INVALID_NUMBER), lex("0x").codes());
        assertEquals(List.of(DiagnosticCode.INVALID_NUMBER), lex("1_").codes());
        assertEquals(List.of(DiagnosticCode.NUMBER_OUT_OF_RANGE), lex("99999999999999999999").codes());
        assertEquals(List.of(DiagnosticCode.NUMBER_OUT_OF_RANGE), lex("1e999").codes());
        assertEquals(List.of(DiagnosticCode.NUMBER_OUT_OF_RANGE), lex("1e-999").codes());
        assertFalse(lex("9223372036854775808").diagnostics.hasErrors(), "2^63 is valid as operand of unary minus");
        assertFalse(lex("0.0 0e5").diagnostics.hasErrors(), "zero literals are not underflow");
    }

    @Test
    void decodesStringEscapes() {
        Lexed lexed = lex("\"a\\nb\\t\\\"q\\\" \\\\ \\{x\\} \\u00e9\"");
        assertEquals(STRING_LITERAL, lexed.token(0).kind());
        assertEquals("a\nb\t\"q\" \\ {x} \u00e9", lexed.token(0).value());
        assertFalse(lexed.diagnostics.hasErrors());
    }

    @Test
    void reportsInvalidEscapesAndUnterminatedStrings() {
        assertEquals(List.of(DiagnosticCode.INVALID_ESCAPE), lex("\"\\q\"").codes());
        assertEquals(List.of(DiagnosticCode.INVALID_ESCAPE), lex("\"\\u12\"").codes());
        Lexed unterminated = lex("\"abc\nlet");
        assertEquals(List.of(DiagnosticCode.UNTERMINATED_STRING), unterminated.codes());
        assertEquals(List.of(STRING_LITERAL, NEWLINE, LET, EOF), unterminated.kinds());
    }

    @Test
    void lexesInterpolatedStrings() {
        Lexed lexed = lex("\"Hello {player.name}, you have {count + 1} coins\"");
        assertEquals(List.of(TEMPLATE_START, IDENTIFIER, DOT, IDENTIFIER, TEMPLATE_MIDDLE, IDENTIFIER, PLUS, INT_LITERAL,
                TEMPLATE_END, EOF), lexed.kinds());
        assertEquals("Hello ", lexed.token(0).value());
        assertEquals(", you have ", lexed.token(4).value());
        assertEquals(" coins", lexed.token(8).value());
        assertFalse(lexed.diagnostics.hasErrors());
    }

    @Test
    void interpolationMayContainStringsAndBrackets() {
        Lexed lexed = lex("\"a {f(\"x\", list[0])} b\"");
        assertEquals(List.of(TEMPLATE_START, IDENTIFIER, LPAREN, STRING_LITERAL, COMMA, IDENTIFIER, LBRACKET,
                INT_LITERAL, RBRACKET, RPAREN, TEMPLATE_END, EOF), lexed.kinds());
        assertFalse(lexed.diagnostics.hasErrors());
    }

    @Test
    void recoversFromUnterminatedInterpolation() {
        Lexed lexed = lex("let s = \"a {name\nlet t = 1");
        assertEquals(List.of(DiagnosticCode.UNTERMINATED_STRING), lexed.codes());
        assertEquals(List.of(LET, IDENTIFIER, EQ, TEMPLATE_START, IDENTIFIER, TEMPLATE_END, NEWLINE,
                LET, IDENTIFIER, EQ, INT_LITERAL, EOF), lexed.kinds());
    }

    @Test
    void newlinesAreSignificantOnlyInsideBraces() {
        assertEquals(List.of(IDENTIFIER, LPAREN, INT_LITERAL, COMMA, INT_LITERAL, RPAREN, NEWLINE, IDENTIFIER, EOF),
                kinds("f(\n1,\n2\n)\nx"));
        assertEquals(List.of(LBRACKET, INT_LITERAL, COMMA, INT_LITERAL, RBRACKET, EOF), kinds("[\n1,\n\n2\n]"));
        assertEquals(List.of(LBRACE, NEWLINE, IDENTIFIER, NEWLINE, RBRACE, EOF), kinds("{\n\n\nx\r\n\r\n}"));
        assertEquals(List.of(IDENTIFIER, EOF), kinds("\n\n x"), "leading newlines are dropped");
    }

    @Test
    void collectsCommentsAsTrivia() {
        Lexed lexed = lex("// line\n/// doc\n/* block /* nested */ still */ x //// not doc");
        assertEquals(List.of(IDENTIFIER, EOF), lexed.kinds());
        List<Comment.Kind> commentKinds = lexed.result.comments().stream().map(Comment::kind).toList();
        assertEquals(List.of(Comment.Kind.LINE, Comment.Kind.DOC, Comment.Kind.BLOCK, Comment.Kind.LINE), commentKinds);
        assertEquals(" doc", lexed.result.comments().get(1).text());
    }

    @Test
    void reportsUnterminatedBlockComment() {
        Lexed lexed = lex("x /* never closed");
        assertEquals(List.of(DiagnosticCode.UNTERMINATED_COMMENT), lexed.codes());
        assertEquals(List.of(IDENTIFIER, EOF), lexed.kinds());
    }

    @Test
    void reportsUnexpectedCharactersWithHints() {
        Lexed lexed = lex("a @ b & c \u201Chi\u201D caf\u00e9");
        assertEquals(5, lexed.codes().size(), () -> lexed.diagnostics.diagnostics().toString());
        assertTrue(lexed.codes().stream().allMatch(code -> code == DiagnosticCode.UNEXPECTED_CHARACTER));
        String notes = lexed.diagnostics.diagnostics().stream().flatMap(d -> d.notes().stream())
                .collect(Collectors.joining("\n"));
        assertTrue(notes.contains("&&"), notes);
        assertTrue(notes.contains("Typographic quotes"), notes);
        assertEquals(EOF, lexed.kinds().getLast());
    }

    @Test
    void alwaysEndsWithSingleEof() {
        for (String source : List.of("", "\"", "\"{", "{{{", ")))", "/*", "0x", "\\")) {
            Lexed lexed = lex(source);
            assertEquals(EOF, lexed.kinds().getLast(), source);
            assertEquals(1, lexed.kinds().stream().filter(kind -> kind == EOF).count(), source);
        }
    }
}
