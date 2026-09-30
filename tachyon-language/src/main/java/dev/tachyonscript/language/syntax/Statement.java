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

    /** {@code target = value}, {@code target += value}, ...; {@code x++} is {@code x += 1}. */
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

    /**
     * {@code for variable in iterable { body }}; {@code iterable} may be a range. For maps,
     * {@code for key, value in map { }} has a {@code second} variable; otherwise it is {@code null}.
     */
    record For(Identifier variable, Identifier second, Expression iterable, Block body, Span span) implements Statement {
    }

    /** {@code value} is {@code null} for a bare {@code return}. */
    record Return(Expression value, Span span) implements Statement {
    }

    record Break(Span span) implements Statement {
    }

    record Continue(Span span) implements Statement {
    }

    /** One {@code case a, b -> body} of a switch statement. */
    record SwitchCase(List<Expression> labels, Statement body, Span span) {
        public SwitchCase {
            labels = List.copyOf(labels);
        }
    }

    /** {@code switch subject { case ... -> ...; default -> ... }}; {@code defaultBody} may be null. */
    record Switch(Expression subject, List<SwitchCase> cases, Statement defaultBody, Span span) implements Statement {
        public Switch {
            cases = List.copyOf(cases);
        }
    }

    /**
     * {@code try { } catch e { } finally { }}. {@code catchVariable} and {@code catchBody} are
     * null without a catch clause (then {@code finallyBody} is set); {@code catchVariable} is
     * null for {@code catch { }}.
     */
    record Try(Block body, Identifier catchVariable, Block catchBody, Block finallyBody, Span span)
            implements Statement {
    }

    /** {@code throw value}: a message (string) or a caught {@code Error}. */
    record Throw(Expression value, Span span) implements Statement {
    }

    /** Kinds of scheduling blocks. */
    enum ScheduleKind {
        /** {@code after 5 seconds { }}: once, later. */
        AFTER,
        /** {@code every 5 seconds { }}: repeatedly, until cancelled. */
        EVERY,
        /** {@code async { }}: now, on a background thread. */
        ASYNC,
        /** {@code sync { }}: on the server (global region) thread. */
        SYNC
    }

    /**
     * A block that runs later or elsewhere: {@code after 5 seconds [for entity] { }},
     * {@code every 1 minute [for entity] { }}, {@code async { }} or {@code sync { }}.
     * {@code delay} is null for async and sync; {@code owner} is null unless {@code for} is given.
     */
    record Schedule(ScheduleKind kind, Expression delay, Expression owner, Block body, Span span) implements Statement {
    }
}
