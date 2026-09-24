package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.language.source.Span;

import java.util.List;

/** A type-checked statement. */
public sealed interface BoundStatement {

    Span span();

    record Block(List<BoundStatement> statements, Span span) implements BoundStatement {
        public Block {
            statements = List.copyOf(statements);
        }
    }

    record LocalDeclaration(LocalSymbol local, BoundExpression initializer, Span span) implements BoundStatement {
    }

    record LocalAssignment(LocalSymbol local, BoundExpression value, Span span) implements BoundStatement {
    }

    /** Calls {@code setter}; {@code receiver} is {@code null} for global properties. */
    record PropertyAssignment(BoundExpression receiver, NativeDeclaration setter, BoundExpression value, Span span)
            implements BoundStatement {
    }

    record ListSet(BoundExpression list, BoundExpression index, BoundExpression value, Span span)
            implements BoundStatement {
    }

    record ExpressionStatement(BoundExpression expression, Span span) implements BoundStatement {
    }

    /** {@code elseBranch} may be {@code null}. */
    record If(BoundExpression condition, BoundStatement thenBranch, BoundStatement elseBranch, Span span)
            implements BoundStatement {
    }

    record While(BoundExpression condition, BoundStatement body, Span span) implements BoundStatement {
    }

    /**
     * {@code for variable in start..end}: {@code start} and {@code end} are evaluated once;
     * {@code kind} is INT or LONG.
     */
    record ForRange(LocalSymbol variable, BoundExpression start, BoundExpression end, boolean inclusive,
                    Representation kind, BoundStatement body, Span span) implements BoundStatement {
    }

    /** {@code for variable in list}; iterates over a snapshot-free view of the list. */
    record ForEach(LocalSymbol variable, BoundExpression iterable, BoundStatement body, Span span)
            implements BoundStatement {
    }

    /** {@code value} is {@code null} for a bare return. */
    record Return(BoundExpression value, Span span) implements BoundStatement {
    }

    record Break(Span span) implements BoundStatement {
    }

    record Continue(Span span) implements BoundStatement {
    }
}
