package dev.tachyonscript.language.syntax;

import java.util.List;

/**
 * Renders syntax trees as compact S-expressions, e.g.
 * {@code (call (member (name player) send) (template "Hello " (name x) "!"))}.
 *
 * <p>Used by tests to assert tree shapes and by the {@code dump-ast} debug commands.
 */
public final class SyntaxPrinter {

    private SyntaxPrinter() {
    }

    public static String print(SourceUnit unit) {
        StringBuilder out = new StringBuilder();
        if (unit.module() != null) {
            out.append("(module ").append(unit.module().name().text()).append(")\n");
        }
        for (Declaration.Import anImport : unit.imports()) {
            out.append(print(anImport)).append('\n');
        }
        for (Declaration declaration : unit.declarations()) {
            out.append(print(declaration)).append('\n');
        }
        return out.toString();
    }

    public static String print(Declaration declaration) {
        String annotations = annotations(declaration.annotations());
        return annotations + switch (declaration) {
            case Declaration.Module module -> "(module " + module.name().text() + ")";
            case Declaration.Import anImport -> "(import " + anImport.module().text()
                    + (anImport.names().isEmpty() ? "" : " {" + String.join(" ",
                    anImport.names().stream().map(Identifier::name).toList()) + "}")
                    + (anImport.alias() != null ? " as " + anImport.alias().name() : "") + ")";
            case Declaration.Event event -> "(event " + event.name().text() + " " + print(event.body()) + ")";
            case Declaration.Function function -> {
                StringBuilder out = new StringBuilder("(function ").append(function.name().name()).append(' ');
                out.append(parameters(function.parameters()));
                if (function.returnType() != null) {
                    out.append(" : ").append(print(function.returnType()));
                }
                yield out.append(' ').append(print(function.body())).append(')').toString();
            }
            case Declaration.Const constant -> "(const " + constant.name().name()
                    + (constant.type() != null ? ":" + print(constant.type()) : "") + " " + print(constant.value()) + ")";
            case Declaration.Global global -> "(" + switch (global.storage()) {
                case SCRIPT -> "";
                case PERSISTENT -> "persistent ";
                case PLAYERDATA -> "playerdata ";
            } + (global.mutable() ? "var " : "let ") + global.name().name()
                    + (global.type() != null ? ":" + print(global.type()) : "") + " " + print(global.initializer()) + ")";
            case Declaration.Record record -> {
                StringBuilder out = new StringBuilder("(record ").append(record.name().name()).append(" (");
                for (int i = 0; i < record.fields().size(); i++) {
                    Declaration.RecordField field = record.fields().get(i);
                    out.append(i == 0 ? "" : " ").append(field.name().name()).append(':').append(print(field.type()));
                    if (field.defaultValue() != null) {
                        out.append('=').append(print(field.defaultValue()));
                    }
                }
                out.append(')');
                for (Declaration.Function method : record.methods()) {
                    out.append(' ').append(print(method));
                }
                yield out.append(')').toString();
            }
            case Declaration.Command command -> "(command " + command.path().text() + " "
                    + parameters(command.parameters()) + " " + print(command.body()) + ")";
            case Declaration.Lifecycle lifecycle -> "(on " + (lifecycle.load() ? "load " : "unload ")
                    + print(lifecycle.body()) + ")";
            case Declaration.Task task -> task.interval() != null
                    ? "(every " + print(task.interval()) + " " + print(task.body()) + ")"
                    : "(at " + print(task.time()) + " " + print(task.body()) + ")";
            case Declaration.Placeholder placeholder -> "(placeholder " + placeholder.name().name() + " "
                    + print(placeholder.body()) + ")";
        };
    }

    private static String annotations(List<Declaration.Annotation> annotations) {
        StringBuilder out = new StringBuilder();
        for (Declaration.Annotation annotation : annotations) {
            out.append("(@").append(annotation.name().name());
            for (Expression argument : annotation.arguments()) {
                out.append(' ').append(print(argument));
            }
            out.append(") ");
        }
        return out.toString();
    }

