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

    /** Assigns a top-level variable. */
    record GlobalStore(GlobalSymbol global, BoundExpression value, Span span) implements BoundStatement {
    }

    /**
     * {@code global += delta} or {@code -=} on a numeric top-level variable, performed atomically
     * so that concurrent handlers (asynchronous chat, Folia regions) never lose an update.
     * {@code delta} has the variable's type; subtraction is already folded into a negation.
     */
    record GlobalAdd(GlobalSymbol global, BoundExpression delta, Span span) implements BoundStatement {
    }

    /** Assigns a {@code playerdata var} of a player. */
    record PlayerDataStore(GlobalSymbol global, BoundExpression owner, BoundExpression value, Span span)
            implements BoundStatement {
    }

    /**
     * {@code player.data += delta} or {@code -=} on a numeric {@code playerdata var}, performed
     * atomically; {@code delta} has the variable's type (subtraction is folded into a negation).
     */
    record PlayerDataAdd(GlobalSymbol global, BoundExpression owner, BoundExpression delta, Span span)
            implements BoundStatement {
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

    /**
     * {@code try { body } catch e { catchBody } finally { finallyBody }}. {@code catchLocal}
     * receives the error ({@code Error}); it is a hidden local for {@code catch { }}.
     * {@code catchBody} or {@code finallyBody} may be null, not both.
     */
    record Try(Block body, LocalSymbol catchLocal, Block catchBody, Block finallyBody, Span span)
            implements BoundStatement {
    }

    /** {@code throw value}; {@code value} is a {@code string} message or an {@code Error}. */
    record Throw(BoundExpression value, Span span) implements BoundStatement {
    }
}
