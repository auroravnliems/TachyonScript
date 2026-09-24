package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.api.value.Values;

/**
 * Evaluates bound expressions whose value is known at compile time.
 *
 * <p>The arithmetic here must match the runtime exactly (Java semantics for all numeric
 * types, {@link Values} for text), because the compiler substitutes folded values for
 * expressions. Integer overflow wraps like at runtime but is reported, since it is almost
 * certainly a mistake in a constant.
 */
final class ConstantEvaluator {

    /** Returned when an expression is not a compile-time constant. */
    static final Object NOT_CONSTANT = new Object() {
        @Override
        public String toString() {
            return "<not constant>";
        }
    };

    /** Receives problems found while evaluating. */
    interface Problems {
        void overflow(BoundExpression expression);

        void divisionByZero(BoundExpression expression);
    }

    /** Ignores problems (used when folding is opportunistic). */
    static final Problems SILENT = new Problems() {
        @Override
        public void overflow(BoundExpression expression) {
        }

        @Override
        public void divisionByZero(BoundExpression expression) {
        }
    };

    private ConstantEvaluator() {
    }

    static boolean isConstant(BoundExpression expression) {
        return evaluate(expression, SILENT) != NOT_CONSTANT;
    }

    static Object evaluate(BoundExpression expression, Problems problems) {
        return switch (expression) {
            case BoundExpression.Literal literal -> literal.value();
            case BoundExpression.Arithmetic arithmetic -> arithmetic(arithmetic, problems);
            case BoundExpression.Negate negate -> {
                Object operand = evaluate(negate.operand(), problems);
                if (operand == NOT_CONSTANT) {
                    yield NOT_CONSTANT;
                }
                yield switch (negate.kind()) {
                    case INT -> {
                        int value = (Integer) operand;
                        if (value == Integer.MIN_VALUE) {
                            problems.overflow(negate);
                        }
                        yield -value;
                    }
                    case LONG -> {
                        long value = (Long) operand;
                        if (value == Long.MIN_VALUE) {
                            problems.overflow(negate);
                        }
                        yield -value;
                    }
                    case FLOAT -> -(Float) operand;
                    case DOUBLE -> -(Double) operand;
                    default -> NOT_CONSTANT;
                };
            }
            case BoundExpression.Not not -> {
                Object operand = evaluate(not.operand(), problems);
                yield operand == NOT_CONSTANT ? NOT_CONSTANT : !(Boolean) operand;
            }
            case BoundExpression.Logical logical -> {
                Object left = evaluate(logical.left(), problems);
                Object right = evaluate(logical.right(), problems);
                if (left == NOT_CONSTANT || right == NOT_CONSTANT) {
                    yield NOT_CONSTANT;
                }
                yield logical.and() ? (Boolean) left && (Boolean) right : (Boolean) left || (Boolean) right;
            }
            case BoundExpression.Compare compare -> compare(compare, problems);
            case BoundExpression.Concat concat -> {
                StringBuilder builder = new StringBuilder();
                for (BoundExpression part : concat.parts()) {
                    Object value = evaluate(part, problems);
                    if (value == NOT_CONSTANT) {
                        yield NOT_CONSTANT;
                    }
                    builder.append((String) value);
                }
                yield builder.toString();
            }
            case BoundExpression.Conversion conversion -> conversion(conversion, problems);
            case BoundExpression.NullCheck check -> {
                Object operand = evaluate(check.operand(), problems);
                yield operand == NOT_CONSTANT ? NOT_CONSTANT : (operand == null) == check.isNull();
            }
            default -> NOT_CONSTANT;
        };
    }

    private static Object conversion(BoundExpression.Conversion conversion, Problems problems) {
        Object operand = evaluate(conversion.operand(), problems);
        if (operand == NOT_CONSTANT) {
            return NOT_CONSTANT;
        }
        return switch (conversion.kind()) {
            case NUMERIC -> Conversions.convertNumber(operand, (PrimitiveType) conversion.type());
            case BOX, UNBOX -> operand;
            case TO_STRING -> toText(operand, conversion.operand().type() == PrimitiveType.DURATION);
            case STRING_TO_COMPONENT -> NOT_CONSTANT;
        };
    }

    /** Canonical text of a constant value. */
    static String toText(Object value, boolean duration) {
        return switch (value) {
            case null -> "null";
            case Long l when duration -> Values.durationToString(l);
            case Integer i -> Values.toString((int) i);
            case Long l -> Values.toString((long) l);
            case Float f -> Values.toString((float) f);
            case Double d -> Values.toString((double) d);
            case Boolean b -> Values.toString((boolean) b);
            default -> Values.toString(value);
        };
    }

