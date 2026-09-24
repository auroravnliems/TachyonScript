package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.Parameter;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.doc.Deprecation;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.NullType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.source.Span;
import dev.tachyonscript.language.syntax.AssignmentOperator;
import dev.tachyonscript.language.syntax.BinaryOperator;
import dev.tachyonscript.language.syntax.Expression;
import dev.tachyonscript.language.syntax.Identifier;
import dev.tachyonscript.language.syntax.Statement;
import dev.tachyonscript.language.syntax.UnaryOperator;
import dev.tachyonscript.language.util.Suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Binds and type-checks the body of one function, event handler or constant initializer.
 *
 * <p>Expressions are checked bidirectionally: an expected type flows into literals,
 * templates and list literals so that, for example, a string template passed where a
 * {@code Component} is expected becomes a pre-compiled message template instead of a
 * concatenation followed by a MiniMessage parse. Narrowing facts from null checks and
 * {@code is} tests are tracked in an immutable {@link Flow} that is forked at branches.
 */
final class BodyBinder {

    /** Resolves constants on demand (implemented by the module-level binder). */
    interface ConstantResolver {
        BoundExpression constant(ConstantSymbol constant, Span use);
    }

    private static final Set<String> CANCEL_MEMBERS = Set.of("cancel", "uncancel", "cancelled", "isCancelled", "setCancelled");

    private final ModuleContext module;
    private final ConstantResolver constants;
    private final Type returnType;
    private final EventDeclaration event;
    private final String owner;
    private Scope scope;
    private Flow flow = Flow.EMPTY;
    private int loopDepth;

    BodyBinder(ModuleContext module, ConstantResolver constants, Scope root, Type returnType, EventDeclaration event,
               String owner) {
        this.module = module;
        this.constants = constants;
        this.scope = root;
        this.returnType = returnType;
        this.event = event;
        this.owner = owner;
    }

    // =================================================================== statements

    BoundStatement.Block bindBlock(Statement.Block block, boolean newScope) {
        if (newScope) {
            scope = new Scope(scope);
        }
        List<BoundStatement> statements = new ArrayList<>();
        boolean reachable = true;
        boolean warned = false;
        for (Statement statement : block.statements()) {
            if (!reachable && !warned) {
                module.report(module.diagnostic(DiagnosticCode.UNREACHABLE_CODE, statement.span(), "Unreachable code.")
                        .note("The statement before it always returns, breaks or continues.").build());
                warned = true;
            }
            BoundStatement bound = bindStatement(statement);
            if (reachable) {
                statements.add(bound);
                reachable = Reachability.completesNormally(bound);
            }
        }
        if (newScope) {
            reportUnused(scope);
            scope = scope.parent();
        }
        return new BoundStatement.Block(statements, block.span());
    }

    private void reportUnused(Scope ending) {
        for (LocalSymbol local : ending.locals()) {
            if (local.kind() == LocalSymbol.Kind.VARIABLE && !local.isRead() && !local.name().startsWith("_")) {
                module.report(module.diagnostic(DiagnosticCode.UNUSED_VARIABLE, local.declaration(),
                        "Variable '" + local.name() + "' is never used.").build());
            }
        }
    }

    private BoundStatement bindStatement(Statement statement) {
        return switch (statement) {
            case Statement.Block block -> bindBlock(block, true);
            case Statement.LocalVariable local -> bindLocal(local);
            case Statement.Assignment assignment -> bindAssignment(assignment);
            case Statement.ExpressionStatement expression -> bindExpressionStatement(expression);
            case Statement.If anIf -> bindIf(anIf);
            case Statement.While loop -> bindWhile(loop);
            case Statement.For loop -> bindFor(loop);
            case Statement.Return ret -> bindReturn(ret);
            case Statement.Break brk -> {
                checkInLoop("break", brk.span());
                yield new BoundStatement.Break(brk.span());
            }
            case Statement.Continue cont -> {
                checkInLoop("continue", cont.span());
                yield new BoundStatement.Continue(cont.span());
            }
        };
    }

    private void checkInLoop(String keyword, Span span) {
        if (loopDepth == 0) {
            module.error(DiagnosticCode.JUMP_OUTSIDE_LOOP, span, "'" + keyword + "' can only be used inside a loop.");
        }
    }

