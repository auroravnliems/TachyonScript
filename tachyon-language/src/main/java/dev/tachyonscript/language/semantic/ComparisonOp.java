package dev.tachyonscript.language.semantic;

/** Comparisons; references only support {@link #EQUAL} and {@link #NOT_EQUAL}. */
public enum ComparisonOp {
    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL;

    /** The comparison that is true exactly when this one is false. */
    public ComparisonOp negate() {
        return switch (this) {
            case EQUAL -> NOT_EQUAL;
            case NOT_EQUAL -> EQUAL;
            case LESS -> GREATER_EQUAL;
            case LESS_EQUAL -> GREATER;
            case GREATER -> LESS_EQUAL;
            case GREATER_EQUAL -> LESS;
        };
    }
}
