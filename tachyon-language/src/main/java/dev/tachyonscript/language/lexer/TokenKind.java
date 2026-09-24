package dev.tachyonscript.language.lexer;

import java.util.HashMap;
import java.util.Map;

/** Kinds of tokens produced by the {@link Lexer}. */
public enum TokenKind {
    IDENTIFIER("identifier"),
    INT_LITERAL("integer literal"),
    LONG_LITERAL("long literal"),
    FLOAT_LITERAL("float literal"),
    DOUBLE_LITERAL("double literal"),
    STRING_LITERAL("string literal"),
    /** Text before the first {@code {} of an interpolated string. */
    TEMPLATE_START("string template"),
    /** Text between two interpolations. */
    TEMPLATE_MIDDLE("string template"),
    /** Text after the last interpolation, up to the closing quote. */
    TEMPLATE_END("string template"),

    // Reserved keywords.
    LET("'let'", "let"),
    VAR("'var'", "var"),
    CONST("'const'", "const"),
    FUNCTION("'function'", "function"),
    RETURN("'return'", "return"),
    IF("'if'", "if"),
    ELSE("'else'", "else"),
    WHILE("'while'", "while"),
    FOR("'for'", "for"),
    IN("'in'", "in"),
    BREAK("'break'", "break"),
    CONTINUE("'continue'", "continue"),
    TRUE("'true'", "true"),
    FALSE("'false'", "false"),
    NULL("'null'", "null"),
    IS("'is'", "is"),
    AS("'as'", "as"),
    // Reserved now so that future error handling does not break existing scripts.
    TRY("'try'", "try"),
    CATCH("'catch'", "catch"),
    THROW("'throw'", "throw"),

    LPAREN("'('"),
    RPAREN("')'"),
    LBRACE("'{'"),
    RBRACE("'}'"),
    LBRACKET("'['"),
    RBRACKET("']'"),
    COMMA("','"),
    DOT("'.'"),
    QUESTION_DOT("'?.'"),
    COLON("':'"),
    SEMICOLON("';'"),
    ARROW("'=>'"),
    PLUS("'+'"),
    MINUS("'-'"),
    STAR("'*'"),
    SLASH("'/'"),
    PERCENT("'%'"),
    BANG("'!'"),
    EQ("'='"),
    EQ_EQ("'=='"),
    BANG_EQ("'!='"),
    LT("'<'"),
    LT_EQ("'<='"),
    GT("'>'"),
    GT_EQ("'>='"),
    AMP_AMP("'&&'"),
    PIPE_PIPE("'||'"),
    QUESTION("'?'"),
    QUESTION_QUESTION("'??'"),
    PLUS_EQ("'+='"),
    MINUS_EQ("'-='"),
    STAR_EQ("'*='"),
    SLASH_EQ("'/='"),
    PERCENT_EQ("'%='"),
    DOT_DOT("'..'"),
    DOT_DOT_LT("'..<'"),
    NEWLINE("end of line"),
    EOF("end of file");

    private static final Map<String, TokenKind> KEYWORDS = new HashMap<>();

    static {
        for (TokenKind kind : values()) {
            if (kind.keyword != null) {
                KEYWORDS.put(kind.keyword, kind);
            }
        }
    }

    private final String description;
    private final String keyword;

    TokenKind(String description) {
        this(description, null);
    }

    TokenKind(String description, String keyword) {
        this.description = description;
        this.keyword = keyword;
    }

    /** Human-readable description used in diagnostics, e.g. {@code '}'} or {@code identifier}. */
    public String description() {
        return description;
    }

    public boolean isKeyword() {
        return keyword != null;
    }

    /** Keyword kind for {@code text}, or {@code null} if it is not a reserved keyword. */
    public static TokenKind keyword(String text) {
        return KEYWORDS.get(text);
    }

    public boolean isLiteral() {
        return this == INT_LITERAL || this == LONG_LITERAL || this == FLOAT_LITERAL || this == DOUBLE_LITERAL
                || this == STRING_LITERAL;
    }
}
