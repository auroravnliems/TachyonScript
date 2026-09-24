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
        return switch (declaration) {
            case Declaration.Module module -> "(module " + module.name().text() + ")";
            case Declaration.Import anImport -> "(import " + anImport.module().text()
                    + (anImport.names().isEmpty() ? "" : " {" + String.join(" ",
                    anImport.names().stream().map(Identifier::name).toList()) + "}")
                    + (anImport.alias() != null ? " as " + anImport.alias().name() : "") + ")";
            case Declaration.Event event -> "(event " + event.name().text() + " " + print(event.body()) + ")";
            case Declaration.Function function -> {
                StringBuilder out = new StringBuilder("(function ").append(function.name().name()).append(" (");
                List<Declaration.Parameter> parameters = function.parameters();
                for (int i = 0; i < parameters.size(); i++) {
                    if (i > 0) {
                        out.append(' ');
                    }
                    out.append(parameters.get(i).name().name()).append(':').append(print(parameters.get(i).type()));
                }
                out.append(')');
                if (function.returnType() != null) {
                    out.append(" : ").append(print(function.returnType()));
                }
                yield out.append(' ').append(print(function.body())).append(')').toString();
            }
            case Declaration.Const constant -> "(const " + constant.name().name()
                    + (constant.type() != null ? ":" + print(constant.type()) : "") + " " + print(constant.value()) + ")";
        };
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
            case Statement.For loop -> "(for " + loop.variable().name() + " " + print(loop.iterable()) + " "
                    + print(loop.body()) + ")";
            case Statement.Return ret -> ret.value() == null ? "(return)" : "(return " + print(ret.value()) + ")";
            case Statement.Break ignored -> "(break)";
            case Statement.Continue ignored -> "(continue)";
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
            case Expression.Is is -> "(is " + print(is.operand()) + " " + print(is.type()) + ")";
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
        };
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
