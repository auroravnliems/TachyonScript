package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.language.source.Span;

import java.util.List;

/**
 * A type-checked expression. Every name is resolved, every operator is specialised by
 * operand representation, and every implicit conversion is an explicit {@link Conversion}.
 */
public sealed interface BoundExpression {

    Type type();

    Span span();

    /**
     * A constant. {@code value} is an {@code Integer}, {@code Long} (also for durations, in
     * milliseconds), {@code Float}, {@code Double}, {@code Boolean}, {@code String}, or
     * {@code null} for the null literal.
     */
    record Literal(Object value, Type type, Span span) implements BoundExpression {
    }

    /** Reads a local. {@code type} is the (possibly narrowed) type at this point. */
    record LocalLoad(LocalSymbol local, Type type, Span span) implements BoundExpression {
    }

    /** Calls a host operation; for members the receiver is argument 0. */
    record NativeCall(NativeDeclaration target, List<BoundExpression> arguments, Type type, Span span)
            implements BoundExpression {
        public NativeCall {
            arguments = List.copyOf(arguments);
        }
    }

    /** Calls a function declared in the script. */
    record FunctionCall(FunctionSymbol function, List<BoundExpression> arguments, Type type, Span span)
            implements BoundExpression {
        public FunctionCall {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code kind} is the operand representation (INT, LONG, FLOAT or DOUBLE). */
    record Arithmetic(ArithmeticOp op, Representation kind, BoundExpression left, BoundExpression right, Type type,
                      Span span) implements BoundExpression {
    }

    record Negate(Representation kind, BoundExpression operand, Type type, Span span) implements BoundExpression {
    }

    record Not(BoundExpression operand, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
        }
    }

    /** {@code kind} is the representation both operands were converted to. */
    record Compare(ComparisonOp op, Representation kind, BoundExpression left, BoundExpression right, Span span)
            implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
        }
    }

    /** {@code operand == null} when {@code isNull}, otherwise {@code operand != null}. */
    record NullCheck(BoundExpression operand, boolean isNull, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
        }
    }

    /** Short-circuit {@code &&} (when {@code and}) or {@code ||}. */
    record Logical(boolean and, BoundExpression left, BoundExpression right, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
        }
    }

    /** Concatenation of string-typed parts. */
    record Concat(List<BoundExpression> parts, Span span) implements BoundExpression {
        public Concat {
            parts = List.copyOf(parts);
        }

        @Override
        public Type type() {
            return Types.STRING;
        }
    }

    /**
     * A message template: {@code segments} are MiniMessage text (one more than arguments);
     * {@code arguments} are {@code string} values (inserted as plain text) or {@code Component}s.
     */
    record ComponentTemplate(List<String> segments, List<BoundExpression> arguments, Span span)
            implements BoundExpression {
        public ComponentTemplate {
            segments = List.copyOf(segments);
            arguments = List.copyOf(arguments);
        }

        @Override
        public Type type() {
            return Types.COMPONENT;
        }
    }

    record Conversion(ConversionKind kind, BoundExpression operand, Type type, Span span) implements BoundExpression {
    }

    /**
     * {@code receiver?.access}: {@code receiver} is stored in {@code temporary}; when it is
     * non-null, {@code access} (which reads the temporary) is the result, otherwise null.
     */
    record SafeAccess(BoundExpression receiver, LocalSymbol temporary, BoundExpression access, Type type, Span span)
            implements BoundExpression {
    }

    /**
     * {@code left ?? fallback}: {@code left} is stored in {@code temporary}; when non-null the
     * result is {@code nonNullValue} (computed from the temporary), otherwise {@code fallback}.
     */
    record Coalesce(BoundExpression left, LocalSymbol temporary, BoundExpression nonNullValue,
                    BoundExpression fallback, Type type, Span span) implements BoundExpression {
    }

    /** {@code operand is target} */
    record TypeTest(BoundExpression operand, ClassType target, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
        }
    }

    /** Checked reference cast; {@code safe} casts produce null instead of failing. */
    record Cast(BoundExpression operand, ClassType target, boolean safe, Type type, Span span)
            implements BoundExpression {
    }

    record ListLiteral(List<BoundExpression> elements, ListType type, Span span) implements BoundExpression {
        public ListLiteral {
            elements = List.copyOf(elements);
        }
    }

    record ListGet(BoundExpression list, BoundExpression index, Type type, Span span) implements BoundExpression {
    }

    record ListSize(BoundExpression list, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.INT;
        }
    }

    record ListContains(BoundExpression list, BoundExpression element, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
        }
    }

    record ListAdd(BoundExpression list, BoundExpression element, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.VOID;
        }
    }

    /** An expression that failed to bind; its error has been reported. */
    record Error(Span span) implements BoundExpression {
        @Override
        public Type type() {
            return Types.ERROR;
        }
    }
}
