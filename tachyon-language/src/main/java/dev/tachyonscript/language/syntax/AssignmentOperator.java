package dev.tachyonscript.language.syntax;

/** Assignment operators; compound forms apply {@link #binary()} to the old value. */
public enum AssignmentOperator {
    ASSIGN("=", null),
    ADD("+=", BinaryOperator.ADD),
    SUBTRACT("-=", BinaryOperator.SUBTRACT),
    MULTIPLY("*=", BinaryOperator.MULTIPLY),
    DIVIDE("/=", BinaryOperator.DIVIDE),
    REMAINDER("%=", BinaryOperator.REMAINDER);

    private final String symbol;
    private final BinaryOperator binary;

    AssignmentOperator(String symbol, BinaryOperator binary) {
        this.symbol = symbol;
        this.binary = binary;
    }

    public String symbol() {
        return symbol;
    }

    /** The arithmetic operator of a compound assignment, or {@code null} for plain {@code =}. */
    public BinaryOperator binary() {
        return binary;
    }

    public boolean isCompound() {
        return binary != null;
    }
}