    private static Object arithmetic(BoundExpression.Arithmetic arithmetic, Problems problems) {
        Object left = evaluate(arithmetic.left(), problems);
        Object right = evaluate(arithmetic.right(), problems);
        if (left == NOT_CONSTANT || right == NOT_CONSTANT) {
            return NOT_CONSTANT;
        }
        return switch (arithmetic.kind()) {
            case INT -> {
                int a = (Integer) left;
                int b = (Integer) right;
                yield switch (arithmetic.op()) {
                    case ADD -> checked(arithmetic, problems, (long) a + b, a + b);
                    case SUBTRACT -> checked(arithmetic, problems, (long) a - b, a - b);
                    case MULTIPLY -> checked(arithmetic, problems, (long) a * b, a * b);
                    case DIVIDE -> {
                        if (b == 0) {
                            problems.divisionByZero(arithmetic);
                            yield NOT_CONSTANT;
                        }
                        if (a == Integer.MIN_VALUE && b == -1) {
                            problems.overflow(arithmetic);
                        }
                        yield a / b;
                    }
                    case REMAINDER -> {
                        if (b == 0) {
                            problems.divisionByZero(arithmetic);
                            yield NOT_CONSTANT;
                        }
                        yield a % b;
                    }
                };
            }
            case LONG -> {
                long a = (Long) left;
                long b = (Long) right;
                yield switch (arithmetic.op()) {
                    case ADD -> exact(arithmetic, problems, () -> Math.addExact(a, b), a + b);
                    case SUBTRACT -> exact(arithmetic, problems, () -> Math.subtractExact(a, b), a - b);
                    case MULTIPLY -> exact(arithmetic, problems, () -> Math.multiplyExact(a, b), a * b);
                    case DIVIDE -> {
                        if (b == 0) {
                            problems.divisionByZero(arithmetic);
                            yield NOT_CONSTANT;
                        }
                        if (a == Long.MIN_VALUE && b == -1) {
                            problems.overflow(arithmetic);
                        }
                        yield a / b;
                    }
                    case REMAINDER -> {
                        if (b == 0) {
                            problems.divisionByZero(arithmetic);
                            yield NOT_CONSTANT;
                        }
                        yield a % b;
                    }
                };
            }
            case FLOAT -> {
                float a = (Float) left;
                float b = (Float) right;
                yield switch (arithmetic.op()) {
                    case ADD -> a + b;
                    case SUBTRACT -> a - b;
                    case MULTIPLY -> a * b;
                    case DIVIDE -> a / b;
                    case REMAINDER -> a % b;
                };
            }
            case DOUBLE -> {
                double a = (Double) left;
                double b = (Double) right;
                yield switch (arithmetic.op()) {
                    case ADD -> a + b;
                    case SUBTRACT -> a - b;
                    case MULTIPLY -> a * b;
                    case DIVIDE -> a / b;
                    case REMAINDER -> a % b;
                };
            }
            default -> NOT_CONSTANT;
        };
    }

    private static Object checked(BoundExpression expression, Problems problems, long wide, int wrapped) {
        if (wide != wrapped) {
            problems.overflow(expression);
        }
        return wrapped;
    }

    private interface LongOp {
        long apply();
    }

    private static Object exact(BoundExpression expression, Problems problems, LongOp exact, long wrapped) {
        try {
            return exact.apply();
        } catch (ArithmeticException overflow) {
            problems.overflow(expression);
            return wrapped;
        }
    }

    private static Object compare(BoundExpression.Compare compare, Problems problems) {
        Object left = evaluate(compare.left(), problems);
        Object right = evaluate(compare.right(), problems);
        if (left == NOT_CONSTANT || right == NOT_CONSTANT) {
            return NOT_CONSTANT;
        }
        Representation kind = compare.kind();
        if (kind == Representation.REF || kind == Representation.BOOL) {
            boolean equal = java.util.Objects.equals(left, right);
            return switch (compare.op()) {
                case EQUAL -> equal;
                case NOT_EQUAL -> !equal;
                default -> NOT_CONSTANT;
            };
        }
        int order = switch (kind) {
            case INT -> Integer.compare((Integer) left, (Integer) right);
            case LONG -> Long.compare((Long) left, (Long) right);
            // Primitive float/double comparison semantics (NaN is unordered) differ from
            // Double.compare, so compare with the operators themselves.
            case FLOAT -> {
                float a = (Float) left;
                float b = (Float) right;
                yield compareFloating(compare.op(), a, b) ? 0 : 1;
            }
            case DOUBLE -> {
                double a = (Double) left;
                double b = (Double) right;
                yield compareFloating(compare.op(), a, b) ? 0 : 1;
            }
            default -> 0;
        };
        if (kind == Representation.FLOAT || kind == Representation.DOUBLE) {
            return order == 0;
        }
        return switch (compare.op()) {
            case EQUAL -> order == 0;
            case NOT_EQUAL -> order != 0;
            case LESS -> order < 0;
            case LESS_EQUAL -> order <= 0;
            case GREATER -> order > 0;
            case GREATER_EQUAL -> order >= 0;
        };
    }

    private static boolean compareFloating(ComparisonOp op, double a, double b) {
        return switch (op) {
            case EQUAL -> a == b;
            case NOT_EQUAL -> a != b;
            case LESS -> a < b;
            case LESS_EQUAL -> a <= b;
            case GREATER -> a > b;
            case GREATER_EQUAL -> a >= b;
        };
    }

    /** Whether {@code value} may be used as the value of a {@code const}. */
    static boolean isConstantType(dev.tachyonscript.api.type.Type type) {
        return type instanceof PrimitiveType primitive && primitive != PrimitiveType.VOID || type == Types.STRING;
    }
}
