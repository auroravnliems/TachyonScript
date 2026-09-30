package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.FunctionType;
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
     * A constant. {@code value} is an {@code Integer}, {@code Long} (also for durations and
     * instants, in milliseconds), {@code Float}, {@code Double}, {@code Boolean}, {@code String},
     * or {@code null} for the null literal.
     */
    record Literal(Object value, Type type, Span span) implements BoundExpression {
    }

    /** A named constant of a keyed type ({@code Material.DIAMOND}), resolved by the linker. */
    record KeyedConstant(ClassType type, String name, String key, Span span) implements BoundExpression {
    }

    /** Reads a local. {@code type} is the (possibly narrowed) type at this point. */
    record LocalLoad(LocalSymbol local, Type type, Span span) implements BoundExpression {
    }

    /** Reads a top-level variable ({@code let}, {@code var} or {@code persistent var}). */
    record GlobalLoad(GlobalSymbol global, Type type, Span span) implements BoundExpression {
    }

    /** Reads a {@code playerdata var} of a player ({@code player.coins}). */
    record PlayerDataLoad(GlobalSymbol global, BoundExpression owner, Type type, Span span) implements BoundExpression {
    }

    /** Calls a host operation; for members the receiver is argument 0. */
    record NativeCall(NativeDeclaration target, List<BoundExpression> arguments, Type type, Span span)
            implements BoundExpression {
        public NativeCall {
            arguments = List.copyOf(arguments);
        }
    }

    /** Calls a function declared in a script (a method gets its receiver as argument 0). */
    record FunctionCall(FunctionSymbol function, List<BoundExpression> arguments, Type type, Span span)
            implements BoundExpression {
        public FunctionCall {
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * A lambda or scheduled block. {@code captures} are the lambda's copies of the enclosing
     * locals it uses and {@code captureValues} their values at creation, in the same order;
     * the lowered function takes the captures first, then {@code parameters}.
     */
    record Lambda(String key, String displayName, List<LocalSymbol> parameters, List<LocalSymbol> captures,
                  List<BoundExpression> captureValues, Type returnType, BoundStatement.Block body, FunctionType type,
                  Span span) implements BoundExpression {
        public Lambda {
            parameters = List.copyOf(parameters);
            captures = List.copyOf(captures);
            captureValues = List.copyOf(captureValues);
        }
    }

    /** A script function used as a value ({@code list.sortBy(levelOf)}). */
    record FunctionReference(FunctionSymbol function, FunctionType type, Span span) implements BoundExpression {
    }

    /** Calls a function value ({@code f(x)}). */
    record ClosureCall(BoundExpression callee, List<BoundExpression> arguments, Type type, Span span)
            implements BoundExpression {
        public ClosureCall {
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

    /** {@code condition ? whenTrue : whenFalse}; both branches already converted to {@code type}. */
    record Conditional(BoundExpression condition, BoundExpression whenTrue, BoundExpression whenFalse, Type type,
                       Span span) implements BoundExpression {
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

    /** {@code operand is target}; {@code record} is the declaring record when the target is a script record type. */
    record TypeTest(BoundExpression operand, ClassType target, RecordSymbol record, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
        }
    }

    /**
     * Checked reference cast; {@code safe} casts produce null instead of failing. {@code record}
     * is the declaring record when the target is a script record type.
     */
    record Cast(BoundExpression operand, ClassType target, RecordSymbol record, boolean safe, Type type, Span span)
            implements BoundExpression {
    }

    record ListLiteral(List<BoundExpression> elements, ListType type, Span span) implements BoundExpression {
        public ListLiteral {
            elements = List.copyOf(elements);
        }
    }

    /** A map literal; keys and values are already converted to the map's types. */
    record MapLiteral(List<BoundExpression> keys, List<BoundExpression> values, Type type, Span span)
            implements BoundExpression {
        public MapLiteral {
            keys = List.copyOf(keys);
            values = List.copyOf(values);
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

    /** Creates a record value; {@code fields} are in declaration order, defaults filled in. */
    record NewRecord(RecordSymbol record, List<BoundExpression> fields, Span span) implements BoundExpression {
        public NewRecord {
            fields = List.copyOf(fields);
        }

        @Override
        public Type type() {
            return record.type();
        }
    }

    /** Reads a field of a record value. */
    record RecordGet(BoundExpression receiver, RecordSymbol.Field field, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return field.type();
        }
    }

    /** Evaluates {@code value} into {@code local}, then {@code body} (which reads the local). */
    record Let(LocalSymbol local, BoundExpression value, BoundExpression body, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return body.type();
        }
    }

    /**
     * In the module initializer: loads the saved value of a {@code persistent var} into it;
     * true when there was one (otherwise the initializer assigns the default).
     */
    record GlobalRestore(GlobalSymbol global, Span span) implements BoundExpression {
        @Override
        public Type type() {
            return PrimitiveType.BOOL;
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
