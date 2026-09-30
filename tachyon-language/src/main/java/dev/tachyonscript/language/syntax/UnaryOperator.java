package dev.tachyonscript.language.syntax;

/** Prefix operators. */
public enum UnaryOperator {
    NEGATE("-"),
    NOT("!"),
    /** Bitwise complement of an int or long. */
    BIT_NOT("~");

    private final String symbol;

    UnaryOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
