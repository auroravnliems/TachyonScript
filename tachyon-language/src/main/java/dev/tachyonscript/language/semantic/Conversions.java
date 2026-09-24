package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.NullType;
import dev.tachyonscript.api.type.NullableType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;

import java.util.List;

/**
 * Implicit conversion rules.
 *
 * <p>An implicit conversion has a cost used by overload resolution: {@code 0} for identity,
 * subtyping and {@code null}; {@code 1} for numeric widening or boxing; {@code 2} for
 * {@code string → Component} (a MiniMessage parse). {@link #NONE} means no implicit
 * conversion exists.
 *
 * <p>Numeric widening follows Java, minus lossy {@code long → float}: {@code int → long},
 * {@code int → float}, {@code int → double}, {@code long → double}, {@code float → double}.
 * Nullable values never convert implicitly to non-nullable ones; that requires a null check,
 * {@code ?.} or {@code ??}.
 */
final class Conversions {

    static final int NONE = -1;

    private Conversions() {
    }

    static int cost(Type from, Type to) {
        if (from.isError() || to.isError() || from.equals(to)) {
            return 0;
        }
        if (from instanceof NullType) {
            return to.isNullable() ? 0 : NONE;
        }
        if (to instanceof NullableType(Type inner)) {
            if (from instanceof NullableType(Type fromInner)) {
                return isReferenceUpcast(fromInner, inner) ? 0 : NONE;
            }
            int cost = cost(from, inner);
            if (cost == NONE) {
                return NONE;
            }
            return inner.representation().isPrimitive() ? cost + 1 : cost;
        }
        if (to == Types.ANY) {
            if (from.isNullable() || from == PrimitiveType.VOID) {
                return NONE;
            }
            return from.representation().isPrimitive() ? 1 : 0;
        }
        if (from.isNullable()) {
            return NONE;
        }
        if (from instanceof PrimitiveType source && to instanceof PrimitiveType target) {
            return isNumericWidening(source, target) ? 1 : NONE;
        }
        if (from instanceof ClassType source && to instanceof ClassType target) {
            if (source.isSubtypeOf(target)) {
                return 0;
            }
            if (source == Types.STRING && target == Types.COMPONENT) {
                return 2;
            }
        }
        return NONE;
    }

    static boolean isAssignable(Type from, Type to) {
        return cost(from, to) != NONE;
    }

    static boolean isNumericWidening(PrimitiveType from, PrimitiveType to) {
        return switch (from) {
            case INT -> to == PrimitiveType.LONG || to == PrimitiveType.FLOAT || to == PrimitiveType.DOUBLE;
            case LONG -> to == PrimitiveType.DOUBLE;
            case FLOAT -> to == PrimitiveType.DOUBLE;
            default -> false;
        };
    }

    private static boolean isReferenceUpcast(Type from, Type to) {
        if (from.equals(to) || to == Types.ANY && from.representation() == Representation.REF) {
            return true;
        }
        return from instanceof ClassType source && to instanceof ClassType target && source.isSubtypeOf(target);
    }

    /**
     * Wraps {@code expression} in the conversions needed to obtain {@code target}. The
     * conversion must exist ({@link #cost} not {@link #NONE}). Conversions of literals are
     * folded into new literals.
     */
    static BoundExpression apply(BoundExpression expression, Type target) {
        Type from = expression.type();
        if (from.isError() || target.isError() || from.equals(target) || from instanceof NullType) {
            return expression;
        }
        if (target instanceof NullableType(Type inner)) {
            if (from.isNullable()) {
                return expression;
            }
            BoundExpression converted = apply(expression, inner);
            if (inner.representation().isPrimitive()) {
                return new BoundExpression.Conversion(ConversionKind.BOX, converted, target, expression.span());
            }
            return converted;
        }
        if (target == Types.ANY) {
            if (from.representation().isPrimitive()) {
                return new BoundExpression.Conversion(ConversionKind.BOX, expression, target, expression.span());
            }
            return expression;
        }
        if (from instanceof PrimitiveType && target instanceof PrimitiveType primitive) {
            if (expression instanceof BoundExpression.Literal literal) {
                return new BoundExpression.Literal(convertNumber(literal.value(), primitive), target, literal.span());
            }
            return new BoundExpression.Conversion(ConversionKind.NUMERIC, expression, target, expression.span());
        }
        if (from == Types.STRING && target == Types.COMPONENT) {
            if (expression instanceof BoundExpression.Literal literal) {
                return new BoundExpression.ComponentTemplate(List.of((String) literal.value()), List.of(), literal.span());
            }
            return new BoundExpression.Conversion(ConversionKind.STRING_TO_COMPONENT, expression, target, expression.span());
        }
        // Reference upcasts need no runtime operation.
        return expression;
    }

    /** Converts a boxed numeric constant to the Java box of {@code target} (Java semantics). */
    static Object convertNumber(Object value, PrimitiveType target) {
        Number number = (Number) value;
        return switch (target) {
            case INT -> number.intValue();
            case LONG, DURATION -> number.longValue();
            case FLOAT -> number.floatValue();
            case DOUBLE -> number.doubleValue();
            default -> throw new IllegalArgumentException("Not numeric: " + target);
        };
    }

    /**
     * Explicit ({@code as}) numeric conversion: any numeric primitive to any other, with
     * Java's truncating semantics.
     */
    static boolean isExplicitNumeric(Type from, Type to) {
        return from instanceof PrimitiveType source && source.isNumeric()
                && to instanceof PrimitiveType target && target.isNumeric();
    }
}
