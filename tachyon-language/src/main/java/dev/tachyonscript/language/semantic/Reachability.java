package dev.tachyonscript.language.semantic;

/**
 * Control-flow facts about bound statements, following Java's "can complete normally"
 * rules: {@code return}, {@code break}, {@code continue} and {@code throw} never complete
 * normally; an {@code if} with both branches completes if either does; {@code while true}
 * completes only if its body can {@code break}; a {@code try} completes if its body or its
 * catch block does and its finally block does.
 */
final class Reachability {

    private Reachability() {
    }

    static boolean completesNormally(BoundStatement statement) {
        return switch (statement) {
            case BoundStatement.Return ignored -> false;
            case BoundStatement.Break ignored -> false;
            case BoundStatement.Continue ignored -> false;
            case BoundStatement.Throw ignored -> false;
            case BoundStatement.Block block -> {
                for (BoundStatement inner : block.statements()) {
                    if (!completesNormally(inner)) {
                        yield false;
                    }
                }
                yield true;
            }
            case BoundStatement.If anIf -> anIf.elseBranch() == null
                    || completesNormally(anIf.thenBranch()) || completesNormally(anIf.elseBranch());
            case BoundStatement.While loop -> !isLiteralTrue(loop.condition()) || containsBreak(loop.body());
            case BoundStatement.Try tryStatement -> {
                if (tryStatement.finallyBody() != null && !completesNormally(tryStatement.finallyBody())) {
                    yield false;
                }
                yield completesNormally(tryStatement.body())
                        || (tryStatement.catchBody() != null && completesNormally(tryStatement.catchBody()));
            }
            default -> true;
        };
    }

    /** Whether {@code body} contains a {@code break} that exits the loop owning {@code body}. */
    static boolean containsBreak(BoundStatement body) {
        return switch (body) {
            case BoundStatement.Break ignored -> true;
            case BoundStatement.Block block -> block.statements().stream().anyMatch(Reachability::containsBreak);
            case BoundStatement.If anIf -> containsBreak(anIf.thenBranch())
                    || (anIf.elseBranch() != null && containsBreak(anIf.elseBranch()));
            case BoundStatement.Try tryStatement -> containsBreak(tryStatement.body())
                    || (tryStatement.catchBody() != null && containsBreak(tryStatement.catchBody()))
                    || (tryStatement.finallyBody() != null && containsBreak(tryStatement.finallyBody()));
            // A break inside a nested loop exits that loop, not ours.
            default -> false;
        };
    }

    static boolean isLiteralTrue(BoundExpression expression) {
        return expression instanceof BoundExpression.Literal literal && Boolean.TRUE.equals(literal.value());
    }
}
