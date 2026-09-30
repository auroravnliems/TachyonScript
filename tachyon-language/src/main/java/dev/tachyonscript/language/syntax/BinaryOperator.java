package dev.tachyonscript.language.syntax;

/**
 * Binary operators with their precedence (higher binds tighter) and associativity.
 * The full table, including prefix and postfix operators, is documented in
 * {@code docs/compiler/parser.md}.
 */
public enum BinaryOperator {
    OR("||", Precedence.OR, Associativity.LEFT),
    AND("&&", Precedence.AND, Associativity.LEFT),
    BIT_OR("|", Precedence.BIT_OR, Associativity.LEFT),
    BIT_XOR("^", Precedence.BIT_XOR, Associativity.LEFT),
    BIT_AND("&", Precedence.BIT_AND, Associativity.LEFT),
    EQUAL("==", Precedence.EQUALITY, Associativity.NONE),
    NOT_EQUAL("!=", Precedence.EQUALITY, Associativity.NONE),
    LESS("<", Precedence.RELATIONAL, Associativity.NONE),
    LESS_EQUAL("<=", Precedence.RELATIONAL, Associativity.NONE),
    GREATER(">", Precedence.RELATIONAL, Associativity.NONE),
    GREATER_EQUAL(">=", Precedence.RELATIONAL, Associativity.NONE),
    /** {@code element in collection}: membership in a list, map (keys), string or range. */
    IN("in", Precedence.RELATIONAL, Associativity.NONE),
    /** {@code element !in collection} */
    NOT_IN("!in", Precedence.RELATIONAL, Associativity.NONE),
    COALESCE("??", Precedence.COALESCE, Associativity.RIGHT),
    SHIFT_LEFT("<<", Precedence.SHIFT, Associativity.LEFT),
    SHIFT_RIGHT(">>", Precedence.SHIFT, Associativity.LEFT),
    UNSIGNED_SHIFT_RIGHT(">>>", Precedence.SHIFT, Associativity.LEFT),
    ADD("+", Precedence.ADDITIVE, Associativity.LEFT),
    SUBTRACT("-", Precedence.ADDITIVE, Associativity.LEFT),
    MULTIPLY("*", Precedence.MULTIPLICATIVE, Associativity.LEFT),
    DIVIDE("/", Precedence.MULTIPLICATIVE, Associativity.LEFT),
    REMAINDER("%", Precedence.MULTIPLICATIVE, Associativity.LEFT);

    /** Operator precedence levels, lowest first. */
    public static final class Precedence {
        /** {@code c ? a : b} (right-associative, parsed separately). */
        public static final int CONDITIONAL = 0;
        public static final int OR = 1;
        public static final int AND = 2;
        public static final int BIT_OR = 3;
        public static final int BIT_XOR = 4;
        public static final int BIT_AND = 5;
        public static final int EQUALITY = 6;
        /** Also {@code is}, {@code in}. */
        public static final int RELATIONAL = 7;
        public static final int COALESCE = 8;
        /** {@code ..} and {@code ..<}. */
        public static final int RANGE = 9;
        public static final int SHIFT = 10;
        public static final int ADDITIVE = 11;
        public static final int MULTIPLICATIVE = 12;
        /** {@code as} and {@code as?}. */
        public static final int CAST = 13;
        public static final int PREFIX = 14;
        public static final int POSTFIX = 15;

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
        return this == EQUAL || this == NOT_EQUAL || this == LESS || this == LESS_EQUAL || this == GREATER
                || this == GREATER_EQUAL;
    }

    public boolean isArithmetic() {
        return precedence == Precedence.ADDITIVE || precedence == Precedence.MULTIPLICATIVE;
    }

    public boolean isBitwise() {
        return this == BIT_AND || this == BIT_OR || this == BIT_XOR || this == SHIFT_LEFT || this == SHIFT_RIGHT
                || this == UNSIGNED_SHIFT_RIGHT;
    }

    public boolean isMembership() {
        return this == IN || this == NOT_IN;
    }

    public boolean isLogical() {
        return this == AND || this == OR;
    }
}
