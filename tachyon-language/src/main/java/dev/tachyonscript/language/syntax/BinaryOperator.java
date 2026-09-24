package dev.tachyonscript.language.syntax;

/**
 * Binary operators with their precedence (higher binds tighter) and associativity.
 * The full table, including prefix and postfix operators, is documented in
 * {@code docs/compiler/parser.md}.
 */
public enum BinaryOperator {
    OR("||", Precedence.OR, Associativity.LEFT),
    AND("&&", Precedence.AND, Associativity.LEFT),
    EQUAL("==", Precedence.EQUALITY, Associativity.NONE),
    NOT_EQUAL("!=", Precedence.EQUALITY, Associativity.NONE),
    LESS("<", Precedence.RELATIONAL, Associativity.NONE),
    LESS_EQUAL("<=", Precedence.RELATIONAL, Associativity.NONE),
    GREATER(">", Precedence.RELATIONAL, Associativity.NONE),
    GREATER_EQUAL(">=", Precedence.RELATIONAL, Associativity.NONE),
    COALESCE("??", Precedence.COALESCE, Associativity.RIGHT),
    ADD("+", Precedence.ADDITIVE, Associativity.LEFT),
    SUBTRACT("-", Precedence.ADDITIVE, Associativity.LEFT),
    MULTIPLY("*", Precedence.MULTIPLICATIVE, Associativity.LEFT),
    DIVIDE("/", Precedence.MULTIPLICATIVE, Associativity.LEFT),
    REMAINDER("%", Precedence.MULTIPLICATIVE, Associativity.LEFT);

    /** Operator precedence levels, lowest first. */
    public static final class Precedence {
        public static final int OR = 1;
        public static final int AND = 2;
        public static final int EQUALITY = 3;
        /** Also {@code is}. */
        public static final int RELATIONAL = 4;
        public static final int COALESCE = 5;
        /** {@code ..} and {@code ..<}. */
        public static final int RANGE = 6;
        public static final int ADDITIVE = 7;
        public static final int MULTIPLICATIVE = 8;
        /** {@code as} and {@code as?}. */
        public static final int CAST = 9;
        public static final int PREFIX = 10;
        public static final int POSTFIX = 11;

        private Precedence() {
        }
    }

    public enum Associativity {
        LEFT,
        RIGHT,
        /** Chaining is an error, e.g. {@code a < b < c}. */
        NONE
    }

    private final String symbol;
    private final int precedence;
    private final Associativity associativity;

    BinaryOperator(String symbol, int precedence, Associativity associativity) {
        this.symbol = symbol;
        this.precedence = precedence;
        this.associativity = associativity;
    }

    public String symbol() {
        return symbol;
    }

    public int precedence() {
        return precedence;
    }

    public Associativity associativity() {
        return associativity;
    }

    public boolean isComparison() {
        return precedence == Precedence.EQUALITY || precedence == Precedence.RELATIONAL;
    }

    public boolean isArithmetic() {
        return precedence == Precedence.ADDITIVE || precedence == Precedence.MULTIPLICATIVE;
    }

    public boolean isLogical() {
        return this == AND || this == OR;
    }
}