    private static String parameters(List<Declaration.Parameter> parameters) {
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < parameters.size(); i++) {
            Declaration.Parameter parameter = parameters.get(i);
            if (i > 0) {
                out.append(' ');
            }
            out.append(parameter.name().name()).append(':').append(print(parameter.type()));
            if (parameter.rest()) {
                out.append("...");
            }
            if (parameter.defaultValue() != null) {
                out.append('=').append(print(parameter.defaultValue()));
            }
        }
        return out.append(')').toString();
    }

    public static String print(Statement statement) {
        return switch (statement) {
            case Statement.Block block -> {
                StringBuilder out = new StringBuilder("{");
                for (Statement inner : block.statements()) {
                    out.append(' ').append(print(inner));
                }
                yield out.append(block.statements().isEmpty() ? "}" : " }").toString();
            }
            case Statement.LocalVariable local -> "(" + (local.mutable() ? "var " : "let ") + local.name().name()
                    + (local.type() != null ? ":" + print(local.type()) : "")
                    + (local.initializer() != null ? " " + print(local.initializer()) : "") + ")";
            case Statement.Assignment assignment -> "(" + assignment.operator().symbol() + " "
                    + print(assignment.target()) + " " + print(assignment.value()) + ")";
            case Statement.ExpressionStatement expression -> print(expression.expression());
            case Statement.If anIf -> "(if " + print(anIf.condition()) + " " + print(anIf.thenBlock())
                    + (anIf.elseBranch() != null ? " else " + print(anIf.elseBranch()) : "") + ")";
            case Statement.While loop -> "(while " + print(loop.condition()) + " " + print(loop.body()) + ")";
            case Statement.For loop -> "(for " + loop.variable().name()
                    + (loop.second() != null ? "," + loop.second().name() : "") + " " + print(loop.iterable()) + " "
                    + print(loop.body()) + ")";
            case Statement.Return ret -> ret.value() == null ? "(return)" : "(return " + print(ret.value()) + ")";
            case Statement.Break ignored -> "(break)";
            case Statement.Continue ignored -> "(continue)";
            case Statement.Switch sw -> {
                StringBuilder out = new StringBuilder("(switch ").append(print(sw.subject()));
                for (Statement.SwitchCase switchCase : sw.cases()) {
                    out.append(" (case");
                    for (Expression label : switchCase.labels()) {
                        out.append(' ').append(print(label));
                    }
                    out.append(" -> ").append(print(switchCase.body())).append(')');
                }
                if (sw.defaultBody() != null) {
                    out.append(" (default -> ").append(print(sw.defaultBody())).append(')');
                }
                yield out.append(')').toString();
            }
            case Statement.Try tryStatement -> "(try " + print(tryStatement.body())
                    + (tryStatement.catchBody() != null ? " (catch" + (tryStatement.catchVariable() != null
                    ? " " + tryStatement.catchVariable().name() : "") + " " + print(tryStatement.catchBody()) + ")" : "")
                    + (tryStatement.finallyBody() != null ? " (finally " + print(tryStatement.finallyBody()) + ")" : "")
                    + ")";
            case Statement.Throw throwStatement -> "(throw " + print(throwStatement.value()) + ")";
            case Statement.Schedule schedule -> "(" + schedule.kind().name().toLowerCase(java.util.Locale.ROOT)
                    + (schedule.delay() != null ? " " + print(schedule.delay()) : "")
                    + (schedule.owner() != null ? " for " + print(schedule.owner()) : "")
                    + " " + print(schedule.body()) + ")";
        };
    }

    public static String print(Expression expression) {
        return switch (expression) {
            case Expression.Literal literal -> switch (literal.kind()) {
                case STRING -> quote((String) literal.value());
                case NULL -> "null";
                case LONG -> literal.value() + "L";
                case FLOAT -> literal.value() + "f";
                default -> String.valueOf(literal.value());
            };
            case Expression.Template template -> {
                StringBuilder out = new StringBuilder("(template ").append(quote(template.segments().getFirst()));
                for (int i = 0; i < template.parts().size(); i++) {
                    out.append(' ').append(print(template.parts().get(i)))
                            .append(' ').append(quote(template.segments().get(i + 1)));
                }
                yield out.append(')').toString();
            }
            case Expression.Duration duration -> "(duration " + print(duration.amount()) + " "
                    + duration.unit().plural() + ")";
            case Expression.Name name -> name.name();
            case Expression.Member member -> "(" + (member.nullSafe() ? "?." : ".") + " " + print(member.target())
                    + " " + member.member().name() + ")";
            case Expression.Call call -> {
                StringBuilder out = new StringBuilder("(call ").append(print(call.callee()));
                for (Expression argument : call.arguments()) {
                    out.append(' ').append(print(argument));
                }
                yield out.append(')').toString();
            }
            case Expression.Index index -> "(index " + print(index.target()) + " " + print(index.index()) + ")";
            case Expression.Unary unary -> "(" + unary.operator().symbol() + " " + print(unary.operand()) + ")";
            case Expression.Binary binary -> "(" + binary.operator().symbol() + " " + print(binary.left()) + " "
                    + print(binary.right()) + ")";
            case Expression.Is is -> "(" + (is.negated() ? "!is " : "is ") + print(is.operand()) + " " + print(is.type()) + ")";
            case Expression.Cast cast -> "(" + (cast.safe() ? "as? " : "as ") + print(cast.operand()) + " "
                    + print(cast.type()) + ")";
            case Expression.Range range -> "(" + (range.inclusive() ? ".. " : "..< ") + print(range.start()) + " "
                    + print(range.end()) + ")";
            case Expression.ListLiteral list -> {
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < list.elements().size(); i++) {
                    out.append(i == 0 ? "" : " ").append(print(list.elements().get(i)));
                }
                yield out.append(']').toString();
            }
            case Expression.MapLiteral map -> {
                StringBuilder out = new StringBuilder("{");
                for (int i = 0; i < map.entries().size(); i++) {
                    Expression.MapEntry entry = map.entries().get(i);
                    out.append(i == 0 ? "" : " ").append(print(entry.key())).append(':').append(print(entry.value()));
                }
                yield out.append('}').toString();
            }
            case Expression.Lambda lambda -> {
                StringBuilder out = new StringBuilder("(lambda (");
                for (int i = 0; i < lambda.parameters().size(); i++) {
                    Expression.LambdaParameter parameter = lambda.parameters().get(i);
                    out.append(i == 0 ? "" : " ").append(parameter.name().name());
                    if (parameter.type() != null) {
                        out.append(':').append(print(parameter.type()));
                    }
                }
                out.append(") ");
                out.append(lambda.expressionBody() != null ? print(lambda.expressionBody()) : print(lambda.blockBody()));
                yield out.append(')').toString();
            }
            case Expression.Conditional conditional -> "(? " + print(conditional.condition()) + " "
                    + print(conditional.whenTrue()) + " " + print(conditional.whenFalse()) + ")";
            case Expression.Switch sw -> {
                StringBuilder out = new StringBuilder("(switch-expr ").append(print(sw.subject()));
                for (Expression.SwitchArm arm : sw.arms()) {
                    out.append(" (case");
                    for (Expression label : arm.labels()) {
                        out.append(' ').append(print(label));
                    }
                    out.append(" -> ").append(print(arm.value())).append(')');
                }
                if (sw.defaultValue() != null) {
                    out.append(" (default -> ").append(print(sw.defaultValue())).append(')');
                }
                yield out.append(')').toString();
            }
            case Expression.Parenthesized parenthesized -> "(paren " + print(parenthesized.inner()) + ")";
            case Expression.Error ignored -> "<error>";
        };
    }

    public static String print(TypeRef type) {
        return switch (type) {
            case TypeRef.Named named -> {
                if (named.arguments().isEmpty()) {
                    yield named.name().text();
                }
                yield named.name().text() + "<" + String.join(",", named.arguments().stream()
                        .map(SyntaxPrinter::print).toList()) + ">";
            }
            case TypeRef.Nullable nullable -> print(nullable.inner()) + "?";
            case TypeRef.Function function -> "function(" + String.join(",", function.parameters().stream()
                    .map(SyntaxPrinter::print).toList()) + ")" + (function.returnType() != null
                    ? ":" + print(function.returnType()) : "");
        };
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