    private BoundStatement bindLocal(Statement.LocalVariable declaration) {
        String name = declaration.name().name();
        Type declared = declaration.type() != null ? module.types().resolve(declaration.type(), false) : null;
        BoundExpression initializer;
        Type type;
        if (declaration.initializer() == null) {
            module.report(module.diagnostic(DiagnosticCode.MISSING_INITIALIZER, declaration.span(),
                            "Variable '" + name + "' must be initialized.")
                    .note("Example: " + (declaration.mutable() ? "var " : "let ") + name
                            + (declaration.type() == null ? ": int" : "") + " = 0").build());
            initializer = new BoundExpression.Error(declaration.span());
            type = declared != null ? declared : Types.ERROR;
        } else if (declared != null) {
            initializer = convert(bindValue(declaration.initializer(), declared), declared,
                    declaration.initializer().span(), "variable '" + name + "'");
            type = declared;
        } else {
            initializer = bindValue(declaration.initializer(), null);
            type = initializer.type();
            if (type instanceof NullType) {
                module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, declaration.initializer().span(),
                                "Cannot infer the type of '" + name + "' from 'null'.")
                        .note("Declare the type explicitly, e.g. let " + name + ": Player? = null").build());
                type = Types.ERROR;
            }
        }
        LocalSymbol existing = scope.lookupHere(name);
        if (existing != null) {
            module.report(module.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, declaration.name().span(),
                            "'" + name + "' is already declared in this scope.")
                    .label(existing.declaration(), "first declared here").build());
        } else {
            LocalSymbol outer = scope.lookup(name);
            if (outer != null) {
                module.report(module.diagnostic(DiagnosticCode.SHADOWED_VARIABLE, declaration.name().span(),
                                "'" + name + "' shadows " + describeKind(outer) + " with the same name.")
                        .label(outer.declaration(), "declared here").build());
            }
        }
        LocalSymbol local = new LocalSymbol(name, type, declaration.mutable(), LocalSymbol.Kind.VARIABLE,
                declaration.name().span(), null);
        if (!declaration.mutable() && initializer instanceof BoundExpression.Literal literal
                && literal.value() instanceof String text) {
            local.knownString(text);
        }
        scope.declare(local);
        narrowAfterAssignment(local, initializer);
        return new BoundStatement.LocalDeclaration(local, initializer, declaration.span());
    }

    private void narrowAfterAssignment(LocalSymbol local, BoundExpression value) {
        flow = flow.without(local);
        Type valueType = value.type();
        if (local.type().isNullable() && !valueType.isNullable() && !valueType.isError()) {
            flow = flow.with(local, local.type().nonNullable());
        }
    }

    private BoundStatement bindAssignment(Statement.Assignment assignment) {
        Expression target = unwrap(assignment.target());
        AssignmentOperator operator = assignment.operator();
        Span span = assignment.span();
        return switch (target) {
            case Expression.Name name -> assignLocal(name, operator, assignment.value(), span);
            case Expression.Member member -> assignMember(member, operator, assignment.value(), span);
            case Expression.Index index -> assignIndex(index, operator, assignment.value(), span);
            default -> {
                bindValue(assignment.value(), null);
                module.error(DiagnosticCode.INVALID_ASSIGNMENT_TARGET, target.span(), "Cannot assign to this expression.");
                yield new BoundStatement.ExpressionStatement(new BoundExpression.Error(span), span);
            }
        };
    }

    private BoundStatement assignLocal(Expression.Name name, AssignmentOperator operator, Expression valueSyntax, Span span) {
        LocalSymbol local = scope.lookup(name.name());
        if (local == null) {
            bindValue(valueSyntax, null);
            if (module.constants().containsKey(name.name())) {
                module.error(DiagnosticCode.ASSIGN_TO_READONLY, name.span(), "Cannot assign to constant '" + name.name() + "'.");
            } else {
                reportUnknownName(name.identifier());
            }
            return new BoundStatement.ExpressionStatement(new BoundExpression.Error(span), span);
        }
        if (!local.isMutable()) {
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.ASSIGN_TO_READONLY, name.span(),
                    "Cannot assign to " + describeKind(local) + " '" + local.name() + "'.");
            if (local.kind() == LocalSymbol.Kind.VARIABLE) {
                builder.note("It is declared with 'let'. Use 'var' for a variable that can change.")
                        .label(local.declaration(), "declared here");
            } else if (local.kind() == LocalSymbol.Kind.PARAMETER) {
                builder.note("Parameters are read-only. Copy the value into a variable: var copy = " + local.name());
            }
            module.report(builder.build());
        }
        BoundExpression value;
        if (operator.isCompound()) {
            BoundExpression current = loadLocal(local, name.span());
            BoundExpression right = bindValue(valueSyntax, current.type());
            value = binaryOperation(operator.binary(), current, right, span);
        } else {
            value = bindValue(valueSyntax, local.type());
        }
        value = convert(value, local.type(), valueSyntax.span(), describeKind(local) + " '" + local.name() + "'");
        narrowAfterAssignment(local, value);
        return new BoundStatement.LocalAssignment(local, value, span);
    }

    private BoundStatement assignMember(Expression.Member member, AssignmentOperator operator, Expression valueSyntax,
                                        Span span) {
        PathResult target = bindTarget(member.target());
        String name = member.member().name();
        if (target instanceof PathResult.Namespace namespace) {
            String qualified = namespace.name() + "." + name;
            PropertyDeclaration property = module.registry().globalProperty(qualified).orElse(null);
            if (property == null) {
                bindValue(valueSyntax, null);
                reportUnknownNamespaceMember(namespace.name(), member.member());
                return errorStatement(span);
            }
            return assignProperty(null, property, qualified, operator, valueSyntax, member.member().span(), span);
        }
        BoundExpression receiver = expectValue(target, member.target());
        if (receiver.type().isError()) {
            bindValue(valueSyntax, null);
            return errorStatement(span);
        }
        if (receiver.type().isNullable()) {
            bindValue(valueSyntax, null);
            reportNullableAccess(receiver, member.target(), name);
            return errorStatement(span);
        }
        if (receiver.type() instanceof ClassType type) {
            MemberLookup.Result result = module.members().lookup(type, name);
            if (result.property() != null) {
                return assignProperty(receiver, result.property(), type.name() + "." + name, operator, valueSyntax,
                        member.member().span(), span);
            }
            bindValue(valueSyntax, null);
            if (!result.methods().isEmpty()) {
                module.error(DiagnosticCode.ASSIGN_TO_READONLY, member.member().span(),
                        "Cannot assign to method '" + name + "' of " + type.name() + ".");
            } else {
                reportUnknownMember(type, member.member(), receiver);
            }
            return errorStatement(span);
        }
        bindValue(valueSyntax, null);
        module.error(DiagnosticCode.ASSIGN_TO_READONLY, member.member().span(),
                "Cannot assign to '" + name + "' of a value of type " + receiver.type().displayName() + ".");
        return errorStatement(span);
    }

    private BoundStatement assignProperty(BoundExpression receiver, PropertyDeclaration property, String display,
                                          AssignmentOperator operator, Expression valueSyntax, Span nameSpan, Span span) {
        if (property.setter().isEmpty()) {
            bindValue(valueSyntax, null);
            module.error(DiagnosticCode.ASSIGN_TO_READONLY, nameSpan, "Property '" + display + "' is read-only.");
            return errorStatement(span);
        }
        warnDeprecated(property.deprecation().orElse(null), display, nameSpan);
        Type type = property.type();
        List<BoundStatement> prefix = new ArrayList<>();
        BoundExpression value;
        if (operator.isCompound()) {
            if (receiver != null && !(receiver instanceof BoundExpression.LocalLoad)) {
                LocalSymbol temporary = module.newTemporary(receiver.type(), receiver.span());
                prefix.add(new BoundStatement.LocalDeclaration(temporary, receiver, receiver.span()));
                receiver = new BoundExpression.LocalLoad(temporary, receiver.type(), receiver.span());
            }
            List<BoundExpression> getterArguments = receiver == null ? List.of() : List.of(receiver);
            BoundExpression current = new BoundExpression.NativeCall(property.getter(), getterArguments, type, nameSpan);
            BoundExpression right = bindValue(valueSyntax, type);
            value = binaryOperation(operator.binary(), current, right, span);
        } else {
            value = bindValue(valueSyntax, type);
        }
        value = convert(value, type, valueSyntax.span(), "property '" + display + "'");
        BoundStatement assignment = new BoundStatement.PropertyAssignment(receiver, property.setter().orElseThrow(), value, span);
        if (prefix.isEmpty()) {
            return assignment;
        }
        prefix.add(assignment);
        return new BoundStatement.Block(prefix, span);
    }

    private BoundStatement assignIndex(Expression.Index index, AssignmentOperator operator, Expression valueSyntax, Span span) {
        BoundExpression list = bindValue(index.target(), null);
        if (list.type().isError()) {
            bindValue(valueSyntax, null);
            return errorStatement(span);
        }
        if (!(list.type() instanceof ListType listType)) {
            bindValue(valueSyntax, null);
            if (list.type().isNullable()) {
                reportNullableAccess(list, index.target(), "[...]");
            } else {
                module.error(DiagnosticCode.INVALID_OPERATOR, index.span(),
                        "Cannot index a value of type " + list.type().displayName() + ".");
            }
            return errorStatement(span);
        }
        BoundExpression position = convert(bindValue(index.index(), PrimitiveType.INT), PrimitiveType.INT,
                index.index().span(), "list index");
        List<BoundStatement> prefix = new ArrayList<>();
        BoundExpression value;
        if (operator.isCompound()) {
            list = hoist(list, prefix);
            position = hoist(position, prefix);
            BoundExpression current = new BoundExpression.ListGet(list, position, listType.element(), index.span());
            value = binaryOperation(operator.binary(), current, bindValue(valueSyntax, listType.element()), span);
        } else {
            value = bindValue(valueSyntax, listType.element());
        }
        value = convert(value, listType.element(), valueSyntax.span(), "list element");
        BoundStatement set = new BoundStatement.ListSet(list, position, value, span);
        if (prefix.isEmpty()) {
            return set;
        }
        prefix.add(set);
        return new BoundStatement.Block(prefix, span);
    }

    /** Stores {@code expression} in a temporary (unless trivially re-evaluable) so it is evaluated once. */
    private BoundExpression hoist(BoundExpression expression, List<BoundStatement> prefix) {
        if (expression instanceof BoundExpression.LocalLoad || expression instanceof BoundExpression.Literal) {
            return expression;
        }
        LocalSymbol temporary = module.newTemporary(expression.type(), expression.span());
        prefix.add(new BoundStatement.LocalDeclaration(temporary, expression, expression.span()));
        return new BoundExpression.LocalLoad(temporary, expression.type(), expression.span());
    }

    private BoundStatement bindExpressionStatement(Statement.ExpressionStatement statement) {
        BoundExpression expression = bind(statement.expression(), null);
        if (!hasEffect(expression)) {
            module.report(module.diagnostic(DiagnosticCode.UNUSED_EXPRESSION, statement.span(),
                    "The result of this expression is not used.").build());
        }
        return new BoundStatement.ExpressionStatement(expression, statement.span());
    }

    private static boolean hasEffect(BoundExpression expression) {
        return switch (expression) {
            case BoundExpression.NativeCall call -> switch (call.target().kind()) {
                case FUNCTION, METHOD, SETTER -> true;
                default -> false;
            };
            case BoundExpression.FunctionCall ignored -> true;
            case BoundExpression.ListAdd ignored -> true;
            case BoundExpression.SafeAccess access -> hasEffect(access.access());
            case BoundExpression.Conversion conversion -> hasEffect(conversion.operand());
            case BoundExpression.Error ignored -> true;
            default -> false;
        };
    }

    private BoundStatement bindIf(Statement.If statement) {
        Condition condition = bindCondition(statement.condition());
        Flow before = flow;
        flow = before.with(condition.facts().whenTrue());
        BoundStatement thenBranch = bindBlock(statement.thenBlock(), true);
        Flow afterThen = flow;
        boolean thenCompletes = Reachability.completesNormally(thenBranch);
        BoundStatement elseBranch = null;
        Flow afterElse;
        boolean elseCompletes = true;
        flow = before.with(condition.facts().whenFalse());
        if (statement.elseBranch() != null) {
            elseBranch = bindStatement(statement.elseBranch());
            elseCompletes = Reachability.completesNormally(elseBranch);
        }
        afterElse = flow;
        if (thenCompletes && elseCompletes) {
            flow = afterThen.intersect(afterElse);
        } else if (thenCompletes) {
            flow = afterThen;
        } else if (elseCompletes) {
            flow = afterElse;
        } else {
            flow = before;
        }
        return new BoundStatement.If(condition.expression(), thenBranch, elseBranch, statement.span());
    }

    private BoundStatement bindWhile(Statement.While statement) {
        flow = flow.withoutNames(assignedNames(statement.body()));
        Condition condition = bindCondition(statement.condition());
        Flow before = flow;
        flow = before.with(condition.facts().whenTrue());
        loopDepth++;
        BoundStatement body = bindBlock(statement.body(), true);
        loopDepth--;
        flow = Reachability.containsBreak(body) ? before : before.with(condition.facts().whenFalse());
        return new BoundStatement.While(condition.expression(), body, statement.span());
    }

    private BoundStatement bindFor(Statement.For statement) {
        flow = flow.withoutNames(assignedNames(statement.body()));
        Flow before = flow;
        Expression iterable = unwrap(statement.iterable());
        scope = new Scope(scope);
        try {
            if (iterable instanceof Expression.Range range) {
                BoundExpression start = bindValue(range.start(), null);
                BoundExpression end = bindValue(range.end(), start.type());
                Type kind = rangeKind(start, end, range);
                if (!kind.isError()) {
                    start = convert(start, kind, range.start().span(), "range start");
                    end = convert(end, kind, range.end().span(), "range end");
                }
                LocalSymbol variable = declareLoopVariable(statement.variable(), kind);
                loopDepth++;
                BoundStatement body = bindBlock(statement.body(), false);
                loopDepth--;
                return new BoundStatement.ForRange(variable, start, end, range.inclusive(),
                        kind.isError() ? Representation.INT : kind.representation(), body, statement.span());
            }
            BoundExpression collection = bindValue(iterable, null);
            Type elementType = Types.ERROR;
            if (collection.type() instanceof ListType list) {
                elementType = list.element();
            } else if (!collection.type().isError()) {
                if (collection.type().isNullable()) {
                    reportNullableAccess(collection, iterable, "for ... in");
                } else {
                    module.report(module.diagnostic(DiagnosticCode.NOT_ITERABLE, iterable.span(),
                                    "Cannot iterate over a value of type " + collection.type().displayName() + ".")
                            .note("'for' loops iterate over lists and ranges, e.g. for i in 1..10 { }").build());
                }
            }
            LocalSymbol variable = declareLoopVariable(statement.variable(), elementType);
            loopDepth++;
            BoundStatement body = bindBlock(statement.body(), false);
            loopDepth--;
            return new BoundStatement.ForEach(variable, collection, body, statement.span());
        } finally {
            reportUnused(scope);
            scope = scope.parent();
            flow = before;
        }
    }

    private Type rangeKind(BoundExpression start, BoundExpression end, Expression.Range range) {
        Type a = start.type();
        Type b = end.type();
        if (a.isError() || b.isError()) {
            return Types.ERROR;
        }
        boolean aIntegral = a == PrimitiveType.INT || a == PrimitiveType.LONG;
        boolean bIntegral = b == PrimitiveType.INT || b == PrimitiveType.LONG;
        if (!aIntegral || !bIntegral) {
            module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, range.span(),
                            "Range bounds must be int or long.")
                    .expectedReceived("int or long", a.displayName() + ".." + b.displayName()).build());
            return Types.ERROR;
        }
        return a == PrimitiveType.LONG || b == PrimitiveType.LONG ? PrimitiveType.LONG : PrimitiveType.INT;
    }

    private LocalSymbol declareLoopVariable(Identifier name, Type type) {
        LocalSymbol outer = scope.lookup(name.name());
        if (outer != null) {
            module.report(module.diagnostic(DiagnosticCode.SHADOWED_VARIABLE, name.span(),
                            "'" + name.name() + "' shadows " + describeKind(outer) + " with the same name.")
                    .label(outer.declaration(), "declared here").build());
        }
        LocalSymbol variable = new LocalSymbol(name.name(), type, false, LocalSymbol.Kind.LOOP_VARIABLE, name.span(), null);
        scope.declare(variable);
        return variable;
    }

    /** Names assigned anywhere in {@code statement} (conservative input for loop flow). */
    private static Set<String> assignedNames(Statement statement) {
        Set<String> names = new HashSet<>();
        collectAssigned(statement, names);
        return names;
    }

    private static void collectAssigned(Statement statement, Set<String> names) {
        switch (statement) {
            case Statement.Block block -> block.statements().forEach(inner -> collectAssigned(inner, names));
            case Statement.Assignment assignment -> {
                if (unwrap(assignment.target()) instanceof Expression.Name name) {
                    names.add(name.name());
                }
            }
            case Statement.If anIf -> {
                collectAssigned(anIf.thenBlock(), names);
                if (anIf.elseBranch() != null) {
                    collectAssigned(anIf.elseBranch(), names);
                }
            }
            case Statement.While loop -> collectAssigned(loop.body(), names);
            case Statement.For loop -> collectAssigned(loop.body(), names);
            default -> {
            }
        }
    }

    private BoundStatement bindReturn(Statement.Return statement) {
        Span span = statement.span();
        if (returnType == PrimitiveType.VOID) {
            if (statement.value() == null) {
                return new BoundStatement.Return(null, span);
            }
            BoundExpression value = bind(statement.value(), null);
            if (value.type() == PrimitiveType.VOID || value.type().isError()) {
                return new BoundStatement.Block(List.of(new BoundStatement.ExpressionStatement(value, value.span()),
                        new BoundStatement.Return(null, span)), span);
            }
            module.report(module.diagnostic(DiagnosticCode.UNEXPECTED_RETURN_VALUE, statement.value().span(),
                            event != null ? "Event handlers cannot return a value."
                                    : capitalize(owner) + " has no return type, so it cannot return a value.")
                    .note(event != null ? "Use a bare 'return' to stop the handler."
                            : "Declare a return type, e.g. function f(): int { ... }").build());
            return new BoundStatement.Return(null, span);
        }
        if (statement.value() == null) {
            module.report(module.diagnostic(DiagnosticCode.MISSING_RETURN_VALUE, span,
                    capitalize(owner) + " must return a value of type " + returnType.displayName() + ".").build());
            return new BoundStatement.Return(new BoundExpression.Error(span), span);
        }
        BoundExpression value = convert(bindValue(statement.value(), returnType), returnType, statement.value().span(),
                "the return value of " + owner);
        return new BoundStatement.Return(value, span);
    }

    // =================================================================== conditions

    /** A bound condition and the narrowing facts it implies. */
    private record Condition(BoundExpression expression, Facts facts) {
    }

    private Condition bindCondition(Expression syntax) {
        Expression expression = unwrap(syntax);
        if (expression instanceof Expression.Unary unary && unary.operator() == UnaryOperator.NOT) {
            Condition operand = bindCondition(unary.operand());
            return new Condition(new BoundExpression.Not(operand.expression(), unary.span()), operand.facts().negate());
        }
        if (expression instanceof Expression.Binary binary && binary.operator().isLogical()) {
            boolean and = binary.operator() == BinaryOperator.AND;
            Condition left = bindCondition(binary.left());
            Flow saved = flow;
            flow = flow.with(and ? left.facts().whenTrue() : left.facts().whenFalse());
            Condition right = bindCondition(binary.right());
            flow = saved;
            Facts facts = and
                    ? new Facts(Facts.union(left.facts().whenTrue(), right.facts().whenTrue()), java.util.Map.of())
                    : new Facts(java.util.Map.of(), Facts.union(left.facts().whenFalse(), right.facts().whenFalse()));
            return new Condition(new BoundExpression.Logical(and, left.expression(), right.expression(), binary.span()), facts);
        }
        BoundExpression bound = convert(bindValue(syntax, PrimitiveType.BOOL), PrimitiveType.BOOL, syntax.span(), "the condition");
        return new Condition(bound, factsOf(bound));
    }

    /** Narrowing facts of a single (non-logical) condition. */
    private Facts factsOf(BoundExpression condition) {
        if (condition instanceof BoundExpression.NullCheck check && check.operand() instanceof BoundExpression.LocalLoad load) {
            Type current = flow.typeOf(load.local());
            if (current.isNullable() && !(current instanceof NullType)) {
                java.util.Map<LocalSymbol, Type> fact = java.util.Map.of(load.local(), current.nonNullable());
                return check.isNull() ? new Facts(java.util.Map.of(), fact) : new Facts(fact, java.util.Map.of());
            }
        }
        if (condition instanceof BoundExpression.TypeTest test && test.operand() instanceof BoundExpression.LocalLoad load) {
            return new Facts(java.util.Map.of(load.local(), test.target()), java.util.Map.of());
        }
        return Facts.NONE;
    }

    // =================================================================== expressions

    /**
     * Text known only while running (a variable, a player's chat message, a concatenation
     * with runtime values) is never parsed as MiniMessage implicitly: players could inject
     * tags. Constant text is formatted; runtime values go into templates as plain text.
     */
    private BoundExpression unsafeTextFormatting(BoundExpression expression, Span span) {
        String name = expression instanceof BoundExpression.LocalLoad load ? load.local().name() : "value";
        module.report(module.diagnostic(DiagnosticCode.UNSAFE_TEXT_FORMATTING, span,
                        "This text is only known while the script runs, so it is not formatted as MiniMessage "
                                + "automatically: it could contain tags from a player.")
                .expectedReceived(Types.COMPONENT.displayName(), Types.STRING.displayName())
                .note("To show it as plain text, use a template: \"{" + name + "}\" (formatting around it is allowed, "
                        + "e.g. \"<gray>{" + name + "}\").")
                .note("If the text is trusted MiniMessage, format it explicitly: text.mini(" + name + ")").build());
        return new BoundExpression.Error(span);
    }

    /** Binds an expression used as a value; {@code void} results are reported. */
    BoundExpression bindValue(Expression syntax, Type expected) {
        BoundExpression expression = bind(syntax, expected);
        if (expression.type() == PrimitiveType.VOID) {
            module.error(DiagnosticCode.VOID_VALUE, syntax.span(), "This expression does not produce a value.");
            return new BoundExpression.Error(syntax.span());
        }
        return expression;
    }

    /** Converts {@code expression} to {@code target} or reports a type mismatch. */
    BoundExpression convert(BoundExpression expression, Type target, Span span, String what) {
        Type from = expression.type();
        if (Conversions.cost(from, target) != Conversions.NONE) {
            if (from.nonNullable() == Types.STRING && target.nonNullable() == Types.COMPONENT
                    && !(expression instanceof BoundExpression.Literal)) {
                return unsafeTextFormatting(expression, span);
            }
            return Conversions.apply(expression, target);
        }
        if (from.isNullable() && !(from instanceof NullType) && Conversions.cost(from.nonNullable(), target) != Conversions.NONE) {
            String name = describeValue(expression);
            module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, span,
                            name + " may be null, but " + what + " requires " + target.displayName() + ".")
                    .expectedReceived(target.displayName(), from.displayName())
                    .note("Check for null first (if " + name + " != null { ... }), or give a default with '??'.").build());
        } else {
            module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, span, "Type mismatch for " + what + ".")
                    .expectedReceived(target.displayName(), from.displayName()).build());
        }
        return new BoundExpression.Error(expression.span());
    }

    BoundExpression bind(Expression syntax, Type expected) {
        return switch (syntax) {
            case Expression.Literal literal -> bindLiteral(literal, expected, false, literal.span());
            case Expression.Template template -> bindTemplate(template, expected);
            case Expression.Duration duration -> bindDuration(duration);
            case Expression.Name name -> expectValue(resolveName(name.identifier()), name);
            case Expression.Member member -> expectValue(bindTarget(member), member);
            case Expression.Call call -> bindCall(call);
            case Expression.Index index -> bindIndex(index);
            case Expression.Unary unary -> bindUnary(unary, expected);
            case Expression.Binary binary -> bindBinary(binary, expected);
            case Expression.Is is -> bindIs(is);
            case Expression.Cast cast -> bindCast(cast);
            case Expression.Range range -> {
                module.report(module.diagnostic(DiagnosticCode.UNSUPPORTED_FEATURE, range.span(),
                        "Ranges can only be used in 'for' loops.").note("Example: for i in 1..10 { }").build());
                yield new BoundExpression.Error(range.span());
            }
            case Expression.ListLiteral list -> bindList(list, expected);
            case Expression.Parenthesized parenthesized -> bind(parenthesized.inner(), expected);
            case Expression.Error error -> new BoundExpression.Error(error.span());
        };
    }

    // ------------------------------------------------------------------- literals

    private BoundExpression bindLiteral(Expression.Literal literal, Type expected, boolean negate, Span span) {
        Type target = expected == null ? null : expected.nonNullable();
        return switch (literal.kind()) {
            case INT -> {
                long magnitude = (Long) literal.value();
                if (target == PrimitiveType.LONG || target == PrimitiveType.FLOAT || target == PrimitiveType.DOUBLE) {
                    yield integerLiteral(magnitude, negate, (PrimitiveType) target, span);
                }
                yield integerLiteral(magnitude, negate, PrimitiveType.INT, span);
            }
            case LONG -> integerLiteral((Long) literal.value(), negate, PrimitiveType.LONG, span);
            case FLOAT -> new BoundExpression.Literal(negate ? -(Float) literal.value() : (Float) literal.value(),
                    PrimitiveType.FLOAT, span);
            case DOUBLE -> new BoundExpression.Literal(negate ? -(Double) literal.value() : (Double) literal.value(),
                    PrimitiveType.DOUBLE, span);
            case BOOL -> new BoundExpression.Literal(literal.value(), PrimitiveType.BOOL, span);
            case STRING -> target == Types.COMPONENT
                    ? new BoundExpression.ComponentTemplate(List.of((String) literal.value()), List.of(), span)
                    : new BoundExpression.Literal(literal.value(), Types.STRING, span);
            case NULL -> new BoundExpression.Literal(null,
                    expected != null && expected.isNullable() ? expected : Types.NULL, span);
        };
    }

    private BoundExpression integerLiteral(long magnitude, boolean negate, PrimitiveType type, Span span) {
        // 'magnitude' is unsigned: Long.MIN_VALUE stands for 2^63, valid only when negated.
        boolean fits = switch (type) {
            case INT -> negate ? Long.compareUnsigned(magnitude, 2_147_483_648L) <= 0 : magnitude <= Integer.MAX_VALUE;
            case LONG -> negate || magnitude != Long.MIN_VALUE;
            default -> true;
        };
        if (!fits) {
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.LITERAL_OUT_OF_RANGE, span,
                    "Integer literal " + (negate ? "-" : "") + Long.toUnsignedString(magnitude) + " is out of range for "
                            + type.displayName() + ".");
            if (type == PrimitiveType.INT) {
                builder.note("Use a long literal instead: " + (negate ? "-" : "") + Long.toUnsignedString(magnitude) + "L");
            }
            module.report(builder.build());
            return new BoundExpression.Error(span);
        }
        long signed = negate ? -magnitude : magnitude;
        // The unsigned magnitude 2^63 is exact only through BigInteger for floating targets.
        double floating = new java.math.BigInteger(Long.toUnsignedString(magnitude)).doubleValue() * (negate ? -1 : 1);
        Object value = switch (type) {
            case INT -> (int) signed;
            case LONG -> signed;
            case FLOAT -> (float) floating;
            default -> floating;
        };
        return new BoundExpression.Literal(value, type, span);
    }

    private BoundExpression bindDuration(Expression.Duration duration) {
        Expression.Literal amount = duration.amount();
        long unit = duration.unit().millis();
        try {
            long millis = switch (amount.kind()) {
                case INT, LONG -> Math.multiplyExact((Long) amount.value(), unit);
                case FLOAT, DOUBLE -> {
                    double value = ((Number) amount.value()).doubleValue() * unit;
                    if (!Double.isFinite(value) || Math.abs(value) > Long.MAX_VALUE) {
                        throw new ArithmeticException();
                    }
                    yield Math.round(value);
                }
                default -> throw new IllegalStateException("Duration amount must be numeric");
            };
            if (millis < 0) {
                throw new ArithmeticException();
            }
            return new BoundExpression.Literal(millis, PrimitiveType.DURATION, duration.span());
        } catch (ArithmeticException overflow) {
            module.error(DiagnosticCode.LITERAL_OUT_OF_RANGE, duration.span(), "Duration is too long.");
            return new BoundExpression.Error(duration.span());
        }
    }

    // ------------------------------------------------------------------- templates

    private BoundExpression bindTemplate(Expression.Template template, Type expected) {
        List<BoundExpression> parts = new ArrayList<>();
        boolean hasComponent = false;
        for (Expression part : template.parts()) {
            BoundExpression bound = bindValue(part, null);
            hasComponent |= bound.type() == Types.COMPONENT;
            parts.add(bound);
        }
        boolean wantComponent = expected != null && expected.nonNullable() == Types.COMPONENT;
        if (wantComponent || (hasComponent && expected == null)) {
            return componentTemplate(template, parts);
        }
        List<BoundExpression> pieces = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            addText(pieces, template.segments().get(i), template.span());
            BoundExpression part = parts.get(i);
            if (part.type() == Types.COMPONENT) {
                module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, part.span(),
                                "A Component cannot be inserted into a string.")
                        .note("Use text.plain(value) to get its plain text, or build a Component message instead.").build());
                pieces.add(new BoundExpression.Error(part.span()));
            } else {
                pieces.add(toText(part));
            }
        }
        addText(pieces, template.segments().getLast(), template.span());
        return concat(pieces, template.span());
    }

    private BoundExpression componentTemplate(Expression.Template template, List<BoundExpression> parts) {
        List<String> segments = new ArrayList<>();
        List<BoundExpression> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder(template.segments().getFirst());
        for (int i = 0; i < parts.size(); i++) {
            BoundExpression part = parts.get(i);
            Object constant = part.type() == Types.COMPONENT ? ConstantEvaluator.NOT_CONSTANT
                    : ConstantEvaluator.evaluate(part, ConstantEvaluator.SILENT);
            if (constant != ConstantEvaluator.NOT_CONSTANT && !part.type().isError()) {
                // Compile-time constants become part of the MiniMessage text, so their tags format.
                current.append(ConstantEvaluator.toText(constant, part.type() == PrimitiveType.DURATION));
            } else {
                hintUnformattedTags(part);
                segments.add(current.toString());
                current.setLength(0);
                arguments.add(part.type() == Types.COMPONENT ? part : toText(part));
            }
            current.append(template.segments().get(i + 1));
        }
        segments.add(current.toString());
        return new BoundExpression.ComponentTemplate(segments, arguments, template.span());
    }

    private void hintUnformattedTags(BoundExpression part) {
        if (part instanceof BoundExpression.LocalLoad load && load.local().knownString() != null
                && load.local().knownString().contains("<")) {
            module.report(module.diagnostic(DiagnosticCode.INTERPOLATION_NOT_FORMATTED, part.span(),
                            "Tags in '" + load.local().name() + "' are shown as text, not formatted.")
                    .note("Interpolated values are inserted as plain text. Declare it with 'const' to make its "
                            + "MiniMessage tags part of the message.").build());
        }
    }

    private static void addText(List<BoundExpression> pieces, String text, Span span) {
        if (!text.isEmpty()) {
            pieces.add(new BoundExpression.Literal(text, Types.STRING, span));
        }
    }

    /** Joins string pieces, folding adjacent constants and flattening nested concatenations. */
    private BoundExpression concat(List<BoundExpression> pieces, Span span) {
        List<BoundExpression> flat = new ArrayList<>();
        for (BoundExpression piece : pieces) {
            List<BoundExpression> inner = piece instanceof BoundExpression.Concat nested ? nested.parts() : List.of(piece);
            for (BoundExpression part : inner) {
                Object constant = ConstantEvaluator.evaluate(part, ConstantEvaluator.SILENT);
                if (constant instanceof String text && !flat.isEmpty()
                        && flat.getLast() instanceof BoundExpression.Literal last && last.value() instanceof String previous) {
                    flat.set(flat.size() - 1, new BoundExpression.Literal(previous + text, Types.STRING, last.span().to(part.span())));
                } else if (constant instanceof String text) {
                    flat.add(new BoundExpression.Literal(text, Types.STRING, part.span()));
                } else {
                    flat.add(part);
                }
            }
        }
        if (flat.isEmpty()) {
            return new BoundExpression.Literal("", Types.STRING, span);
        }
        if (flat.size() == 1 && flat.getFirst().type() == Types.STRING) {
            return flat.getFirst();
        }
        return new BoundExpression.Concat(flat, span);
    }

    /** Converts a value to its canonical text form ({@code toString()} member if the type declares one). */
    private BoundExpression toText(BoundExpression value) {
        Type type = value.type();
        if (type == Types.STRING || type.isError()) {
            return value;
        }
        if (type instanceof NullType) {
            return new BoundExpression.Literal("null", Types.STRING, value.span());
        }
        if (type.nonNullable() instanceof ClassType classType) {
            FunctionDeclaration stringifier = stringifier(classType);
            if (stringifier != null) {
                if (!type.isNullable()) {
                    return new BoundExpression.NativeCall(stringifier.invocable(), List.of(value), Types.STRING, value.span());
                }
                // value?.toString() ?? "null"
                LocalSymbol receiver = module.newTemporary(type, value.span());
                BoundExpression call = new BoundExpression.NativeCall(stringifier.invocable(),
                        List.of(new BoundExpression.LocalLoad(receiver, classType, value.span())), Types.STRING, value.span());
                Type nullableString = Types.nullable(Types.STRING);
                BoundExpression safe = new BoundExpression.SafeAccess(value, receiver, call, nullableString, value.span());
                LocalSymbol result = module.newTemporary(nullableString, value.span());
                return new BoundExpression.Coalesce(safe, result,
                        new BoundExpression.LocalLoad(result, Types.STRING, value.span()),
                        new BoundExpression.Literal("null", Types.STRING, value.span()), Types.STRING, value.span());
            }
        }
        BoundExpression conversion = new BoundExpression.Conversion(ConversionKind.TO_STRING, value, Types.STRING, value.span());
        Object constant = ConstantEvaluator.evaluate(conversion, ConstantEvaluator.SILENT);
        if (constant instanceof String text) {
            return new BoundExpression.Literal(text, Types.STRING, value.span());
        }
        return conversion;
    }

    private FunctionDeclaration stringifier(ClassType type) {
        for (FunctionDeclaration method : module.members().lookup(type, "toString").methods()) {
            if (method.parameters().isEmpty() && method.returnType() == Types.STRING) {
                return method;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- names and paths

    /** What a name or dotted path refers to. */
    private sealed interface PathResult {
        record Value(BoundExpression expression) implements PathResult {
        }

        record Namespace(String name) implements PathResult {
        }

        record Functions(String name, List<FunctionSymbol> user, List<FunctionDeclaration> natives) implements PathResult {
        }

        record TypeName(String name) implements PathResult {
        }

        /** Nothing has this name; not reported yet. */
        record Unknown(Identifier identifier) implements PathResult {
        }

        /** Resolution failed and the error was reported. */
        record Failed(Span span) implements PathResult {
        }
    }

    private PathResult resolveName(Identifier identifier) {
        String name = identifier.name();
        LocalSymbol local = scope.lookup(name);
        if (local != null) {
            return new PathResult.Value(loadLocal(local, identifier.span()));
        }
        ConstantSymbol constant = module.constants().get(name);
        if (constant != null) {
            return new PathResult.Value(constants.constant(constant, identifier.span()));
        }
        List<FunctionSymbol> user = module.functions(name);
        if (!user.isEmpty()) {
            return new PathResult.Functions(name, user, List.of());
        }
        var property = module.registry().globalProperty(name);
        if (property.isPresent()) {
            return new PathResult.Value(propertyGet(null, property.get(), name, identifier.span()));
        }
        if (module.registry().isNamespace(name)) {
            return new PathResult.Namespace(name);
        }
        List<FunctionDeclaration> natives = module.registry().functions(name);
        if (!natives.isEmpty()) {
            return new PathResult.Functions(name, List.of(), natives);
        }
        if (module.types().isTypeName(name)) {
            return new PathResult.TypeName(name);
        }
        return new PathResult.Unknown(identifier);
    }

    /** Resolves a name or member chain, which may denote a namespace rather than a value. */
    private PathResult bindTarget(Expression syntax) {
        Expression expression = syntax instanceof Expression.Parenthesized ? unwrap(syntax) : syntax;
        if (expression instanceof Expression.Name name) {
            return resolveName(name.identifier());
        }
        if (expression instanceof Expression.Member member) {
            PathResult target = bindTarget(member.target());
            String name = member.member().name();
            return switch (target) {
                case PathResult.Namespace namespace -> {
                    if (member.nullSafe()) {
                        module.error(DiagnosticCode.NOT_A_VALUE, member.span(), "'?.' cannot be used on a namespace.");
                        yield new PathResult.Failed(member.span());
                    }
                    yield namespaceMember(namespace.name(), member.member());
                }
                case PathResult.Value value -> new PathResult.Value(memberOnValue(value.expression(), member));
                case PathResult.Unknown unknown -> {
                    reportUnknownName(unknown.identifier());
                    yield new PathResult.Failed(member.span());
                }
                case PathResult.Failed failed -> failed;
                case PathResult.Functions functions -> {
                    module.error(DiagnosticCode.NOT_A_VALUE, member.target().span(),
                            "'" + functions.name() + "' is a function; call it with parentheses: " + functions.name() + "(...)");
                    yield new PathResult.Failed(member.span());
                }
                case PathResult.TypeName typeName -> {
                    module.error(DiagnosticCode.UNKNOWN_MEMBER, member.member().span(),
                            "Type '" + typeName.name() + "' has no static member '" + name + "'.");
                    yield new PathResult.Failed(member.span());
                }
            };
        }
        return new PathResult.Value(bindValue(expression, null));
    }

    private PathResult namespaceMember(String namespace, Identifier member) {
        String qualified = namespace + "." + member.name();
        var property = module.registry().globalProperty(qualified);
        if (property.isPresent()) {
            return new PathResult.Value(propertyGet(null, property.get(), qualified, member.span()));
        }
        if (module.registry().isNamespace(qualified)) {
            return new PathResult.Namespace(qualified);
        }
        List<FunctionDeclaration> natives = module.registry().functions(qualified);
        if (!natives.isEmpty()) {
            return new PathResult.Functions(qualified, List.of(), natives);
        }
        reportUnknownNamespaceMember(namespace, member);
        return new PathResult.Failed(member.span());
    }

    private BoundExpression expectValue(PathResult result, Expression syntax) {
        return switch (result) {
            case PathResult.Value value -> value.expression();
            case PathResult.Failed failed -> new BoundExpression.Error(syntax.span());
            case PathResult.Unknown unknown -> {
                reportUnknownName(unknown.identifier());
                yield new BoundExpression.Error(syntax.span());
            }
            case PathResult.Namespace namespace -> {
                List<String> members = new ArrayList<>(module.registry().namespaceMembers(namespace.name()));
                Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.NOT_A_VALUE, syntax.span(),
                        "'" + namespace.name() + "' is a namespace, not a value.");
                if (!members.isEmpty()) {
                    builder.note("Members of " + namespace.name() + ": " + String.join(", ", members.subList(0, Math.min(8, members.size())))
                            + (members.size() > 8 ? ", ..." : ""));
                }
                module.report(builder.build());
                yield new BoundExpression.Error(syntax.span());
            }
            case PathResult.Functions functions -> {
                module.error(DiagnosticCode.NOT_A_VALUE, syntax.span(),
                        "'" + functions.name() + "' is a function; call it with parentheses: " + functions.name() + "(...)");
                yield new BoundExpression.Error(syntax.span());
            }
            case PathResult.TypeName typeName -> {
                module.error(DiagnosticCode.NOT_A_VALUE, syntax.span(), "'" + typeName.name() + "' is a type, not a value.");
                yield new BoundExpression.Error(syntax.span());
            }
        };
    }

    BoundExpression loadLocal(LocalSymbol local, Span span) {
        local.markRead();
        Type current = flow.typeOf(local);
        if (current.equals(local.type())) {
            return new BoundExpression.LocalLoad(local, current, span);
        }
        if (local.type().representation() == Representation.REF && current.representation().isPrimitive()) {
            return new BoundExpression.Conversion(ConversionKind.UNBOX,
                    new BoundExpression.LocalLoad(local, local.type(), span), current, span);
        }
        return new BoundExpression.LocalLoad(local, current, span);
    }

    // ------------------------------------------------------------------- members

    private BoundExpression memberOnValue(BoundExpression receiver, Expression.Member member) {
        return nullSafe(receiver, member.target(), member.member().name(), member.nullSafe(), member.span(),
                value -> memberAccess(value, member.member(), member.span()));
    }

    private interface Access {
        BoundExpression apply(BoundExpression receiver);
    }

    /**
     * Applies {@code access} to a receiver, handling {@code ?.}: a nullable receiver without
     * {@code ?.} is an error; with {@code ?.} the access runs only for non-null receivers.
     */
    private BoundExpression nullSafe(BoundExpression receiver, Expression receiverSyntax, String memberName, boolean safe,
                                     Span span, Access access) {
        Type type = receiver.type();
        if (type.isError()) {
            return new BoundExpression.Error(span);
        }
        if (safe && !type.isNullable()) {
            module.report(module.diagnostic(DiagnosticCode.UNNECESSARY_SAFE_CALL, span,
                    "'?.' is unnecessary: " + describeValue(receiver) + " is never null.").build());
            safe = false;
        }
        if (type instanceof NullType) {
            module.error(DiagnosticCode.NULLABLE_ACCESS, span, "Cannot access members of 'null'.");
            return new BoundExpression.Error(span);
        }
        if (type.isNullable()) {
            if (!safe) {
                reportNullableAccess(receiver, receiverSyntax, memberName);
                return new BoundExpression.Error(span);
            }
            LocalSymbol temporary = module.newTemporary(type, receiver.span());
            BoundExpression inner = access.apply(new BoundExpression.LocalLoad(temporary, type.nonNullable(), receiver.span()));
            if (inner.type().isError()) {
                return inner;
            }
            if (inner.type() == PrimitiveType.VOID) {
                return new BoundExpression.SafeAccess(receiver, temporary, inner, PrimitiveType.VOID, span);
            }
            Type resultType = Types.nullable(inner.type());
            return new BoundExpression.SafeAccess(receiver, temporary, Conversions.apply(inner, resultType), resultType, span);
        }
        return access.apply(receiver);
    }

    private BoundExpression memberAccess(BoundExpression receiver, Identifier member, Span span) {
        String name = member.name();
        Type type = receiver.type();
        if (type instanceof ClassType classType) {
            MemberLookup.Result result = module.members().lookup(classType, name);
            if (result.property() != null) {
                return propertyGet(receiver, result.property(), classType.name() + "." + name, span);
            }
            if (!result.methods().isEmpty()) {
                module.error(DiagnosticCode.NOT_A_VALUE, member.span(),
                        "'" + name + "' is a method; call it with parentheses: " + name + "(...)");
                return new BoundExpression.Error(span);
            }
            reportUnknownMember(classType, member, receiver);
            return new BoundExpression.Error(span);
        }
        if (type instanceof ListType) {
            switch (name) {
                case "size" -> {
                    return new BoundExpression.ListSize(receiver, span);
                }
                case "isEmpty" -> {
                    return new BoundExpression.Compare(ComparisonOp.EQUAL, Representation.INT,
                            new BoundExpression.ListSize(receiver, span), new BoundExpression.Literal(0, PrimitiveType.INT, span), span);
                }
                default -> {
                    module.report(module.diagnostic(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                                    "Unknown member '" + name + "' on " + type.displayName() + ".")
                            .suggestions(Suggestions.closest(name, List.of("size", "isEmpty", "add", "contains", "get"), 3)).build());
                    return new BoundExpression.Error(span);
                }
            }
        }
        module.error(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                "Values of type " + type.displayName() + " have no member '" + name + "'.");
        return new BoundExpression.Error(span);
    }

    private BoundExpression propertyGet(BoundExpression receiver, PropertyDeclaration property, String display, Span span) {
        warnDeprecated(property.deprecation().orElse(null), display, span);
        List<BoundExpression> arguments = receiver == null ? List.of() : List.of(receiver);
        return new BoundExpression.NativeCall(property.getter(), arguments, property.type(), span);
    }

    // ------------------------------------------------------------------- calls

    private BoundExpression bindCall(Expression.Call call) {
        Expression callee = unwrap(call.callee());
        if (callee instanceof Expression.Member member) {
            PathResult target = bindTarget(member.target());
            String name = member.member().name();
            return switch (target) {
                case PathResult.Namespace namespace -> {
                    String qualified = namespace.name() + "." + name;
                    List<FunctionDeclaration> natives = module.registry().functions(qualified);
                    if (natives.isEmpty()) {
                        bindArgumentsForErrors(call.arguments());
                        if (module.registry().globalProperty(qualified).isPresent()) {
                            module.error(DiagnosticCode.NOT_CALLABLE, member.member().span(),
                                    "'" + qualified + "' is a property, not a function; remove the parentheses.");
                        } else {
                            reportUnknownNamespaceMember(namespace.name(), member.member());
                        }
                        yield new BoundExpression.Error(call.span());
                    }
                    yield callFunctions(qualified, List.of(), natives, null, call);
                }
                case PathResult.Value value -> nullSafe(value.expression(), member.target(), name, member.nullSafe(),
                        call.span(), receiver -> methodCall(receiver, member.member(), call));
                case PathResult.Unknown unknown -> {
                    bindArgumentsForErrors(call.arguments());
                    reportUnknownName(unknown.identifier());
                    yield new BoundExpression.Error(call.span());
                }
                case PathResult.Failed ignored -> {
                    bindArgumentsForErrors(call.arguments());
                    yield new BoundExpression.Error(call.span());
                }
                case PathResult.Functions functions -> {
                    bindArgumentsForErrors(call.arguments());
                    module.error(DiagnosticCode.NOT_A_VALUE, member.target().span(),
                            "'" + functions.name() + "' is a function; call it with parentheses: " + functions.name() + "(...)");
                    yield new BoundExpression.Error(call.span());
                }
                case PathResult.TypeName typeName -> {
                    bindArgumentsForErrors(call.arguments());
                    module.error(DiagnosticCode.UNKNOWN_MEMBER, member.member().span(),
                            "Type '" + typeName.name() + "' has no static function '" + name + "'.");
                    yield new BoundExpression.Error(call.span());
                }
            };
        }
        if (callee instanceof Expression.Name name) {
            String text = name.name();
            LocalSymbol local = scope.lookup(text);
            if (local != null) {
                bindArgumentsForErrors(call.arguments());
                module.error(DiagnosticCode.NOT_CALLABLE, name.span(), "'" + text + "' is " + describeKind(local)
                        + " of type " + local.type().displayName() + ", not a function.");
                return new BoundExpression.Error(call.span());
            }
            List<FunctionSymbol> user = module.functions(text);
            if (!user.isEmpty()) {
                return callFunctions(text, user, List.of(), null, call);
            }
            List<FunctionDeclaration> natives = module.registry().functions(text);
            if (!natives.isEmpty()) {
                return callFunctions(text, List.of(), natives, null, call);
            }
            bindArgumentsForErrors(call.arguments());
            if (module.constants().containsKey(text) || module.registry().globalProperty(text).isPresent()) {
                module.error(DiagnosticCode.NOT_CALLABLE, name.span(), "'" + text + "' is a value, not a function.");
            } else {
                List<String> candidates = new ArrayList<>(module.functions().keySet());
                for (String member : module.registry().namespaceMembers("")) {
                    if (!module.registry().functions(member).isEmpty()) {
                        candidates.add(member);
                    }
                }
                module.report(module.diagnostic(DiagnosticCode.UNKNOWN_FUNCTION, name.span(),
                        "Unknown function '" + text + "'.").suggestions(Suggestions.closest(text, candidates, 3)).build());
            }
            return new BoundExpression.Error(call.span());
        }
        bindValue(callee, null);
        bindArgumentsForErrors(call.arguments());
        module.error(DiagnosticCode.NOT_CALLABLE, callee.span(), "This expression cannot be called.");
        return new BoundExpression.Error(call.span());
    }

    private BoundExpression methodCall(BoundExpression receiver, Identifier method, Expression.Call call) {
        String name = method.name();
        Type type = receiver.type();
        if (type instanceof ClassType classType) {
            MemberLookup.Result result = module.members().lookup(classType, name);
            if (!result.methods().isEmpty()) {
                return callFunctions(classType.name() + "." + name, List.of(), result.methods(), receiver, call);
            }
            bindArgumentsForErrors(call.arguments());
            if (result.property() != null) {
                module.error(DiagnosticCode.NOT_CALLABLE, method.span(), "'" + name + "' is a property, not a method; "
                        + "remove the parentheses.");
            } else {
                reportUnknownMember(classType, method, receiver);
            }
            return new BoundExpression.Error(call.span());
        }
        if (type instanceof ListType list) {
            return listMethod(receiver, list, method, call);
        }
        bindArgumentsForErrors(call.arguments());
        module.error(DiagnosticCode.UNKNOWN_MEMBER, method.span(),
                "Values of type " + type.displayName() + " have no method '" + name + "'.");
        return new BoundExpression.Error(call.span());
    }

    private BoundExpression listMethod(BoundExpression list, ListType type, Identifier method, Expression.Call call) {
        String name = method.name();
        List<Expression> arguments = call.arguments();
        Type element = type.element();
        switch (name) {
            case "add", "contains" -> {
                if (arguments.size() != 1) {
                    return wrongListArguments(name, "(element: " + element.displayName() + ")", call);
                }
                BoundExpression value = convert(bindValue(arguments.getFirst(), element), element,
                        arguments.getFirst().span(), "the list element");
                return name.equals("add") ? new BoundExpression.ListAdd(list, value, call.span())
                        : new BoundExpression.ListContains(list, value, call.span());
            }
            case "get" -> {
                if (arguments.size() != 1) {
                    return wrongListArguments(name, "(index: int)", call);
                }
                BoundExpression index = convert(bindValue(arguments.getFirst(), PrimitiveType.INT), PrimitiveType.INT,
                        arguments.getFirst().span(), "the list index");
                return new BoundExpression.ListGet(list, index, element, call.span());
            }
            default -> {
                bindArgumentsForErrors(arguments);
                module.report(module.diagnostic(DiagnosticCode.UNKNOWN_MEMBER, method.span(),
                                "Unknown method '" + name + "' on " + type.displayName() + ".")
                        .suggestions(Suggestions.closest(name, List.of("add", "contains", "get", "size", "isEmpty"), 3)).build());
                return new BoundExpression.Error(call.span());
            }
        }
    }

    private BoundExpression wrongListArguments(String name, String signature, Expression.Call call) {
        bindArgumentsForErrors(call.arguments());
        module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                "'" + name + "' expects 1 argument, but " + call.arguments().size() + " were given.")
                .note("Signature:\n    " + name + signature).build());
        return new BoundExpression.Error(call.span());
    }

    private void bindArgumentsForErrors(List<Expression> arguments) {
        for (Expression argument : arguments) {
            bind(argument, null);
        }
    }

    // ------------------------------------------------------------------- overloads

    /** A call target: a script function or a host function/method. */
    private record Candidate(FunctionSymbol user, FunctionDeclaration host, List<Type> parameters, List<String> names) {
        static Candidate of(FunctionSymbol symbol) {
            return new Candidate(symbol, null, symbol.parameterTypes(), symbol.parameterNames());
        }

        static Candidate of(FunctionDeclaration declaration) {
            List<Type> types = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Parameter parameter : declaration.parameters()) {
                types.add(parameter.type());
                names.add(parameter.name());
            }
            return new Candidate(null, declaration, types, names);
        }

        Type returnType() {
            return user != null ? user.returnType() : host.returnType();
        }

        String signature(String display) {
            StringJoiner joiner = new StringJoiner(", ", display + "(", ")");
            for (int i = 0; i < parameters.size(); i++) {
                joiner.add(names.get(i) + ": " + parameters.get(i).displayName());
            }
            return joiner + ": " + returnType().displayName();
        }
    }

    /** An argument bound before overload selection, or one whose typing depends on the parameter. */
    private sealed interface Argument {
        Span span();

        Type naturalType();

        record Bound(BoundExpression expression) implements Argument {
            public Span span() {
                return expression.span();
            }

            public Type naturalType() {
                return expression.type();
            }
        }

        /** String literals, templates, numeric literals, null and empty lists: typed by the parameter. */
        record Deferred(Expression syntax, Type naturalType, boolean text, boolean emptyList) implements Argument {
            public Span span() {
                return syntax.span();
            }
        }
    }

    private Argument prebind(Expression syntax) {
        Expression expression = unwrap(syntax);
        if (expression instanceof Expression.Template) {
            return new Argument.Deferred(syntax, Types.STRING, true, false);
        }
        if (expression instanceof Expression.ListLiteral list && list.elements().isEmpty()) {
            return new Argument.Deferred(syntax, Types.ERROR, false, true);
        }
        Expression.Literal literal = expression instanceof Expression.Literal l ? l
                : expression instanceof Expression.Unary unary && unary.operator() == UnaryOperator.NEGATE
                && unwrap(unary.operand()) instanceof Expression.Literal l2 ? l2 : null;
        if (literal != null) {
            switch (literal.kind()) {
                case STRING -> {
                    return new Argument.Deferred(syntax, Types.STRING, true, false);
                }
                case NULL -> {
                    return new Argument.Deferred(syntax, Types.NULL, false, false);
                }
                case INT -> {
                    long magnitude = (Long) literal.value();
                    long limit = literal == expression ? Integer.MAX_VALUE : 2_147_483_648L;
                    Type natural = Long.compareUnsigned(magnitude, limit) <= 0 ? PrimitiveType.INT : PrimitiveType.LONG;
                    return new Argument.Deferred(syntax, natural, false, false);
                }
                default -> {
                }
            }
        }
        return new Argument.Bound(bindValue(syntax, null));
    }

    private static int argumentCost(Argument argument, Type parameter) {
        if (argument instanceof Argument.Deferred deferred) {
            if (deferred.emptyList()) {
                return parameter.nonNullable() instanceof ListType ? 0 : Conversions.NONE;
            }
            if (deferred.text()) {
                Type target = parameter.nonNullable();
                if (target == Types.COMPONENT) {
                    return 1;
                }
                return Conversions.cost(Types.STRING, parameter);
            }
        }
        return Conversions.cost(argument.naturalType(), parameter);
    }

    private BoundExpression callFunctions(String display, List<FunctionSymbol> user, List<FunctionDeclaration> host,
                                          BoundExpression receiver, Expression.Call call) {
        List<Candidate> candidates = new ArrayList<>();
        user.forEach(symbol -> candidates.add(Candidate.of(symbol)));
        host.forEach(declaration -> candidates.add(Candidate.of(declaration)));
        List<Argument> arguments = new ArrayList<>();
        for (Expression argument : call.arguments()) {
            arguments.add(prebind(argument));
        }
        if (arguments.stream().anyMatch(argument -> argument.naturalType().isError()
                && !(argument instanceof Argument.Deferred deferred && deferred.emptyList()))) {
            bindDeferredForErrors(arguments);
            return new BoundExpression.Error(call.span());
        }

        List<Candidate> applicable = new ArrayList<>();
        List<int[]> costs = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.parameters().size() != arguments.size()) {
                continue;
            }
            int[] cost = new int[arguments.size()];
            boolean ok = true;
            for (int i = 0; i < arguments.size() && ok; i++) {
                cost[i] = argumentCost(arguments.get(i), candidate.parameters().get(i));
                ok = cost[i] != Conversions.NONE;
            }
            if (ok) {
                applicable.add(candidate);
                costs.add(cost);
            }
        }
        if (applicable.isEmpty()) {
            reportNoApplicable(display, candidates, arguments, call);
            bindDeferredForErrors(arguments);
            return new BoundExpression.Error(call.span());
        }
        int best = mostSpecific(applicable, costs);
        if (best < 0) {
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.AMBIGUOUS_OVERLOAD, call.span(),
                    "Call to '" + display + "' is ambiguous.");
            StringJoiner joiner = new StringJoiner("\n    ", "Matching candidates:\n    ", "");
            applicable.forEach(candidate -> joiner.add(candidate.signature(display)));
            module.report(builder.note(joiner.toString()).note("Convert an argument with 'as' to choose one.").build());
            bindDeferredForErrors(arguments);
            return new BoundExpression.Error(call.span());
        }
        Candidate chosen = applicable.get(best);
        List<BoundExpression> converted = new ArrayList<>();
        if (receiver != null) {
            converted.add(receiver);
        }
        for (int i = 0; i < arguments.size(); i++) {
            Type parameter = chosen.parameters().get(i);
            BoundExpression value = switch (arguments.get(i)) {
                case Argument.Bound bound -> bound.expression();
                case Argument.Deferred deferred -> bindValue(deferred.syntax(), parameter);
            };
            converted.add(convert(value, parameter, arguments.get(i).span(),
                    "argument #" + (i + 1) + " of '" + display + "'"));
        }
        Type resultType = chosen.returnType();
        if (chosen.user() != null) {
            return new BoundExpression.FunctionCall(chosen.user(), converted, resultType, call.span());
        }
        warnDeprecated(chosen.host().deprecation().orElse(null), display + "(...)", call.span());
        return new BoundExpression.NativeCall(chosen.host().invocable(), converted, resultType, call.span());
    }

    private void bindDeferredForErrors(List<Argument> arguments) {
        for (Argument argument : arguments) {
            if (argument instanceof Argument.Deferred deferred) {
                bind(deferred.syntax(), null);
            }
        }
    }

    /** Index of the unique most specific candidate, or -1 when ambiguous. */
    private static int mostSpecific(List<Candidate> candidates, List<int[]> costs) {
        int result = -1;
        for (int i = 0; i < candidates.size(); i++) {
            boolean dominated = false;
            for (int j = 0; j < candidates.size() && !dominated; j++) {
                dominated = i != j && dominates(candidates.get(j), costs.get(j), candidates.get(i), costs.get(i));
            }
            if (!dominated) {
                if (result >= 0) {
                    return -1;
                }
                result = i;
            }
        }
        return result;
    }

    private static boolean dominates(Candidate a, int[] costA, Candidate b, int[] costB) {
        boolean strictlyBetter = false;
        for (int i = 0; i < costA.length; i++) {
            if (costA[i] > costB[i]) {
                return false;
            }
            if (costA[i] < costB[i]) {
                strictlyBetter = true;
            }
        }
        if (strictlyBetter) {
            return true;
        }
        // Equal costs: prefer more specific parameter types (e.g. Player over Entity).
        boolean aToB = true;
        boolean bToA = true;
        for (int i = 0; i < costA.length; i++) {
            aToB &= Conversions.isAssignable(a.parameters().get(i), b.parameters().get(i));
            bToA &= Conversions.isAssignable(b.parameters().get(i), a.parameters().get(i));
        }
        return aToB && !bToA;
    }

    private void reportNoApplicable(String display, List<Candidate> candidates, List<Argument> arguments, Expression.Call call) {
        List<Candidate> sameArity = candidates.stream().filter(c -> c.parameters().size() == arguments.size()).toList();
        StringJoiner received = new StringJoiner(", ", "(", ")");
        arguments.forEach(argument -> received.add(argument instanceof Argument.Deferred d && d.emptyList()
                ? "empty list" : argument.naturalType().displayName()));
        if (candidates.size() == 1) {
            Candidate only = candidates.getFirst();
            if (sameArity.isEmpty()) {
                int expected = only.parameters().size();
                module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                                "'" + display + "' expects " + expected + " argument" + (expected == 1 ? "" : "s")
                                        + ", but " + arguments.size() + (arguments.size() == 1 ? " was" : " were") + " given.")
                        .note("Signature:\n    " + only.signature(display)).build());
                return;
            }
            for (int i = 0; i < arguments.size(); i++) {
                Argument argument = arguments.get(i);
                Type parameter = only.parameters().get(i);
                if (argumentCost(argument, parameter) == Conversions.NONE) {
                    Type actual = argument.naturalType();
                    Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.TYPE_MISMATCH, argument.span(),
                            "Argument #" + (i + 1) + " of '" + display + "' has the wrong type.")
                            .expectedReceived(parameter.displayName(), argument instanceof Argument.Deferred d && d.emptyList()
                                    ? "empty list" : actual.displayName());
                    if (actual.isNullable() && !(actual instanceof NullType)
                            && Conversions.isAssignable(actual.nonNullable(), parameter)) {
                        builder.note("The value may be null. Check for null first, or give a default with '??'.");
                    }
                    module.report(builder.build());
                    return;
                }
            }
            return;
        }
        StringJoiner options = new StringJoiner("\n    ", "Expected one of:\n    ", "");
        candidates.forEach(candidate -> options.add(candidate.signature(display)));
        if (sameArity.isEmpty()) {
            module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                    "No overload of '" + display + "' takes " + arguments.size() + " argument"
                            + (arguments.size() == 1 ? "" : "s") + ".").note(options.toString()).build());
            return;
        }
        module.report(module.diagnostic(DiagnosticCode.NO_MATCHING_OVERLOAD, call.span(),
                        "No matching overload for " + display + ".")
                .note(options.toString()).note("Received:\n    " + received).build());
    }

    // ------------------------------------------------------------------- operators

    private BoundExpression bindUnary(Expression.Unary unary, Type expected) {
        if (unary.operator() == UnaryOperator.NOT) {
            Condition condition = bindCondition(unary);
            return condition.expression();
        }
        Expression operandSyntax = unwrap(unary.operand());
        if (operandSyntax instanceof Expression.Literal literal && isNumericLiteral(literal)) {
            return bindLiteral(literal, expected, true, unary.span());
        }
        BoundExpression operand = bindValue(unary.operand(), expected);
        Type type = operand.type();
        if (type.isError()) {
            return new BoundExpression.Error(unary.span());
        }
        if (type instanceof PrimitiveType primitive && (primitive.isNumeric() || primitive == PrimitiveType.DURATION)) {
            return new BoundExpression.Negate(primitive.representation(), operand, type, unary.span());
        }
        module.error(DiagnosticCode.INVALID_OPERATOR, unary.span(),
                "Operator '-' cannot be applied to a value of type " + type.displayName() + ".");
        return new BoundExpression.Error(unary.span());
    }

    private static boolean isNumericLiteral(Expression.Literal literal) {
        return switch (literal.kind()) {
            case INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    private BoundExpression bindBinary(Expression.Binary binary, Type expected) {
        BinaryOperator operator = binary.operator();
        if (operator.isLogical()) {
            return bindCondition(binary).expression();
        }
        if (operator == BinaryOperator.COALESCE) {
            return bindCoalesce(binary, expected);
        }
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            boolean leftNull = isNullLiteral(binary.left());
            boolean rightNull = isNullLiteral(binary.right());
            if (leftNull || rightNull) {
                return nullComparison(binary, leftNull ? binary.right() : binary.left(), operator == BinaryOperator.EQUAL);
            }
        }
        BoundExpression left = bindValue(binary.left(), null);
        Type hint = left.type() instanceof PrimitiveType primitive && primitive.isNumeric() ? primitive : null;
        BoundExpression right = bindValue(binary.right(), hint);
        return binaryOperation(operator, left, right, binary.span());
    }

    private static boolean isNullLiteral(Expression expression) {
        return unwrap(expression) instanceof Expression.Literal literal && literal.kind() == Expression.LiteralKind.NULL;
    }

    private BoundExpression nullComparison(Expression.Binary binary, Expression operandSyntax, boolean isNull) {
        BoundExpression operand = bindValue(operandSyntax, null);
        Type type = operand.type();
        if (type.isError()) {
            return new BoundExpression.Error(binary.span());
        }
        if (!type.isNullable()) {
            module.report(module.diagnostic(DiagnosticCode.UNNECESSARY_NULL_CHECK, binary.span(),
                    describeValue(operand) + " is never null, so this comparison is always "
                            + (isNull ? "false" : "true") + ".").build());
        }
        return new BoundExpression.NullCheck(operand, isNull, binary.span());
    }

    /** Types a binary operator applied to already bound operands. */
    BoundExpression binaryOperation(BinaryOperator operator, BoundExpression left, BoundExpression right, Span span) {
        Type a = left.type();
        Type b = right.type();
        if (a.isError() || b.isError()) {
            return new BoundExpression.Error(span);
        }
        if (operator == BinaryOperator.ADD && (a == Types.STRING || b == Types.STRING)) {
            if (a == Types.COMPONENT || b == Types.COMPONENT) {
                return invalidOperator(operator, a, b, span);
            }
            return concat(List.of(toText(left), toText(right)), span);
        }
        if (operator.isArithmetic()) {
            return arithmetic(operator, left, right, span);
        }
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            return equality(operator, left, right, span);
        }
        return ordering(operator, left, right, span);
    }

    private BoundExpression arithmetic(BinaryOperator operator, BoundExpression left, BoundExpression right, Span span) {
        Type a = left.type();
        Type b = right.type();
        ArithmeticOp op = switch (operator) {
            case ADD -> ArithmeticOp.ADD;
            case SUBTRACT -> ArithmeticOp.SUBTRACT;
            case MULTIPLY -> ArithmeticOp.MULTIPLY;
            case DIVIDE -> ArithmeticOp.DIVIDE;
            default -> ArithmeticOp.REMAINDER;
        };
        PrimitiveType promoted = promote(a, b);
        if (promoted != null) {
            BoundExpression l = Conversions.apply(left, promoted);
            BoundExpression r = Conversions.apply(right, promoted);
            checkDivisionByZero(op, promoted, r);
            return new BoundExpression.Arithmetic(op, promoted.representation(), l, r, promoted, span);
        }
        boolean aDuration = a == PrimitiveType.DURATION;
        boolean bDuration = b == PrimitiveType.DURATION;
        boolean aIntegral = a == PrimitiveType.INT || a == PrimitiveType.LONG;
        boolean bIntegral = b == PrimitiveType.INT || b == PrimitiveType.LONG;
        if (aDuration && bDuration && (op == ArithmeticOp.ADD || op == ArithmeticOp.SUBTRACT)) {
            return new BoundExpression.Arithmetic(op, Representation.LONG, left, right, PrimitiveType.DURATION, span);
        }
        if (aDuration && bIntegral && (op == ArithmeticOp.MULTIPLY || op == ArithmeticOp.DIVIDE)) {
            BoundExpression r = Conversions.apply(right, PrimitiveType.LONG);
            checkDivisionByZero(op, PrimitiveType.LONG, r);
            return new BoundExpression.Arithmetic(op, Representation.LONG, left, r, PrimitiveType.DURATION, span);
        }
        if (aIntegral && bDuration && op == ArithmeticOp.MULTIPLY) {
            return new BoundExpression.Arithmetic(op, Representation.LONG, Conversions.apply(left, PrimitiveType.LONG), right,
                    PrimitiveType.DURATION, span);
        }
        return invalidOperator(operator, a, b, span);
    }

    private void checkDivisionByZero(ArithmeticOp op, PrimitiveType type, BoundExpression divisor) {
        if ((op == ArithmeticOp.DIVIDE || op == ArithmeticOp.REMAINDER)
                && (type == PrimitiveType.INT || type == PrimitiveType.LONG)
                && divisor instanceof BoundExpression.Literal literal && ((Number) literal.value()).longValue() == 0) {
            module.error(DiagnosticCode.DIVISION_BY_ZERO, divisor.span(), "Division by zero.");
        }
    }

    /** Binary numeric promotion; {@code null} if either operand is not a numeric primitive. */
    private static PrimitiveType promote(Type a, Type b) {
        if (!(a instanceof PrimitiveType x && x.isNumeric() && b instanceof PrimitiveType y && y.isNumeric())) {
            return null;
        }
        if (x == PrimitiveType.DOUBLE || y == PrimitiveType.DOUBLE) {
            return PrimitiveType.DOUBLE;
        }
        if (x == PrimitiveType.FLOAT || y == PrimitiveType.FLOAT) {
            // long + float widens both to double: long -> float would lose precision silently.
            return x == PrimitiveType.LONG || y == PrimitiveType.LONG ? PrimitiveType.DOUBLE : PrimitiveType.FLOAT;
        }
        if (x == PrimitiveType.LONG || y == PrimitiveType.LONG) {
            return PrimitiveType.LONG;
        }
        return PrimitiveType.INT;
    }

    private BoundExpression ordering(BinaryOperator operator, BoundExpression left, BoundExpression right, Span span) {
        ComparisonOp op = comparison(operator);
        Type a = left.type();
        Type b = right.type();
        PrimitiveType promoted = promote(a, b);
        if (promoted != null) {
            return new BoundExpression.Compare(op, promoted.representation(), Conversions.apply(left, promoted),
                    Conversions.apply(right, promoted), span);
        }
        if (a == PrimitiveType.DURATION && b == PrimitiveType.DURATION) {
            return new BoundExpression.Compare(op, Representation.LONG, left, right, span);
        }
        return invalidOperator(operator, a, b, span);
    }

    private BoundExpression equality(BinaryOperator operator, BoundExpression left, BoundExpression right, Span span) {
        ComparisonOp op = comparison(operator);
        Type a = left.type();
        Type b = right.type();
        PrimitiveType promoted = promote(a, b);
        if (promoted != null) {
            return new BoundExpression.Compare(op, promoted.representation(), Conversions.apply(left, promoted),
                    Conversions.apply(right, promoted), span);
        }
        if (a == b && (a == PrimitiveType.BOOL || a == PrimitiveType.DURATION)) {
            return new BoundExpression.Compare(op, a.representation(), left, right, span);
        }
        if (a.representation() == Representation.REF && b.representation() == Representation.REF) {
            if (comparable(a, b)) {
                return new BoundExpression.Compare(op, Representation.REF, left, right, span);
            }
        } else if (a.representation() == Representation.REF && a.nonNullable().equals(b)) {
            return new BoundExpression.Compare(op, Representation.REF, left, Conversions.apply(right, a), span);
        } else if (b.representation() == Representation.REF && b.nonNullable().equals(a)) {
            return new BoundExpression.Compare(op, Representation.REF, Conversions.apply(left, b), right, span);
        }
        module.report(module.diagnostic(DiagnosticCode.INVALID_OPERATOR, span,
                        "Values of type " + a.displayName() + " and " + b.displayName() + " cannot be compared.")
                .note("They can never be equal.").build());
        return new BoundExpression.Error(span);
    }

    private static boolean comparable(Type a, Type b) {
        Type x = a.nonNullable();
        Type y = b.nonNullable();
        if (x == Types.COMPONENT && y == Types.STRING || x == Types.STRING && y == Types.COMPONENT) {
            return false;
        }
        if (x instanceof ClassType cx && y instanceof ClassType cy) {
            // Unrelated class types may still share a subtype (Player is both Entity and CommandSender).
            return cx != Types.STRING && cy != Types.STRING && cx != Types.COMPONENT && cy != Types.COMPONENT
                    || cx == cy || cx == Types.ANY || cy == Types.ANY;
        }
        return Conversions.isAssignable(x, y) || Conversions.isAssignable(y, x);
    }

    private static ComparisonOp comparison(BinaryOperator operator) {
        return switch (operator) {
            case EQUAL -> ComparisonOp.EQUAL;
            case NOT_EQUAL -> ComparisonOp.NOT_EQUAL;
            case LESS -> ComparisonOp.LESS;
            case LESS_EQUAL -> ComparisonOp.LESS_EQUAL;
            case GREATER -> ComparisonOp.GREATER;
            default -> ComparisonOp.GREATER_EQUAL;
        };
    }

    private BoundExpression invalidOperator(BinaryOperator operator, Type a, Type b, Span span) {
        Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.INVALID_OPERATOR, span,
                "Operator '" + operator.symbol() + "' cannot be applied to " + a.displayName() + " and " + b.displayName() + ".");
        if (a.isNullable() || b.isNullable()) {
            builder.note("One side may be null. Check for null first, or give a default with '??'.");
        } else if (a == Types.STRING && b == Types.STRING) {
            builder.note("Strings can only be joined with '+' or compared with '==' and '!='.");
        }
        module.report(builder.build());
        return new BoundExpression.Error(span);
    }

    private BoundExpression bindCoalesce(Expression.Binary binary, Type expected) {
        BoundExpression left = bindValue(binary.left(), null);
        Type leftType = left.type();
        if (leftType.isError()) {
            bindValue(binary.right(), null);
            return new BoundExpression.Error(binary.span());
        }
        if (!leftType.isNullable()) {
            module.report(module.diagnostic(DiagnosticCode.UNNECESSARY_NULL_CHECK, binary.left().span(),
                    "The left side of '??' is never null.").build());
            bindValue(binary.right(), null);
            return left;
        }
        if (leftType instanceof NullType) {
            return bindValue(binary.right(), expected);
        }
        Type base = leftType.nonNullable();
        BoundExpression right = bindValue(binary.right(), expected != null ? expected : base);
        Type rightType = right.type();
        if (rightType.isError()) {
            return new BoundExpression.Error(binary.span());
        }
        Type rightBase = rightType instanceof NullType ? base : rightType.nonNullable();
        Type resultBase;
        if (Conversions.isAssignable(rightBase, base)) {
            resultBase = base;
        } else if (Conversions.isAssignable(base, rightBase)) {
            resultBase = rightBase;
        } else {
            module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, binary.span(),
                            "The two sides of '??' have incompatible types.")
                    .expectedReceived(base.displayName(), rightType.displayName()).build());
            return new BoundExpression.Error(binary.span());
        }
        if (resultBase == Types.COMPONENT) {
            if (base == Types.STRING) {
                return unsafeTextFormatting(left, binary.left().span());
            }
            if (rightBase == Types.STRING && !(right instanceof BoundExpression.Literal)) {
                return unsafeTextFormatting(right, binary.right().span());
            }
        }
        Type resultType = rightType.isNullable() ? Types.nullable(resultBase) : resultBase;
        LocalSymbol temporary = module.newTemporary(leftType, left.span());
        BoundExpression nonNull = base.representation().isPrimitive()
                ? new BoundExpression.Conversion(ConversionKind.UNBOX,
                new BoundExpression.LocalLoad(temporary, leftType, left.span()), base, left.span())
                : new BoundExpression.LocalLoad(temporary, base, left.span());
        return new BoundExpression.Coalesce(left, temporary, Conversions.apply(nonNull, resultType),
                Conversions.apply(right, resultType), resultType, binary.span());
    }

    // ------------------------------------------------------------------- type tests and casts

    private BoundExpression bindIs(Expression.Is is) {
        BoundExpression operand = bindValue(is.operand(), null);
        Type target = module.types().resolve(is.type(), false);
        if (operand.type().isError() || target.isError()) {
            return new BoundExpression.Error(is.span());
        }
        if (!(target instanceof ClassType classType)) {
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.INVALID_CAST, is.type().span(),
                    "'is' can only test for class types such as Player, not " + target.displayName() + ".");
            if (target.isNullable()) {
                builder.note("Remove the '?': null is never an instance of a type.");
            }
            module.report(builder.build());
            return new BoundExpression.Error(is.span());
        }
        if (operand.type().representation() != Representation.REF) {
            module.error(DiagnosticCode.INVALID_CAST, is.operand().span(),
                    "A value of type " + operand.type().displayName() + " is never a " + classType.name() + ".");
            return new BoundExpression.Error(is.span());
        }
        return new BoundExpression.TypeTest(operand, classType, is.span());
    }

    private BoundExpression bindCast(Expression.Cast cast) {
        BoundExpression operand = bindValue(cast.operand(), null);
        Type target = module.types().resolve(cast.type(), false);
        Type source = operand.type();
        if (source.isError() || target.isError()) {
            return new BoundExpression.Error(cast.span());
        }
        if (Conversions.isExplicitNumeric(source, target) && !cast.safe()) {
            if (source.equals(target)) {
                return operand;
            }
            if (operand instanceof BoundExpression.Literal literal) {
                return new BoundExpression.Literal(Conversions.convertNumber(literal.value(), (PrimitiveType) target),
                        target, cast.span());
            }
            return new BoundExpression.Conversion(ConversionKind.NUMERIC, operand, target, cast.span());
        }
        if (target instanceof ClassType classType && source.representation() == Representation.REF) {
            if (source.nonNullable() instanceof ClassType from && from.isSubtypeOf(classType) && !source.isNullable()) {
                return operand;
            }
            Type resultType = cast.safe() ? Types.nullable(classType) : classType;
            return new BoundExpression.Cast(operand, classType, cast.safe(), resultType, cast.span());
        }
        Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.INVALID_CAST, cast.span(),
                "Cannot convert " + source.displayName() + " to " + target.displayName() + ".");
        if (target.isNullable()) {
            builder.note("Use 'as?' to get null when the value is not a " + target.nonNullable().displayName() + ".");
        } else if (cast.safe()) {
            builder.note("'as?' works on reference types; numbers are converted with 'as'.");
        }
        module.report(builder.build());
        return new BoundExpression.Error(cast.span());
    }

    // ------------------------------------------------------------------- lists

    private BoundExpression bindIndex(Expression.Index index) {
        BoundExpression list = bindValue(index.target(), null);
        Type type = list.type();
        if (type.isError()) {
            bindValue(index.index(), null);
            return new BoundExpression.Error(index.span());
        }
        if (type instanceof ListType listType) {
            BoundExpression position = convert(bindValue(index.index(), PrimitiveType.INT), PrimitiveType.INT,
                    index.index().span(), "the list index");
            return new BoundExpression.ListGet(list, position, listType.element(), index.span());
        }
        bindValue(index.index(), null);
        if (type.isNullable()) {
            reportNullableAccess(list, index.target(), "[...]");
        } else {
            module.error(DiagnosticCode.INVALID_OPERATOR, index.span(), "Cannot index a value of type " + type.displayName() + ".");
        }
        return new BoundExpression.Error(index.span());
    }

    private BoundExpression bindList(Expression.ListLiteral list, Type expected) {
        Type expectedElement = expected != null && expected.nonNullable() instanceof ListType listType ? listType.element() : null;
        if (list.elements().isEmpty()) {
            if (expectedElement == null) {
                module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, list.span(),
                                "Cannot infer the element type of an empty list.")
                        .note("Declare the type, e.g. let names: List<string> = []").build());
                return new BoundExpression.Error(list.span());
            }
            return new BoundExpression.ListLiteral(List.of(), Types.list(expectedElement), list.span());
        }
        List<BoundExpression> elements = new ArrayList<>();
        for (Expression element : list.elements()) {
            elements.add(bindValue(element, expectedElement));
        }
        Type elementType = expectedElement != null ? expectedElement : unify(elements, list.span());
        if (elementType.isError()) {
            return new BoundExpression.Error(list.span());
        }
        List<BoundExpression> converted = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            converted.add(convert(elements.get(i), elementType, list.elements().get(i).span(), "the list element"));
        }
        return new BoundExpression.ListLiteral(converted, Types.list(elementType), list.span());
    }

    private Type unify(List<BoundExpression> elements, Span span) {
        Type result = null;
        boolean nullable = false;
        for (BoundExpression element : elements) {
            Type type = element.type();
            if (type.isError()) {
                return Types.ERROR;
            }
            if (type instanceof NullType) {
                nullable = true;
                continue;
            }
            if (result == null || Conversions.isAssignable(result, type)) {
                result = type;
            } else if (!Conversions.isAssignable(type, result)) {
                PrimitiveType promoted = promote(result, type);
                if (promoted == null) {
                    module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, element.span(),
                                    "List elements have incompatible types " + result.displayName() + " and "
                                            + type.displayName() + ".")
                            .note("Declare the element type, e.g. let values: List<any> = [...]").build());
                    return Types.ERROR;
                }
                result = promoted;
            }
        }
        if (result == null) {
            module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, span,
                    "Cannot infer the element type of a list containing only null.")
                    .note("Declare the type, e.g. let values: List<Player?> = [null]").build());
            return Types.ERROR;
        }
        return nullable ? Types.nullable(result) : result;
    }

    // ------------------------------------------------------------------- diagnostics helpers

    private void reportUnknownName(Identifier identifier) {
        String name = identifier.name();
        List<String> candidates = new ArrayList<>(scope.visibleNames());
        candidates.addAll(module.constants().keySet());
        candidates.addAll(module.functions().keySet());
        candidates.addAll(module.registry().namespaceMembers(""));
        Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.UNKNOWN_NAME, identifier.span(),
                "Unknown name '" + name + "'.").suggestions(Suggestions.closest(name, new LinkedHashSet<>(candidates), 3));
        if (event == null && (name.equals("player") || name.equals("event"))) {
            builder.note("'" + name + "' is only available inside event handlers that provide it.");
        } else if (event != null) {
            StringJoiner variables = new StringJoiner(", ");
            variables.add(EventDeclaration.EVENT_OBJECT_VARIABLE);
            event.variables().forEach(variable -> variables.add(variable.name()));
            builder.note("Variables available in 'event " + event.name() + "': " + variables);
        }
        module.report(builder.build());
    }

    private void reportUnknownNamespaceMember(String namespace, Identifier member) {
        module.report(module.diagnostic(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                        "Unknown member '" + member.name() + "' in '" + namespace + "'.")
                .suggestions(Suggestions.closest(member.name(), module.registry().namespaceMembers(namespace), 3)).build());
    }

    private void reportUnknownMember(ClassType type, Identifier member, BoundExpression receiver) {
        String name = member.name();
        if (event != null && !event.isCancellable() && CANCEL_MEMBERS.contains(name)
                && receiver instanceof BoundExpression.LocalLoad load && load.local().kind() == LocalSymbol.Kind.EVENT_OBJECT) {
            module.report(module.diagnostic(DiagnosticCode.EVENT_NOT_CANCELLABLE, member.span(),
                    "Event '" + event.name() + "' cannot be cancelled.").build());
            return;
        }
        module.report(module.diagnostic(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                        "Unknown member '" + name + "' on " + type.name() + ".")
                .suggestions(Suggestions.closest(name, module.members().memberNames(type), 3)).build());
    }

    private void reportNullableAccess(BoundExpression receiver, Expression receiverSyntax, String member) {
        String name = describeValue(receiver);
        String text = receiverSyntax.span().length() <= 40 ? module.file().text(receiverSyntax.span()) : "value";
        module.report(module.diagnostic(DiagnosticCode.NULLABLE_ACCESS, receiverSyntax.span(),
                        name + " may be null (type " + receiver.type().displayName() + ").")
                .note("Check for null first:\n    if " + text + " != null {\n        ...\n    }\nor use '?.' to skip null values: "
                        + text + "?." + member).build());
    }

    private void warnDeprecated(Deprecation deprecation, String display, Span span) {
        if (deprecation == null) {
            return;
        }
        Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.DEPRECATED, span, "'" + display + "' is deprecated.");
        if (!deprecation.message().isEmpty()) {
            builder.note(deprecation.message());
        }
        if (!deprecation.replacement().isEmpty()) {
            builder.note("Use:\n    " + deprecation.replacement());
        }
        if (!deprecation.removalVersion().isEmpty()) {
            builder.note("It will be removed in version " + deprecation.removalVersion() + ".");
        }
        module.report(builder.build());
    }

    private static String describeKind(LocalSymbol local) {
        return switch (local.kind()) {
            case VARIABLE, TEMPORARY -> "variable";
            case PARAMETER -> "parameter";
            case LOOP_VARIABLE -> "loop variable";
            case EVENT_VARIABLE -> "event variable";
            case EVENT_OBJECT -> "the event object";
        };
    }

    private String describeValue(BoundExpression expression) {
        BoundExpression inner = expression;
        while (inner instanceof BoundExpression.Conversion conversion) {
            inner = conversion.operand();
        }
        if (inner instanceof BoundExpression.LocalLoad load && load.local().kind() != LocalSymbol.Kind.TEMPORARY) {
            return "'" + load.local().name() + "'";
        }
        String text = module.file().text(expression.span());
        return text.length() <= 40 && !text.contains("\n") ? "'" + text + "'" : "the value";
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static BoundStatement errorStatement(Span span) {
        return new BoundStatement.ExpressionStatement(new BoundExpression.Error(span), span);
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current instanceof Expression.Parenthesized parenthesized) {
            current = parenthesized.inner();
        }
        return current;
    }
}
