package dev.tachyonscript.language.diagnostic;

/**
 * Stable identifiers of every diagnostic the compiler can produce.
 *
 * <p>Codes never change meaning once released, so they can be searched, documented and
 * suppressed. Ranges: {@code TYS00xx} lexical, {@code TYS01xx} syntax, {@code TYS02xx}
 * names and types, {@code TYS03xx} warnings and lints, {@code TYS09xx} internal errors.
 */
public enum DiagnosticCode {
    // Lexical
    UNEXPECTED_CHARACTER("TYS0001", Severity.ERROR),
    UNTERMINATED_STRING("TYS0002", Severity.ERROR),
    INVALID_ESCAPE("TYS0003", Severity.ERROR),
    UNTERMINATED_COMMENT("TYS0004", Severity.ERROR),
    INVALID_NUMBER("TYS0005", Severity.ERROR),
    NUMBER_OUT_OF_RANGE("TYS0006", Severity.ERROR),
    EMPTY_INTERPOLATION("TYS0007", Severity.ERROR),

    // Syntax
    EXPECTED_TOKEN("TYS0100", Severity.ERROR),
    UNCLOSED_BLOCK("TYS0101", Severity.ERROR),
    EXPECTED_EXPRESSION("TYS0102", Severity.ERROR),
    EXPECTED_DECLARATION("TYS0103", Severity.ERROR),
    EXPECTED_STATEMENT_END("TYS0104", Severity.ERROR),
    CHAINED_COMPARISON("TYS0105", Severity.ERROR),
    NESTING_TOO_DEEP("TYS0106", Severity.ERROR),
    INVALID_ASSIGNMENT_TARGET("TYS0107", Severity.ERROR),
    MISPLACED_DECLARATION("TYS0108", Severity.ERROR),
    UNSUPPORTED_FEATURE("TYS0109", Severity.ERROR),
    UNEXPECTED_TOKEN("TYS0110", Severity.ERROR),
    SOURCE_TOO_LARGE("TYS0111", Severity.ERROR),

    // Names and types
    UNKNOWN_NAME("TYS0200", Severity.ERROR),
    UNKNOWN_MEMBER("TYS0201", Severity.ERROR),
    UNKNOWN_TYPE("TYS0202", Severity.ERROR),
    UNKNOWN_EVENT("TYS0203", Severity.ERROR),
    UNKNOWN_FUNCTION("TYS0204", Severity.ERROR),
    DUPLICATE_DECLARATION("TYS0205", Severity.ERROR),
    TYPE_MISMATCH("TYS0210", Severity.ERROR),
    NO_MATCHING_OVERLOAD("TYS0211", Severity.ERROR),
    AMBIGUOUS_OVERLOAD("TYS0212", Severity.ERROR),
    WRONG_ARGUMENT_COUNT("TYS0213", Severity.ERROR),
    NOT_CALLABLE("TYS0214", Severity.ERROR),
    INVALID_OPERATOR("TYS0215", Severity.ERROR),
    NULLABLE_ACCESS("TYS0216", Severity.ERROR),
    INVALID_TYPE_ARGUMENTS("TYS0217", Severity.ERROR),
    NOT_A_VALUE("TYS0218", Severity.ERROR),
    ASSIGN_TO_READONLY("TYS0220", Severity.ERROR),
    MISSING_RETURN("TYS0221", Severity.ERROR),
    UNEXPECTED_RETURN_VALUE("TYS0222", Severity.ERROR),
    MISSING_RETURN_VALUE("TYS0223", Severity.ERROR),
    JUMP_OUTSIDE_LOOP("TYS0224", Severity.ERROR),
    EVENT_NOT_CANCELLABLE("TYS0225", Severity.ERROR),
    NOT_CONSTANT("TYS0226", Severity.ERROR),
    CONSTANT_CYCLE("TYS0227", Severity.ERROR),
    LITERAL_OUT_OF_RANGE("TYS0228", Severity.ERROR),
    DIVISION_BY_ZERO("TYS0229", Severity.ERROR),
    MISSING_INITIALIZER("TYS0230", Severity.ERROR),
    INVALID_CAST("TYS0231", Severity.ERROR),
    NOT_ITERABLE("TYS0232", Severity.ERROR),
    VOID_VALUE("TYS0233", Severity.ERROR),
    UNSAFE_TEXT_FORMATTING("TYS0234", Severity.ERROR),

    // Warnings and lints
    UNREACHABLE_CODE("TYS0300", Severity.WARNING),
    UNUSED_VARIABLE("TYS0301", Severity.WARNING),
    DEPRECATED("TYS0302", Severity.WARNING),
    SHADOWED_VARIABLE("TYS0303", Severity.WARNING),
    CONSTANT_CONDITION("TYS0304", Severity.WARNING),
    UNNECESSARY_SAFE_CALL("TYS0305", Severity.WARNING),
    UNNECESSARY_NULL_CHECK("TYS0306", Severity.WARNING),
    INTERPOLATION_NOT_FORMATTED("TYS0307", Severity.HINT),
    UNUSED_EXPRESSION("TYS0308", Severity.WARNING),
    TOO_MANY_DIAGNOSTICS("TYS0399", Severity.INFO),

    // Internal
    INTERNAL_ERROR("TYS0900", Severity.ERROR);

    private final String id;
    private final Severity defaultSeverity;

    DiagnosticCode(String id, Severity defaultSeverity) {
        this.id = id;
        this.defaultSeverity = defaultSeverity;
    }

    /** Stable identifier such as {@code TYS0201}. */
    public String id() {
        return id;
    }

    public Severity defaultSeverity() {
        return defaultSeverity;
    }
}
