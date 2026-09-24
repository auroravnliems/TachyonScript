package dev.tachyonscript.language.lexer;

import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts source text into tokens in a single pass.
 *
 * <p>Two pieces of context are tracked while scanning:
 * <ul>
 *   <li>A bracket stack. Newlines are emitted as {@link TokenKind#NEWLINE} only when the
 *       innermost open bracket is a brace (or none is open), so newlines inside
 *       {@code (...)} and {@code [...]} never terminate statements.</li>
 *   <li>String interpolation. A {@code {} inside a string ends the current literal part and
 *       pushes a template marker; the matching {@code }} resumes scanning the string. The
 *       parser therefore sees {@code TEMPLATE_START expr TEMPLATE_MIDDLE expr TEMPLATE_END}
 *       and nothing about interpolation is left for runtime.</li>
 * </ul>
 *
 * <p>The lexer never throws on malformed input: it reports a diagnostic, recovers, and
 * always ends the token list with {@link TokenKind#EOF}.
 */
public final class Lexer {

    private static final char TEMPLATE = 'T';

    private final SourceFile file;
    private final String src;
    private final int length;
    private final DiagnosticCollector diagnostics;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Comment> comments = new ArrayList<>();
    private final Map<String, String> identifiers = new HashMap<>();

    /** Open brackets: '(', '[', '{' or {@link #TEMPLATE}. */
    private char[] brackets = new char[16];
    /** Start offsets of the strings owning each open template marker (parallel to brackets). */
    private int[] bracketOrigins = new int[16];
    private int bracketDepth;
    private int pos;

    private Lexer(SourceFile file, DiagnosticCollector diagnostics) {
        this.file = file;
        this.src = file.content();
        this.length = src.length();
        this.diagnostics = diagnostics;
    }

    /** Lexes {@code file}, reporting problems to {@code diagnostics}. */
    public static LexResult lex(SourceFile file, DiagnosticCollector diagnostics) {
        Lexer lexer = new Lexer(file, diagnostics);
        lexer.run();
        return new LexResult(file, lexer.tokens, lexer.comments);
    }

    private void run() {
        while (true) {
            skipTrivia();
            if (pos >= length) {
                closeOpenTemplates(length);
                tokens.add(new Token(TokenKind.EOF, length, length, "", null));
                return;
            }
            char c = src.charAt(pos);
            if (c == '\n' || c == '\r') {
                newline();
            } else {
                token(c);
            }
        }
    }

    // ------------------------------------------------------------------ trivia

    private void skipTrivia() {
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\f' || c == '﻿') {
                pos++;
            } else if (c == '/' && pos + 1 < length && src.charAt(pos + 1) == '/') {
                lineComment();
            } else if (c == '/' && pos + 1 < length && src.charAt(pos + 1) == '*') {
                blockComment();
            } else {
                return;
            }
        }
    }

    private void lineComment() {
        int start = pos;
        boolean doc = src.startsWith("///", pos) && !src.startsWith("////", pos);
        pos += doc ? 3 : 2;
        int textStart = pos;
        while (pos < length && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
            pos++;
        }
        comments.add(new Comment(new Span(start, pos), doc ? Comment.Kind.DOC : Comment.Kind.LINE,
                src.substring(textStart, pos)));
    }

    private void blockComment() {
        int start = pos;
        pos += 2;
        int depth = 1;
        while (pos < length && depth > 0) {
            if (src.startsWith("/*", pos)) {
                depth++;
                pos += 2;
            } else if (src.startsWith("*/", pos)) {
                depth--;
                pos += 2;
            } else {
                pos++;
            }
        }
        if (depth > 0) {
            diagnostics.report(Diagnostic.builder(DiagnosticCode.UNTERMINATED_COMMENT, file, new Span(start, start + 2),
                    "Block comment is never closed.").note("Add '*/' to close the comment.").build());
            comments.add(new Comment(new Span(start, length), Comment.Kind.BLOCK, src.substring(start + 2)));
            return;
        }
        comments.add(new Comment(new Span(start, pos), Comment.Kind.BLOCK, src.substring(start + 2, pos - 2)));
    }

    private void newline() {
        int start = pos;
        if (src.charAt(pos) == '\r' && pos + 1 < length && src.charAt(pos + 1) == '\n') {
            pos += 2;
        } else {
            pos++;
        }
        if (hasOpenTemplate()) {
            closeOpenTemplates(start);
        }
        boolean significant = bracketDepth == 0 || brackets[bracketDepth - 1] == '{';
        if (significant && !tokens.isEmpty() && tokens.getLast().kind() != TokenKind.NEWLINE) {
            tokens.add(new Token(TokenKind.NEWLINE, start, pos, src.substring(start, pos), null));
        }
    }

    // ------------------------------------------------------------------ tokens

    private void token(char c) {
        if (isIdentifierStart(c)) {
            identifier();
        } else if (c >= '0' && c <= '9') {
            number();
        } else if (c == '"') {
            int start = pos;
            pos++;
            stringPart(start, true);
        } else {
            operator(c);
        }
    }

    private void identifier() {
        int start = pos;
        while (pos < length && isIdentifierPart(src.charAt(pos))) {
            pos++;
        }
        String text = identifiers.computeIfAbsent(src.substring(start, pos), k -> k);
        TokenKind keyword = TokenKind.keyword(text);
        tokens.add(new Token(keyword != null ? keyword : TokenKind.IDENTIFIER, start, pos, text, null));
    }

    private void operator(char c) {
        int start = pos;
        TokenKind kind;
        switch (c) {
            case '(' -> {
                push('(', start);
                kind = TokenKind.LPAREN;
            }
            case ')' -> {
                closeBracket('(');
                kind = TokenKind.RPAREN;
            }
            case '[' -> {
                push('[', start);
                kind = TokenKind.LBRACKET;
            }
            case ']' -> {
                closeBracket('[');
                kind = TokenKind.RBRACKET;
            }
            case '{' -> {
                push('{', start);
                kind = TokenKind.LBRACE;
            }
            case '}' -> {
                if (closeBrace()) {
                    // The brace ends an interpolation: continue scanning the enclosing string.
                    pos++;
                    stringPart(start, false);
                    return;
                }
                kind = TokenKind.RBRACE;
            }
            case ',' -> kind = TokenKind.COMMA;
            case ':' -> kind = TokenKind.COLON;
            case ';' -> kind = TokenKind.SEMICOLON;
            case '+' -> kind = next('=') ? TokenKind.PLUS_EQ : TokenKind.PLUS;
            case '-' -> kind = next('=') ? TokenKind.MINUS_EQ : TokenKind.MINUS;
            case '*' -> kind = next('=') ? TokenKind.STAR_EQ : TokenKind.STAR;
            case '/' -> kind = next('=') ? TokenKind.SLASH_EQ : TokenKind.SLASH;
            case '%' -> kind = next('=') ? TokenKind.PERCENT_EQ : TokenKind.PERCENT;
            case '!' -> kind = next('=') ? TokenKind.BANG_EQ : TokenKind.BANG;
            case '<' -> kind = next('=') ? TokenKind.LT_EQ : TokenKind.LT;
            case '>' -> kind = next('=') ? TokenKind.GT_EQ : TokenKind.GT;
            case '=' -> {
                if (next('=')) {
                    kind = TokenKind.EQ_EQ;
                } else if (next('>')) {
                    kind = TokenKind.ARROW;
                } else {
                    kind = TokenKind.EQ;
                }
            }
            case '?' -> {
                if (next('.')) {
                    kind = TokenKind.QUESTION_DOT;
                } else if (next('?')) {
                    kind = TokenKind.QUESTION_QUESTION;
                } else {
                    kind = TokenKind.QUESTION;
                }
            }
            case '.' -> {
                if (next('.')) {
                    kind = next('<') ? TokenKind.DOT_DOT_LT : TokenKind.DOT_DOT;
                } else {
                    kind = TokenKind.DOT;
                }
            }
            case '&' -> {
                if (!next('&')) {
                    unexpected(start, "Unexpected character '&'.", "Did you mean '&&' (logical and)?");
                    return;
                }
                kind = TokenKind.AMP_AMP;
            }
            case '|' -> {
                if (!next('|')) {
                    unexpected(start, "Unexpected character '|'.", "Did you mean '||' (logical or)?");
                    return;
                }
                kind = TokenKind.PIPE_PIPE;
            }
            default -> {
                unexpectedCharacter(start);
                return;
            }
        }
        pos++;
        tokens.add(new Token(kind, start, pos, src.substring(start, pos), null));
    }

    /** If the character after the current one is {@code expected}, consumes the current one. */
    private boolean next(char expected) {
        if (pos + 1 < length && src.charAt(pos + 1) == expected) {
            pos++;
            return true;
        }
        return false;
    }

    private void unexpectedCharacter(int start) {
        int codePoint = src.codePointAt(start);
        String shown = new String(Character.toChars(codePoint));
        String hint;
        if (codePoint == '“' || codePoint == '”' || codePoint == '„') {
            hint = "Typographic quotes are not string delimiters; use '\"'.";
        } else if (codePoint == '‘' || codePoint == '’') {
            hint = "Typographic quotes are not supported; use '\"' for strings.";
        } else if (Character.isLetter(codePoint)) {
            hint = "Identifiers may only contain ASCII letters, digits and '_'. Use strings for other text.";
        } else if (codePoint == '\'') {
            hint = "Strings use double quotes: \"text\".";
        } else {
            hint = "";
        }
        String message = "Unexpected character '" + shown + "' (U+" + String.format("%04X", codePoint) + ").";
        unexpected(start, message, hint);
        pos = start + Character.charCount(codePoint);
    }

    private void unexpected(int start, String message, String hint) {
        Diagnostic.Builder builder = Diagnostic.builder(DiagnosticCode.UNEXPECTED_CHARACTER, file,
                new Span(start, Math.min(length, start + Character.charCount(src.codePointAt(start)))), message);
        if (!hint.isEmpty()) {
            builder.note(hint);
        }
        diagnostics.report(builder.build());
        pos = start + 1;
    }

    // ------------------------------------------------------------------ numbers

    private void number() {
        int start = pos;
        int radix = 10;
        if (src.charAt(pos) == '0' && pos + 1 < length) {
            char prefix = src.charAt(pos + 1);
            if (prefix == 'x' || prefix == 'X') {
                radix = 16;
            } else if (prefix == 'b' || prefix == 'B') {
                radix = 2;
            }
        }
        if (radix != 10) {
            pos += 2;
        }
        int digitsStart = pos;
        scanDigits(radix);
        String integerDigits = src.substring(digitsStart, pos);
        boolean floating = false;
        if (radix == 10 && pos + 1 < length && src.charAt(pos) == '.' && isDigit(src.charAt(pos + 1), 10)) {
            pos++;
            scanDigits(10);
            floating = true;
        }
        if (radix == 10 && pos < length && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int save = pos;
            pos++;
            if (pos < length && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < length && isDigit(src.charAt(pos), 10)) {
                scanDigits(10);
                floating = true;
            } else {
                pos = save;
            }
        }
        int bodyEnd = pos;
        char suffix = pos < length ? src.charAt(pos) : 0;
        TokenKind kind;
        if (suffix == 'L' || suffix == 'l') {
            pos++;
            kind = TokenKind.LONG_LITERAL;
        } else if ((suffix == 'f' || suffix == 'F') && radix == 10) {
            pos++;
            kind = TokenKind.FLOAT_LITERAL;
        } else if ((suffix == 'd' || suffix == 'D') && radix == 10) {
            pos++;
            kind = TokenKind.DOUBLE_LITERAL;
        } else {
            kind = floating ? TokenKind.DOUBLE_LITERAL : TokenKind.INT_LITERAL;
        }
        // A number immediately followed by identifier characters is malformed (e.g. "12ab", "0x").
        if (pos < length && isIdentifierPart(src.charAt(pos))) {
            int badStart = pos;
            while (pos < length && isIdentifierPart(src.charAt(pos))) {
                pos++;
            }
            invalidNumber(start, "Invalid number literal '" + src.substring(start, pos) + "'.",
                    "'" + src.substring(badStart, pos) + "' is not a valid digit or suffix.");
            return;
        }
        String text = src.substring(start, pos);
        String body = src.substring(radix == 10 ? start : start + 2, bodyEnd);
        if (body.isEmpty() || !validUnderscores(radix == 10 ? body : integerDigits)) {
            invalidNumber(start, "Invalid number literal '" + text + "'.",
                    body.isEmpty() ? "Digits are required after the '" + src.substring(start, start + 2) + "' prefix."
                            : "Underscores may only appear between digits.");
            return;
        }
        String digits = body.replace("_", "");
        if (floating && kind == TokenKind.LONG_LITERAL) {
            invalidNumber(start, "A decimal number cannot have the 'L' suffix.", "Remove the 'L' or the fraction.");
            return;
        }
        Object value;
        if (kind == TokenKind.INT_LITERAL || kind == TokenKind.LONG_LITERAL) {
            try {
                long magnitude = Long.parseUnsignedLong(digits, radix);
                // 2^63 is only valid as the operand of unary minus; anything larger never is.
                if (Long.compareUnsigned(magnitude, Long.MIN_VALUE) > 0) {
                    throw new NumberFormatException();
                }
                value = magnitude;
            } catch (NumberFormatException e) {
                diagnostics.report(Diagnostic.builder(DiagnosticCode.NUMBER_OUT_OF_RANGE, file, new Span(start, pos),
                        "Integer literal '" + text + "' is too large.")
                        .note("The largest long value is 9223372036854775807.").build());
                value = 0L;
            }
        } else if (kind == TokenKind.FLOAT_LITERAL) {
            float f = Float.parseFloat(digits);
            value = checkFloating(start, text, Float.isInfinite(f), f == 0f && hasNonZeroDigit(digits), "float") ? f : 0f;
        } else {
            double d = Double.parseDouble(digits);
            value = checkFloating(start, text, Double.isInfinite(d), d == 0d && hasNonZeroDigit(digits), "double") ? d : 0d;
        }
        tokens.add(new Token(kind, start, pos, text, value));
    }

    private boolean checkFloating(int start, String text, boolean overflow, boolean underflow, String type) {
        if (overflow || underflow) {
            diagnostics.report(Diagnostic.builder(DiagnosticCode.NUMBER_OUT_OF_RANGE, file, new Span(start, pos),
                    "Literal '" + text + "' is " + (overflow ? "too large" : "too small") + " for " + type + ".").build());
            return false;
        }
        return true;
    }

    private void invalidNumber(int start, String message, String note) {
        diagnostics.report(Diagnostic.builder(DiagnosticCode.INVALID_NUMBER, file, new Span(start, pos), message)
                .note(note).build());
        tokens.add(new Token(TokenKind.INT_LITERAL, start, pos, src.substring(start, pos), 0L));
    }

    private void scanDigits(int radix) {
        while (pos < length && (isDigit(src.charAt(pos), radix) || src.charAt(pos) == '_')) {
            pos++;
        }
    }

    private static boolean validUnderscores(String digits) {
        return !digits.startsWith("_") && !digits.endsWith("_") && !digits.contains("_.") && !digits.contains("._")
                && !digits.contains("_e") && !digits.contains("_E") && !digits.contains("e_") && !digits.contains("E_");
    }

    private static boolean hasNonZeroDigit(String digits) {
        int exponent = Math.max(digits.indexOf('e'), digits.indexOf('E'));
        String mantissa = exponent >= 0 ? digits.substring(0, exponent) : digits;
        for (int i = 0; i < mantissa.length(); i++) {
            char c = mantissa.charAt(i);
            if (c >= '1' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    private static boolean isDigit(char c, int radix) {
        return switch (radix) {
            case 2 -> c == '0' || c == '1';
            case 16 -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            default -> c >= '0' && c <= '9';
        };
    }

    // ------------------------------------------------------------------ strings

    /**
     * Scans string content starting at {@code pos} until the closing quote, an
     * interpolation, or the end of the line.
     *
     * @param tokenStart start of the token (the opening quote, or the '}' ending an interpolation)
     * @param first      whether this is the first part of the string
     */
    private void stringPart(int tokenStart, boolean first) {
        StringBuilder value = new StringBuilder();
        while (true) {
            if (pos >= length || src.charAt(pos) == '\n' || src.charAt(pos) == '\r') {
                int origin = first ? tokenStart : currentTemplateOrigin(tokenStart);
                diagnostics.report(Diagnostic.builder(DiagnosticCode.UNTERMINATED_STRING, file,
                                new Span(origin, Math.min(length, origin + 1)), "String literal is not closed.")
                        .note("Add '\"' at the end of the text. Strings cannot span several lines.").build());
                tokens.add(new Token(first ? TokenKind.STRING_LITERAL : TokenKind.TEMPLATE_END, tokenStart, pos,
                        src.substring(tokenStart, pos), value.toString()));
                return;
            }
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                tokens.add(new Token(first ? TokenKind.STRING_LITERAL : TokenKind.TEMPLATE_END, tokenStart, pos,
                        src.substring(tokenStart, pos), value.toString()));
                return;
            }
            if (c == '\\') {
                escape(value);
            } else if (c == '{') {
                pos++;
                tokens.add(new Token(first ? TokenKind.TEMPLATE_START : TokenKind.TEMPLATE_MIDDLE, tokenStart, pos,
                        src.substring(tokenStart, pos), value.toString()));
                push(TEMPLATE, first ? tokenStart : currentTemplateOrigin(tokenStart));
                return;
            } else {
                value.append(c);
                pos++;
            }
        }
    }

    private void escape(StringBuilder value) {
        int start = pos;
        pos++;
        if (pos >= length || src.charAt(pos) == '\n' || src.charAt(pos) == '\r') {
            diagnostics.report(Diagnostic.builder(DiagnosticCode.INVALID_ESCAPE, file, new Span(start, pos),
                    "Incomplete escape sequence at the end of the line.").build());
            return;
        }
        char c = src.charAt(pos);
        pos++;
        switch (c) {
            case 'n' -> value.append('\n');
            case 't' -> value.append('\t');
            case 'r' -> value.append('\r');
            case '0' -> value.append('\0');
            case '\\' -> value.append('\\');
            case '"' -> value.append('"');
            case '\'' -> value.append('\'');
            case '{' -> value.append('{');
            case '}' -> value.append('}');
            case 'u' -> {
                int end = Math.min(length, pos + 4);
                String hex = src.substring(pos, end);
                if (hex.length() == 4 && hex.chars().allMatch(ch -> isDigit((char) ch, 16))) {
                    value.append((char) Integer.parseInt(hex, 16));
                    pos += 4;
                } else {
                    diagnostics.report(Diagnostic.builder(DiagnosticCode.INVALID_ESCAPE, file, new Span(start, pos),
                            "Invalid unicode escape.").note("Write exactly four hex digits, e.g. \\u00e9.").build());
                }
            }
            default -> diagnostics.report(Diagnostic.builder(DiagnosticCode.INVALID_ESCAPE, file, new Span(start, pos),
                            "Unknown escape sequence '\\" + c + "'.")
                    .note("Valid escapes: \\n \\t \\r \\0 \\\\ \\\" \\' \\{ \\} \\uXXXX").build());
        }
    }

    // ---------------------------------------------------------------- brackets

    private void push(char bracket, int origin) {
        if (bracketDepth == brackets.length) {
            brackets = java.util.Arrays.copyOf(brackets, bracketDepth * 2);
            bracketOrigins = java.util.Arrays.copyOf(bracketOrigins, bracketDepth * 2);
        }
        brackets[bracketDepth] = bracket;
        bracketOrigins[bracketDepth] = origin;
        bracketDepth++;
    }

    /**
     * Pops brackets up to and including the innermost matching opener. Stray closers leave
     * the stack unchanged; the parser reports them. An open template marker is never popped
     * by ')' or ']' so that a malformed interpolation cannot swallow the rest of the string.
     */
    private void closeBracket(char opener) {
        for (int i = bracketDepth - 1; i >= 0; i--) {
            if (brackets[i] == opener) {
                bracketDepth = i;
                return;
            }
            if (brackets[i] == TEMPLATE) {
                return;
            }
        }
    }

    /** Handles '}'; returns {@code true} if it closes an interpolation. */
    private boolean closeBrace() {
        for (int i = bracketDepth - 1; i >= 0; i--) {
            if (brackets[i] == '{') {
                bracketDepth = i;
                return false;
            }
            if (brackets[i] == TEMPLATE) {
                bracketDepth = i;
                return true;
            }
        }
        return false;
    }

    private boolean hasOpenTemplate() {
        for (int i = 0; i < bracketDepth; i++) {
            if (brackets[i] == TEMPLATE) {
                return true;
            }
        }
        return false;
    }

    private int currentTemplateOrigin(int fallback) {
        for (int i = bracketDepth - 1; i >= 0; i--) {
            if (brackets[i] == TEMPLATE) {
                return bracketOrigins[i];
            }
        }
        return fallback;
    }

    /**
     * Recovers from interpolated strings left open at the end of a line or file: reports the
     * string once and emits zero-width {@code TEMPLATE_END} tokens so that the parser still
     * sees balanced templates.
     */
    private void closeOpenTemplates(int at) {
        boolean reported = false;
        for (int i = bracketDepth - 1; i >= 0; i--) {
            if (brackets[i] == TEMPLATE) {
                if (!reported) {
                    int origin = bracketOrigins[i];
                    diagnostics.report(Diagnostic.builder(DiagnosticCode.UNTERMINATED_STRING, file,
                                    new Span(origin, Math.min(length, origin + 1)),
                                    "Interpolated string is not closed.")
                            .note("Close the interpolation with '}' and the string with '\"'. "
                                    + "Write '\\{' for a literal brace.").build());
                    reported = true;
                }
                tokens.add(new Token(TokenKind.TEMPLATE_END, at, at, "", ""));
                bracketDepth = i;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }
}
