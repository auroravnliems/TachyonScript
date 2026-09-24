package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

import java.util.List;

/** Expressions. */
public sealed interface Expression extends Node {

    /** Kinds of literal values. */
    enum LiteralKind {
        /** Value: {@code Long} magnitude (range-checked by the binder, which knows about negation). */
        INT,
        /** Value: {@code Long} magnitude. */
        LONG,
        /** Value: {@code Float}. */
        FLOAT,
        /** Value: {@code Double}. */
        DOUBLE,
        /** Value: {@code Boolean}. */
        BOOL,
        /** Value: {@code String}. */
        STRING,
        /** Value: {@code null}. */
        NULL
    }

    /** {@code 5}, {@code 2.5}, {@code "text"}, {@code true}, {@code null}. */
    record Literal(LiteralKind kind, Object value, Span span) implements Expression {
    }

    /**
     * An interpolated string {@code "Hello {player.name}!"}: {@code segments} are the literal
     * parts (always {@code parts.size() + 1} of them, possibly empty).
     */
    record Template(List<String> segments, List<Expression> parts, Span span) implements Expression {
        public Template {
            segments = List.copyOf(segments);
            parts = List.copyOf(parts);
            if (segments.size() != parts.size() + 1) {
                throw new IllegalArgumentException("A template needs one more segment than parts");
            }
        }
    }

    /** {@code 5 seconds} */
    record Duration(Literal amount, DurationUnit unit, Span span) implements Expression {
    }

    /** A simple name: a local, parameter, constant, function or namespace. */
    record Name(Identifier identifier) implements Expression {
        @Override
        public Span span() {
            return identifier.span();
        }

        public String name() {
            return identifier.name();
        }
    }

    /** {@code target.member} or, when {@code nullSafe}, {@code target?.member}. */
    record Member(Expression target, Identifier member, boolean nullSafe, Span span) implements Expression {
    }

    /** {@code callee(arguments)} */
    record Call(Expression callee, List<Expression> arguments, Span span) implements Expression {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code target[index]} */
    record Index(Expression target, Expression index, Span span) implements Expression {
    }

    record Unary(UnaryOperator operator, Expression operand, Span span) implements Expression {
    }

    record Binary(BinaryOperator operator, Expression left, Expression right, Span span) implements Expression {
    }

    /** {@code operand is Type} */
    record Is(Expression operand, TypeRef type, Span span) implements Expression {
    }

    /** {@code operand as Type}, or {@code operand as? Type} when {@code safe}. */
    record Cast(Expression operand, TypeRef type, boolean safe, Span span) implements Expression {
    }

    /** {@code start..end} (inclusive) or {@code start..<end}. */
    record Range(Expression start, Expression end, boolean inclusive, Span span) implements Expression {
    }

    /** {@code [a, b, c]} */
    record ListLiteral(List<Expression> elements, Span span) implements Expression {
        public ListLiteral {
            elements = List.copyOf(elements);
        }
    }

    /** {@code (inner)}; kept for faithful source representation. */
    record Parenthesized(Expression inner, Span span) implements Expression {
    }

    /** Placeholder produced by error recovery. Never reaches later stages without an error. */
    record Error(Span span) implements Expression {
    }
}
