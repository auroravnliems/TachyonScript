package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Types;

import java.util.List;

/**
 * Renders bound trees as S-expressions with resolved targets and types, e.g.
 * {@code (call Player.send(Component) player (template "Hello " (call Entity.name:get player) "!"))}.
 * Used by tests and by the {@code dump} debug commands.
 */
public final class BoundPrinter {

    private BoundPrinter() {
    }

    public static String print(BoundModule module) {
        StringBuilder out = new StringBuilder();
        for (ConstantSymbol constant : module.constants()) {
            out.append("(const ").append(constant.name()).append(':')
                    .append(constant.type() == null ? "?" : constant.type().displayName()).append(' ')
                    .append(constant.value() instanceof String text ? quote(text) : String.valueOf(constant.value()))
                    .append(")\n");
        }
        for (BoundFunction function : module.functions()) {
            out.append(print(function)).append('\n');
        }
        for (BoundEventHandler handler : module.handlers()) {
            out.append(print(handler)).append('\n');
        }
        return out.toString();
    }

    public static String print(BoundFunction function) {
        return "(function " + function.symbol() + " " + print(function.body()) + ")";
    }

    public static String print(BoundEventHandler handler) {
        StringBuilder out = new StringBuilder("(event ").append(handler.event().name()).append(" [");
        for (int i = 0; i < handler.eventVariables().size(); i++) {
            out.append(i == 0 ? "" : " ").append(handler.eventVariables().get(i).name());
        }
        return out.append("] ").append(print(handler.body())).append(')').toString();
    }

    public static String print(BoundStatement statement) {
        return switch (statement) {
            case BoundStatement.Block block -> {
                StringBuilder out = new StringBuilder("{");
                for (BoundStatement inner : block.statements()) {
                    out.append(' ').append(print(inner));
                }
                yield out.append(block.statements().isEmpty() ? "}" : " }").toString();
            }
            case BoundStatement.LocalDeclaration declaration -> "(let " + declaration.local().name() + ":"
                    + declaration.local().type().displayName() + " " + print(declaration.initializer()) + ")";
            case BoundStatement.LocalAssignment assignment -> "(set " + assignment.local().name() + " "
                    + print(assignment.value()) + ")";
            case BoundStatement.PropertyAssignment assignment -> "(call " + assignment.setter().key()
                    + (assignment.receiver() != null ? " " + print(assignment.receiver()) : "") + " "
                    + print(assignment.value()) + ")";
            case BoundStatement.ListSet set -> "(list-set " + print(set.list()) + " " + print(set.index()) + " "
                    + print(set.value()) + ")";
            case BoundStatement.ExpressionStatement expression -> print(expression.expression());
            case BoundStatement.If anIf -> "(if " + print(anIf.condition()) + " " + print(anIf.thenBranch())
                    + (anIf.elseBranch() != null ? " else " + print(anIf.elseBranch()) : "") + ")";
            case BoundStatement.While loop -> "(while " + print(loop.condition()) + " " + print(loop.body()) + ")";
            case BoundStatement.ForRange loop -> "(for " + loop.variable().name() + ":" + loop.kind().name().toLowerCase()
                    + " " + print(loop.start()) + (loop.inclusive() ? " .. " : " ..< ") + print(loop.end()) + " "
                    + print(loop.body()) + ")";
            case BoundStatement.ForEach loop -> "(for " + loop.variable().name() + ":" + loop.variable().type().displayName()
                    + " " + print(loop.iterable()) + " " + print(loop.body()) + ")";
            case BoundStatement.Return ret -> ret.value() == null ? "(return)" : "(return " + print(ret.value()) + ")";
            case BoundStatement.Break ignored -> "(break)";
            case BoundStatement.Continue ignored -> "(continue)";
        };
    }

    public static String print(BoundExpression expression) {
        return switch (expression) {
            case BoundExpression.Literal literal -> literal(literal);
            case BoundExpression.LocalLoad load -> load.type().equals(load.local().type())
                    ? load.local().name() : load.local().name() + "!" + load.type().displayName();
            case BoundExpression.NativeCall call -> call("call " + call.target().key(), call.arguments());
            case BoundExpression.FunctionCall call -> call("call " + call.function().key(), call.arguments());
            case BoundExpression.Arithmetic arithmetic -> "(" + arithmetic.op().name().toLowerCase() + ":"
                    + arithmetic.kind().name().toLowerCase() + " " + print(arithmetic.left()) + " "
                    + print(arithmetic.right()) + ")";
            case BoundExpression.Negate negate -> "(neg:" + negate.kind().name().toLowerCase() + " "
                    + print(negate.operand()) + ")";
            case BoundExpression.Not not -> "(not " + print(not.operand()) + ")";
            case BoundExpression.Compare compare -> "(" + compare.op().name().toLowerCase() + ":"
                    + compare.kind().name().toLowerCase() + " " + print(compare.left()) + " " + print(compare.right()) + ")";
            case BoundExpression.NullCheck check -> "(" + (check.isNull() ? "is-null " : "non-null ")
                    + print(check.operand()) + ")";
            case BoundExpression.Logical logical -> "(" + (logical.and() ? "and " : "or ") + print(logical.left()) + " "
                    + print(logical.right()) + ")";
            case BoundExpression.Concat concat -> call("concat", concat.parts());
            case BoundExpression.ComponentTemplate template -> {
                StringBuilder out = new StringBuilder("(template ").append(quote(template.segments().getFirst()));
                for (int i = 0; i < template.arguments().size(); i++) {
                    out.append(' ').append(print(template.arguments().get(i))).append(' ')
                            .append(quote(template.segments().get(i + 1)));
                }
                yield out.append(')').toString();
            }
            case BoundExpression.Conversion conversion -> "(" + conversion.kind().name().toLowerCase().replace('_', '-')
                    + ":" + conversion.type().displayName() + " " + print(conversion.operand()) + ")";
            case BoundExpression.SafeAccess access -> "(?. " + print(access.receiver()) + " -> "
                    + access.temporary().name() + " " + print(access.access()) + ")";
            case BoundExpression.Coalesce coalesce -> "(?? " + print(coalesce.left()) + " -> " + coalesce.temporary().name()
                    + " " + print(coalesce.nonNullValue()) + " " + print(coalesce.fallback()) + ")";
            case BoundExpression.TypeTest test -> "(is " + print(test.operand()) + " " + test.target().name() + ")";
            case BoundExpression.Cast cast -> "(" + (cast.safe() ? "as? " : "as ") + print(cast.operand()) + " "
                    + cast.target().name() + ")";
            case BoundExpression.ListLiteral list -> call("list:" + list.type().displayName(), list.elements());
            case BoundExpression.ListGet get -> "(list-get " + print(get.list()) + " " + print(get.index()) + ")";
            case BoundExpression.ListSize size -> "(list-size " + print(size.list()) + ")";
            case BoundExpression.ListContains contains -> "(list-contains " + print(contains.list()) + " "
                    + print(contains.element()) + ")";
            case BoundExpression.ListAdd add -> "(list-add " + print(add.list()) + " " + print(add.element()) + ")";
            case BoundExpression.Error ignored -> "<error>";
        };
    }

    private static String literal(BoundExpression.Literal literal) {
        Object value = literal.value();
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        String suffix = literal.type() == Types.DURATION ? "ms" : ":" + literal.type().displayName();
        return value + suffix;
    }

    private static String call(String head, List<BoundExpression> arguments) {
        StringBuilder out = new StringBuilder("(").append(head);
        for (BoundExpression argument : arguments) {
            out.append(' ').append(print(argument));
        }
        return out.append(')').toString();
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
