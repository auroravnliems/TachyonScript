package dev.tachyonscript.language.syntax;

/** Prefix operators. */
public enum UnaryOperator {
    NEGATE("-"),
    NOT("!");

    private final String symbol;

    UnaryOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
