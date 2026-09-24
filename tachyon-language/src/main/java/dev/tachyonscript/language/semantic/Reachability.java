package dev.tachyonscript.language.semantic;

/**
 * Control-flow facts about bound statements, following Java's "can complete normally"
 * rules: {@code return}, {@code break} and {@code continue} never complete normally; an
 * {@code if} with both branches completes if either does; {@code while true} completes only
 * if its body can {@code break}.
 */
final class Reachability {

    private Reachability() {
    }

    static boolean completesNormally(BoundStatement statement) {
        return switch (statement) {
            case BoundStatement.Return ignored -> false;
            case BoundStatement.Break ignored -> false;
            case BoundStatement.Continue ignored -> false;
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
            // A break inside a nested loop exits that loop, not ours.
            default -> false;
        };
    }

    static boolean isLiteralTrue(BoundExpression expression) {
        return expression instanceof BoundExpression.Literal literal && Boolean.TRUE.equals(literal.value());
    }
}
