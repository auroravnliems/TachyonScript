package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

import java.util.List;

/** Statements. */
public sealed interface Statement extends Node {

    /** {@code { statements }} */
    record Block(List<Statement> statements, Span span) implements Statement {
        public Block {
            statements = List.copyOf(statements);
        }
    }

    /**
     * {@code let name: Type = initializer} or {@code var ...} when {@code mutable}.
     * {@code type} and {@code initializer} are {@code null} when omitted.
     */
    record LocalVariable(Identifier name, TypeRef type, Expression initializer, boolean mutable, Span span)
            implements Statement {
    }

    /** {@code target = value}, {@code target += value}, ... */
    record Assignment(Expression target, AssignmentOperator operator, Expression value, Span span)
            implements Statement {
    }

    record ExpressionStatement(Expression expression, Span span) implements Statement {
    }

    /** {@code elseBranch} is a {@link Block}, another {@link If}, or {@code null}. */
    record If(Expression condition, Block thenBlock, Statement elseBranch, Span span) implements Statement {
    }

    record While(Expression condition, Block body, Span span) implements Statement {
    }

    /** {@code for variable in iterable { body }}; {@code iterable} may be a range. */
    record For(Identifier variable, Expression iterable, Block body, Span span) implements Statement {
    }

    /** {@code value} is {@code null} for a bare {@code return}. */
    record Return(Expression value, Span span) implements Statement {
    }

    record Break(Span span) implements Statement {
    }

    record Continue(Span span) implements Statement {
    }
}
