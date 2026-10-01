package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.declaration.Parameter;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.doc.Deprecation;
import dev.tachyonscript.api.intrinsic.Intrinsics;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.FunctionType;
import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.MapType;
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
import dev.tachyonscript.language.syntax.Declaration;
import dev.tachyonscript.language.syntax.Expression;
import dev.tachyonscript.language.syntax.Identifier;
import dev.tachyonscript.language.syntax.Statement;
import dev.tachyonscript.language.syntax.UnaryOperator;
import dev.tachyonscript.language.util.Suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Binds and type-checks the body of one function, event handler, command, lambda or
 * initializer.
 *
 * <p>Expressions are checked bidirectionally: an expected type flows into literals,
 * templates, list and map literals and lambdas so that, for example, a string template
 * passed where a {@code Component} is expected becomes a pre-compiled message template, and
 * {@code players.filter(p => p.level > 10)} knows that {@code p} is a {@code Player}.
 * Narrowing facts from null checks and {@code is} tests are tracked in an immutable
 * {@link Flow} that is forked at branches.
 *
 * <p>Lambdas and scheduled blocks are bound by a nested binder whose root scope has a capture
 * boundary: every enclosing local they use is copied when the lambda is created, so only
 * locals that never change can be captured, and a local narrowed at that point stays narrowed
 * inside.
 */
final class BodyBinder {

    /** Resolves constants on demand (implemented by the module-level binder). */
    interface ConstantResolver {
        BoundExpression constant(ConstantSymbol constant, Span use);

        /** Binds the default value of a parameter or field in the declaring module's context. */
        BoundExpression defaultValue(Expression syntax, Type type, String what);
    }

    private static final Set<String> CANCEL_MEMBERS = Set.of("cancel", "uncancel", "cancelled", "isCancelled", "setCancelled");

    private final ModuleContext module;
    private final ConstantResolver constants;
    private final EventDeclaration event;
    private final String owner;
    private final RecordSymbol receiver;
    private final Set<String> assignedNames;
    private final BuiltinMembers builtins;
    private Type returnType;
    private final boolean inferReturn;
    private Type inferredReturn;
    private Scope scope;
    private Flow flow = Flow.EMPTY;
    private int loopDepth;
    /** Loop depth when entering a {@code finally} block, or -1 outside one. */
    private int finallyLoopFloor = -1;
    /** Loop depth when entering a switch case, or -1 outside one. */
    private int switchLoopFloor = -1;
    /** Whether this body runs after its creator finished (after/every/async blocks). */
    private boolean delayed;
    /** Whether this binds the module initializer (top-level variables must be declared before use). */
    private boolean initializer;

    BodyBinder(ModuleContext module, ConstantResolver constants, Scope root, Type returnType, EventDeclaration event,
               String owner) {
        this(module, constants, root, returnType, event, owner, null, Set.of());
    }

    BodyBinder(ModuleContext module, ConstantResolver constants, Scope root, Type returnType, EventDeclaration event,
               String owner, RecordSymbol receiver, Set<String> assignedNames) {
        this.module = module;
        this.constants = constants;
        this.scope = root;
        this.returnType = returnType;
        this.inferReturn = returnType == null;
        this.event = event;
        this.owner = owner;
        this.receiver = receiver;
        this.assignedNames = assignedNames;
        this.builtins = new BuiltinMembers(this);
    }

    ModuleContext module() {
        return module;
    }

    void initializerMode() {
        this.initializer = true;
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
                        .note("The statement before it always returns, breaks, continues or throws.").build());
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
                checkJump("break", brk.span());
                yield new BoundStatement.Break(brk.span());
            }
            case Statement.Continue cont -> {
                checkJump("continue", cont.span());
                yield new BoundStatement.Continue(cont.span());
            }
            case Statement.Switch sw -> bindSwitch(sw);
            case Statement.Try tryStatement -> bindTry(tryStatement);
            case Statement.Throw throwStatement -> bindThrow(throwStatement);
            case Statement.Schedule schedule -> bindSchedule(schedule);
        };
    }

    private void checkJump(String keyword, Span span) {
        if (loopDepth == 0) {
            module.error(DiagnosticCode.JUMP_OUTSIDE_LOOP, span, "'" + keyword + "' can only be used inside a loop.");
        } else if (loopDepth == finallyLoopFloor) {
            module.error(DiagnosticCode.JUMP_OUT_OF_FINALLY, span, "'" + keyword + "' cannot leave a 'finally' block.");
        } else if (keyword.equals("break") && loopDepth == switchLoopFloor) {
            module.report(module.diagnostic(DiagnosticCode.JUMP_OUTSIDE_LOOP, span,
                            "'break' cannot be used to leave a switch case.")
                    .note("A case never falls through to the next one, so it needs no 'break'.").build());
        }
    }

    private BoundStatement bindLocal(Statement.LocalVariable declaration) {
        String name = declaration.name().name();
        Type declared = declaration.type() != null ? module.types().resolve(declaration.type(), false) : null;
        BoundExpression initializerValue;
        Type type;
        if (declaration.initializer() == null) {
            module.report(module.diagnostic(DiagnosticCode.MISSING_INITIALIZER, declaration.span(),
                            "Variable '" + name + "' must be initialized.")
                    .note("Example: " + (declaration.mutable() ? "var " : "let ") + name
                            + (declaration.type() == null ? ": int" : "") + " = 0").build());
            initializerValue = new BoundExpression.Error(declaration.span());
            type = declared != null ? declared : Types.ERROR;
        } else if (declared != null) {
            initializerValue = convert(bindValue(declaration.initializer(), declared), declared,
                    declaration.initializer().span(), "variable '" + name + "'");
            type = declared;
        } else {
            initializerValue = bindValue(declaration.initializer(), null);
            type = initializerValue.type();
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
            LocalSymbol outer = scope.peek(name);
            if (outer != null) {
                module.report(module.diagnostic(DiagnosticCode.SHADOWED_VARIABLE, declaration.name().span(),
                                "'" + name + "' shadows " + describeKind(outer) + " with the same name.")
                        .label(outer.declaration(), "declared here").build());
            }
        }
        LocalSymbol local = new LocalSymbol(name, type, declaration.mutable(), LocalSymbol.Kind.VARIABLE,
                declaration.name().span(), null);
        if (!declaration.mutable() && initializerValue instanceof BoundExpression.Literal literal
                && literal.value() instanceof String text) {
            local.knownString(text);
        }
        scope.declare(local);
        narrowAfterAssignment(local, initializerValue);
        return new BoundStatement.LocalDeclaration(local, initializerValue, declaration.span());
    }

    private void narrowAfterAssignment(LocalSymbol local, BoundExpression value) {
        flow = flow.without(local);
        Type valueType = value.type();
        if (local.type().isNullable() && !valueType.isNullable() && !valueType.isError()) {
            flow = flow.with(local, local.type().nonNullable());
        }
    }

    // ------------------------------------------------------------------- assignments

    private BoundStatement bindAssignment(Statement.Assignment assignment) {
        Expression target = unwrap(assignment.target());
        AssignmentOperator operator = assignment.operator();
        Span span = assignment.span();
        return switch (target) {
            case Expression.Name name -> assignName(name, operator, assignment.value(), span);
            case Expression.Member member -> assignMember(member, operator, assignment.value(), span);
            case Expression.Index index -> assignIndex(index, operator, assignment.value(), span);
            default -> {
                bindValue(assignment.value(), null);
                module.error(DiagnosticCode.INVALID_ASSIGNMENT_TARGET, target.span(), "Cannot assign to this expression.");
                yield new BoundStatement.ExpressionStatement(new BoundExpression.Error(span), span);
            }
        };
    }

    private BoundStatement assignName(Expression.Name name, AssignmentOperator operator, Expression valueSyntax, Span span) {
        LocalSymbol local = scope.lookup(name.name());
        if (local != null) {
            return assignLocal(local, name, operator, valueSyntax, span);
        }
        if (receiver != null && receiver.field(name.name()).isPresent()) {
            bindValue(valueSyntax, null);
            module.report(module.diagnostic(DiagnosticCode.ASSIGN_TO_READONLY, name.span(),
                            "Cannot assign to field '" + name.name() + "': records cannot be changed.")
                    .note("Create a new record with the changed value instead.").build());
            return errorStatement(span);
        }
        GlobalSymbol global = module.globals().get(name.name());
        if (global == null && module.importedNames().get(name.name()) instanceof GlobalSymbol imported) {
            global = imported;
        }
        if (global != null) {
            return assignGlobal(global, name.span(), operator, valueSyntax, span);
        }
        bindValue(valueSyntax, null);
        if (module.constants().containsKey(name.name())) {
            module.error(DiagnosticCode.ASSIGN_TO_READONLY, name.span(), "Cannot assign to constant '" + name.name() + "'.");
        } else {
            reportUnknownName(name.identifier());
        }
        return errorStatement(span);
    }

    private BoundStatement assignLocal(LocalSymbol local, Expression.Name name, AssignmentOperator operator,
                                       Expression valueSyntax, Span span) {
        if (!local.isMutable()) {
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.ASSIGN_TO_READONLY, name.span(),
                    "Cannot assign to " + describeKind(local) + " '" + local.name() + "'.");
            if (local.kind() == LocalSymbol.Kind.VARIABLE) {
                builder.note("It is declared with 'let'. Use 'var' for a variable that can change.")
                        .label(local.declaration(), "declared here");
            } else if (local.kind() == LocalSymbol.Kind.PARAMETER) {
                builder.note("Parameters are read-only. Copy the value into a variable: var copy = " + local.name());
            } else if (local.kind() == LocalSymbol.Kind.CAPTURE) {
                builder.note("A lambda or scheduled block gets a copy of '" + local.name() + "' made when it was "
                        + "created, so it cannot change the original. Keep changing state in a top-level 'var' or a map.");
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

    private BoundStatement assignGlobal(GlobalSymbol global, Span nameSpan, AssignmentOperator operator,
                                        Expression valueSyntax, Span span) {
        if (global.isPlayerData()) {
            bindValue(valueSyntax, null);
            module.report(module.diagnostic(DiagnosticCode.INVALID_ASSIGNMENT_TARGET, nameSpan,
                            "'" + global.name() + "' is saved per player; assign it on a player.")
                    .note("Example: player." + global.name() + " = ...").build());
            return errorStatement(span);
        }
        if (!global.isMutable()) {
            bindValue(valueSyntax, null);
            module.report(module.diagnostic(DiagnosticCode.ASSIGN_TO_READONLY, nameSpan,
                            "Cannot assign to '" + global.name() + "': it is declared with 'let'.")
                    .label(global.declaration(), "declared here")
                    .note("Use 'var' for a top-level variable that can change.").build());
            return errorStatement(span);
        }
        Type type = global.type();
        if (operator.isCompound()) {
            boolean atomic = (operator == AssignmentOperator.ADD || operator == AssignmentOperator.SUBTRACT)
                    && (type == PrimitiveType.INT || type == PrimitiveType.LONG || type == PrimitiveType.DOUBLE);
            if (atomic) {
                BoundExpression delta = convert(bindValue(valueSyntax, type), type, valueSyntax.span(),
                        "the change of '" + global.name() + "'");
                if (operator == AssignmentOperator.SUBTRACT && !delta.type().isError()) {
                    delta = new BoundExpression.Negate(type.representation(), delta, type, delta.span());
                }
                return new BoundStatement.GlobalAdd(global, delta, span);
            }
            BoundExpression current = new BoundExpression.GlobalLoad(global, type, nameSpan);
            BoundExpression right = bindValue(valueSyntax, type);
            BoundExpression value = convert(binaryOperation(operator.binary(), current, right, span), type,
                    valueSyntax.span(), "'" + global.name() + "'");
            return new BoundStatement.GlobalStore(global, value, span);
        }
        BoundExpression value = convert(bindValue(valueSyntax, type), type, valueSyntax.span(), "'" + global.name() + "'");
        return new BoundStatement.GlobalStore(global, value, span);
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
        if (target instanceof PathResult.Module imported) {
            if (imported.module().member(name) instanceof GlobalSymbol global) {
                return assignGlobal(global, member.member().span(), operator, valueSyntax, span);
            }
            bindValue(valueSyntax, null);
            module.error(DiagnosticCode.ASSIGN_TO_READONLY, member.member().span(),
                    "'" + imported.alias() + "." + name + "' is not a variable that can be assigned.");
            return errorStatement(span);
        }
        BoundExpression receiverValue = expectValue(target, member.target());
        if (receiverValue.type().isError()) {
            bindValue(valueSyntax, null);
            return errorStatement(span);
        }
        if (receiverValue.type().isNullable()) {
            bindValue(valueSyntax, null);
            reportNullableAccess(receiverValue, member.target(), name);
            return errorStatement(span);
        }
        if (receiverValue.type() instanceof ClassType type) {
            MemberLookup.Result result = module.members().lookup(type, name);
            if (result.property() != null) {
                return assignProperty(receiverValue, result.property(), type.name() + "." + name, operator, valueSyntax,
                        member.member().span(), span);
            }
            GlobalSymbol playerData = playerData(type, name);
            if (playerData != null) {
                return assignPlayerData(playerData, receiverValue, operator, valueSyntax, span);
            }
            bindValue(valueSyntax, null);
            if (!result.methods().isEmpty()) {
                module.error(DiagnosticCode.ASSIGN_TO_READONLY, member.member().span(),
                        "Cannot assign to method '" + name + "' of " + type.name() + ".");
            } else if (module.record(type) != null && module.record(type).field(name).isPresent()) {
                module.report(module.diagnostic(DiagnosticCode.ASSIGN_TO_READONLY, member.member().span(),
                                "Cannot assign to field '" + name + "': records cannot be changed.")
                        .note("Create a new record with the changed value instead.").build());
            } else {
                reportUnknownMember(type, member.member(), receiverValue);
            }
            return errorStatement(span);
        }
        bindValue(valueSyntax, null);
        module.error(DiagnosticCode.ASSIGN_TO_READONLY, member.member().span(),
                "Cannot assign to '" + name + "' of a value of type " + receiverValue.type().displayName() + ".");
        return errorStatement(span);
    }

    private BoundStatement assignPlayerData(GlobalSymbol global, BoundExpression owner, AssignmentOperator operator,
                                            Expression valueSyntax, Span span) {
        Type type = global.type();
        if ((operator == AssignmentOperator.ADD || operator == AssignmentOperator.SUBTRACT)
                && (type == PrimitiveType.INT || type == PrimitiveType.LONG || type == PrimitiveType.DOUBLE)) {
            BoundExpression delta = convert(bindValue(valueSyntax, type), type, valueSyntax.span(),
                    "the change of '" + global.name() + "'");
            if (operator == AssignmentOperator.SUBTRACT && !delta.type().isError()) {
                delta = new BoundExpression.Negate(type.representation(), delta, type, delta.span());
            }
            return new BoundStatement.PlayerDataAdd(global, owner, delta, span);
        }
        List<BoundStatement> prefix = new ArrayList<>();
        BoundExpression value;
        if (operator.isCompound()) {
            owner = hoist(owner, prefix);
            BoundExpression current = new BoundExpression.PlayerDataLoad(global, owner, type, span);
            value = binaryOperation(operator.binary(), current, bindValue(valueSyntax, type), span);
        } else {
            value = bindValue(valueSyntax, type);
        }
        value = convert(value, type, valueSyntax.span(), "'" + global.name() + "'");
        BoundStatement store = new BoundStatement.PlayerDataStore(global, owner, value, span);
        if (prefix.isEmpty()) {
            return store;
        }
        prefix.add(store);
        return new BoundStatement.Block(prefix, span);
    }

    private BoundStatement assignProperty(BoundExpression receiverValue, PropertyDeclaration property, String display,
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
        BoundExpression target = receiverValue;
        if (operator.isCompound()) {
            if (target != null) {
                target = hoist(target, prefix);
            }
            List<BoundExpression> getterArguments = target == null ? List.of() : List.of(target);
            BoundExpression current = new BoundExpression.NativeCall(property.getter(), getterArguments, type, nameSpan);
            BoundExpression right = bindValue(valueSyntax, type);
            value = binaryOperation(operator.binary(), current, right, span);
        } else {
            value = bindValue(valueSyntax, type);
        }
        value = convert(value, type, valueSyntax.span(), "property '" + display + "'");
        BoundStatement assignment = new BoundStatement.PropertyAssignment(target, property.setter().orElseThrow(), value, span);
        if (prefix.isEmpty()) {
            return assignment;
        }
        prefix.add(assignment);
        return new BoundStatement.Block(prefix, span);
    }

    private BoundStatement assignIndex(Expression.Index index, AssignmentOperator operator, Expression valueSyntax, Span span) {
        BoundExpression container = bindValue(index.target(), null);
        if (container.type().isError()) {
            bindValue(index.index(), null);
            bindValue(valueSyntax, null);
            return errorStatement(span);
        }
        if (container.type() instanceof MapType mapType) {
            return builtins.assignMapEntry(container, mapType, index, operator, valueSyntax, span);
        }
        if (!(container.type() instanceof ListType listType)) {
            bindValue(index.index(), null);
            bindValue(valueSyntax, null);
            if (container.type().isNullable()) {
                reportNullableAccess(container, index.target(), "[...]");
            } else {
                module.error(DiagnosticCode.INVALID_OPERATOR, index.span(),
                        "Cannot index a value of type " + container.type().displayName() + ".");
            }
            return errorStatement(span);
        }
        BoundExpression position = convert(bindValue(index.index(), PrimitiveType.INT), PrimitiveType.INT,
                index.index().span(), "list index");
        List<BoundStatement> prefix = new ArrayList<>();
        BoundExpression value;
        BoundExpression list = container;
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
    BoundExpression hoist(BoundExpression expression, List<BoundStatement> prefix) {
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
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.UNUSED_EXPRESSION, statement.span(),
                    "The result of this expression is not used.");
            if (expression instanceof BoundExpression.Lambda) {
                builder.note("A lambda does nothing until it is called or passed to a function.");
            }
            module.report(builder.build());
        }
        return new BoundStatement.ExpressionStatement(expression, statement.span());
    }

    private static boolean hasEffect(BoundExpression expression) {
        return switch (expression) {
            case BoundExpression.NativeCall call -> switch (call.target().kind()) {
                case FUNCTION, METHOD, SETTER -> true;
                case INTRINSIC -> !call.target().effects().isEmpty();
                default -> false;
            };
            case BoundExpression.FunctionCall ignored -> true;
            case BoundExpression.ClosureCall ignored -> true;
            case BoundExpression.ListAdd ignored -> true;
            case BoundExpression.SafeAccess access -> hasEffect(access.access());
            case BoundExpression.Conversion conversion -> hasEffect(conversion.operand());
            case BoundExpression.Let let -> hasEffect(let.body()) || hasEffect(let.value());
            case BoundExpression.Conditional conditional -> hasEffect(conditional.whenTrue())
                    || hasEffect(conditional.whenFalse());
            case BoundExpression.Error ignored -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------- control flow

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
        flow = merge(afterThen, thenCompletes, afterElse, elseCompletes, before);
        return new BoundStatement.If(condition.expression(), thenBranch, elseBranch, statement.span());
    }

    private static Flow merge(Flow a, boolean aCompletes, Flow b, boolean bCompletes, Flow fallback) {
        if (aCompletes && bCompletes) {
            return a.intersect(b);
        }
        if (aCompletes) {
            return a;
        }
        if (bCompletes) {
            return b;
        }
        return fallback;
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
                if (statement.second() != null) {
                    module.error(DiagnosticCode.NOT_ITERABLE, statement.second().span(),
                            "A range gives one value per step; use one loop variable.");
                }
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
            if (collection.type() instanceof MapType mapType) {
                return builtins.forEachEntry(statement, collection, mapType);
            }
            Type elementType = Types.ERROR;
            if (collection.type() instanceof ListType list) {
                elementType = list.element();
            } else if (!collection.type().isError()) {
                if (collection.type().isNullable()) {
                    reportNullableAccess(collection, iterable, "for ... in");
                } else {
                    module.report(module.diagnostic(DiagnosticCode.NOT_ITERABLE, iterable.span(),
                                    "Cannot iterate over a value of type " + collection.type().displayName() + ".")
                            .note("'for' loops iterate over lists, maps and ranges, e.g. for i in 1..10 { }").build());
                }
            }
            if (statement.second() != null && !elementType.isError()) {
                module.report(module.diagnostic(DiagnosticCode.NOT_ITERABLE, statement.second().span(),
                                "Two loop variables need a map; a list gives one value per step.")
                        .note("For the position in a list, loop over the indices: for i in 0..<list.size { }").build());
            }
            LocalSymbol variable = declareLoopVariable(statement.variable(), elementType);
            loopDepth++;
            BoundStatement body = bindBlock(statement.body(), false);
            loopDepth--;
            if (collection.type() instanceof ListType) {
                // The loop walks a snapshot taken when it starts: the body (or a handler on another
                // thread) may add or remove elements without skipping, repeating or overrunning any.
                collection = BuiltinMembers.intrinsic(Intrinsics.LIST_SNAPSHOT, collection.type(), statement.span(), collection);
            }
            return new BoundStatement.ForEach(variable, collection, body, statement.span());
        } finally {
            reportUnused(scope);
            scope = scope.parent();
            flow = before;
        }
    }

    /** Binds the body of a loop whose variables the caller declared (used by map iteration). */
    BoundStatement bindLoopBody(Statement.Block body) {
        loopDepth++;
        try {
            return bindBlock(body, false);
        } finally {
            loopDepth--;
        }
    }

    Scope enterScope() {
        scope = new Scope(scope);
        return scope;
    }

    void leaveScope() {
        reportUnused(scope);
        scope = scope.parent();
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

    LocalSymbol declareLoopVariable(Identifier name, Type type) {
        LocalSymbol outer = scope.peek(name.name());
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
    static Set<String> assignedNames(Statement statement) {
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
            case Statement.Switch sw -> {
                sw.cases().forEach(c -> collectAssigned(c.body(), names));
                if (sw.defaultBody() != null) {
                    collectAssigned(sw.defaultBody(), names);
                }
            }
            case Statement.Try t -> {
                collectAssigned(t.body(), names);
                if (t.catchBody() != null) {
                    collectAssigned(t.catchBody(), names);
                }
                if (t.finallyBody() != null) {
                    collectAssigned(t.finallyBody(), names);
                }
            }
            case Statement.Schedule schedule -> collectAssigned(schedule.body(), names);
            default -> {
            }
        }
    }

    /**
     * Every name assigned in {@code block}, including inside lambdas (used to decide which
     * {@code var}s a lambda may capture).
     */
    static Set<String> allAssignedNames(Statement.Block block) {
        Set<String> names = new HashSet<>();
        collectAllAssigned(block, names);
        return names;
    }

    private static void collectAllAssigned(Statement statement, Set<String> names) {
        switch (statement) {
            case Statement.Block block -> block.statements().forEach(inner -> collectAllAssigned(inner, names));
            case Statement.Assignment assignment -> {
                if (unwrap(assignment.target()) instanceof Expression.Name name) {
                    names.add(name.name());
                }
                collectAllAssigned(assignment.value(), names);
            }
            case Statement.LocalVariable local -> {
                if (local.initializer() != null) {
                    collectAllAssigned(local.initializer(), names);
                }
            }
            case Statement.ExpressionStatement expression -> collectAllAssigned(expression.expression(), names);
            case Statement.If anIf -> {
                collectAllAssigned(anIf.condition(), names);
                collectAllAssigned(anIf.thenBlock(), names);
                if (anIf.elseBranch() != null) {
                    collectAllAssigned(anIf.elseBranch(), names);
                }
            }
            case Statement.While loop -> {
                collectAllAssigned(loop.condition(), names);
                collectAllAssigned(loop.body(), names);
            }
            case Statement.For loop -> {
                collectAllAssigned(loop.iterable(), names);
                collectAllAssigned(loop.body(), names);
            }
            case Statement.Return ret -> {
                if (ret.value() != null) {
                    collectAllAssigned(ret.value(), names);
                }
            }
            case Statement.Switch sw -> {
                sw.cases().forEach(c -> collectAllAssigned(c.body(), names));
                if (sw.defaultBody() != null) {
                    collectAllAssigned(sw.defaultBody(), names);
                }
            }
            case Statement.Try t -> {
                collectAllAssigned(t.body(), names);
                if (t.catchBody() != null) {
                    collectAllAssigned(t.catchBody(), names);
                }
                if (t.finallyBody() != null) {
                    collectAllAssigned(t.finallyBody(), names);
                }
            }
            case Statement.Throw t -> collectAllAssigned(t.value(), names);
            case Statement.Schedule schedule -> collectAllAssigned(schedule.body(), names);
            default -> {
            }
        }
    }

    private static void collectAllAssigned(Expression expression, Set<String> names) {
        switch (expression) {
            case Expression.Lambda lambda -> {
                if (lambda.blockBody() != null) {
                    collectAllAssigned(lambda.blockBody(), names);
                } else {
                    collectAllAssigned(lambda.expressionBody(), names);
                }
            }
            case Expression.Call call -> {
                collectAllAssigned(call.callee(), names);
                call.arguments().forEach(argument -> collectAllAssigned(argument, names));
            }
            case Expression.Member member -> collectAllAssigned(member.target(), names);
            case Expression.Binary binary -> {
                collectAllAssigned(binary.left(), names);
                collectAllAssigned(binary.right(), names);
            }
            case Expression.Unary unary -> collectAllAssigned(unary.operand(), names);
            case Expression.Parenthesized parenthesized -> collectAllAssigned(parenthesized.inner(), names);
            case Expression.Conditional conditional -> {
                collectAllAssigned(conditional.condition(), names);
                collectAllAssigned(conditional.whenTrue(), names);
                collectAllAssigned(conditional.whenFalse(), names);
            }
            case Expression.ListLiteral list -> list.elements().forEach(element -> collectAllAssigned(element, names));
            case Expression.MapLiteral map -> map.entries().forEach(entry -> {
                collectAllAssigned(entry.key(), names);
                collectAllAssigned(entry.value(), names);
            });
            case Expression.Index index -> {
                collectAllAssigned(index.target(), names);
                collectAllAssigned(index.index(), names);
            }
            default -> {
            }
        }
    }

    private BoundStatement bindReturn(Statement.Return statement) {
        Span span = statement.span();
        if (finallyLoopFloor >= 0) {
            module.error(DiagnosticCode.JUMP_OUT_OF_FINALLY, span, "'return' cannot leave a 'finally' block.");
        }
        if (inferReturn) {
            if (statement.value() == null) {
                if (inferredReturn != null && inferredReturn != PrimitiveType.VOID) {
                    module.error(DiagnosticCode.MISSING_RETURN_VALUE, span,
                            "This lambda returns values of type " + inferredReturn.displayName() + " elsewhere.");
                }
                inferredReturn = inferredReturn == null ? PrimitiveType.VOID : inferredReturn;
                return new BoundStatement.Return(null, span);
            }
            BoundExpression value = inferredReturn != null && inferredReturn != PrimitiveType.VOID
                    ? convert(bindValue(statement.value(), inferredReturn), inferredReturn, statement.value().span(),
                    "the return value of " + owner)
                    : bindValue(statement.value(), null);
            if (inferredReturn == null) {
                inferredReturn = value.type() instanceof NullType ? Types.nullable(Types.ANY) : value.type();
            }
            return new BoundStatement.Return(value, span);
        }
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

    /** The return type after binding: declared, or inferred from the returns of a lambda. */
    Type effectiveReturnType() {
        if (!inferReturn) {
            return returnType;
        }
        return inferredReturn == null ? PrimitiveType.VOID : inferredReturn;
    }

    // ------------------------------------------------------------------- switch

    private BoundStatement bindSwitch(Statement.Switch statement) {
        BoundExpression subject = bindValue(statement.subject(), null);
        List<BoundStatement> prefix = new ArrayList<>();
        BoundExpression value = hoist(subject, prefix);
        Flow before = flow;
        Set<Object> seen = new HashSet<>();
        List<BoundExpression> conditions = new ArrayList<>();
        List<Statement> bodies = new ArrayList<>();
        for (Statement.SwitchCase switchCase : statement.cases()) {
            conditions.add(caseCondition(value, switchCase.labels(), seen));
            bodies.add(switchCase.body());
        }
        // Build the if/else-if chain from the last case backwards.
        BoundStatement chain = null;
        int savedFloor = switchLoopFloor;
        switchLoopFloor = loopDepth;
        try {
            List<BoundStatement> boundBodies = new ArrayList<>();
            List<Flow> flows = new ArrayList<>();
            for (Statement body : bodies) {
                flow = before;
                boundBodies.add(bindCaseBody(body));
                flows.add(flow);
            }
            BoundStatement defaultBody = null;
            Flow defaultFlow = before;
            if (statement.defaultBody() != null) {
                flow = before;
                defaultBody = bindCaseBody(statement.defaultBody());
                defaultFlow = flow;
            }
            chain = defaultBody;
            for (int i = conditions.size() - 1; i >= 0; i--) {
                chain = new BoundStatement.If(conditions.get(i), boundBodies.get(i), chain, statement.span());
            }
            Flow merged = defaultBody != null ? defaultFlow : before;
            for (Flow caseFlow : flows) {
                merged = merged.intersect(caseFlow);
            }
            flow = merged.withoutNames(assignedNames(statement));
        } finally {
            switchLoopFloor = savedFloor;
        }
        if (chain != null) {
            prefix.add(chain);
        }
        return new BoundStatement.Block(prefix, statement.span());
    }

    private BoundStatement bindCaseBody(Statement body) {
        if (body instanceof Statement.Block block) {
            return bindBlock(block, true);
        }
        scope = new Scope(scope);
        try {
            return bindStatement(body);
        } finally {
            reportUnused(scope);
            scope = scope.parent();
        }
    }

    private BoundExpression caseCondition(BoundExpression subject, List<Expression> labels, Set<Object> seen) {
        BoundExpression condition = null;
        for (Expression labelSyntax : labels) {
            BoundExpression label = bindValue(labelSyntax, subject.type().nonNullable());
            Object constant = label instanceof BoundExpression.KeyedConstant keyed ? keyed.key()
                    : ConstantEvaluator.evaluate(label, ConstantEvaluator.SILENT);
            if (constant != ConstantEvaluator.NOT_CONSTANT && !seen.add(Objects.requireNonNullElse(constant, "null"))) {
                module.report(module.diagnostic(DiagnosticCode.DUPLICATE_CASE, labelSyntax.span(),
                        "This value is already handled by an earlier case.").build());
            }
            BoundExpression test = label instanceof BoundExpression.Literal literal && literal.value() == null
                    ? new BoundExpression.NullCheck(subject, true, labelSyntax.span())
                    : binaryOperation(BinaryOperator.EQUAL, subject, label, labelSyntax.span());
            condition = condition == null ? test : new BoundExpression.Logical(false, condition, test, labelSyntax.span());
        }
        return condition == null ? new BoundExpression.Literal(false, PrimitiveType.BOOL, subject.span()) : condition;
    }

    private BoundExpression bindSwitchExpression(Expression.Switch syntax, Type expected) {
        BoundExpression subject = bindValue(syntax.subject(), null);
        LocalSymbol temporary = module.newTemporary(subject.type(), subject.span());
        BoundExpression value = new BoundExpression.LocalLoad(temporary, subject.type(), subject.span());
        Set<Object> seen = new HashSet<>();
        List<BoundExpression> conditions = new ArrayList<>();
        List<BoundExpression> values = new ArrayList<>();
        for (Expression.SwitchArm arm : syntax.arms()) {
            conditions.add(caseCondition(value, arm.labels(), seen));
            values.add(bindValue(arm.value(), expected));
        }
        if (syntax.defaultValue() == null) {
            module.report(module.diagnostic(DiagnosticCode.SWITCH_NOT_EXHAUSTIVE, syntax.span(),
                            "A switch used as a value needs a 'default' case.")
                    .note("Add: default -> value").build());
            return new BoundExpression.Error(syntax.span());
        }
        BoundExpression fallback = bindValue(syntax.defaultValue(), expected);
        List<BoundExpression> all = new ArrayList<>(values);
        all.add(fallback);
        Type type = expected != null ? expected : unifyBranches(all, syntax.span());
        if (type.isError()) {
            return new BoundExpression.Error(syntax.span());
        }
        BoundExpression result = convert(fallback, type, syntax.defaultValue().span(), "the switch value");
        for (int i = conditions.size() - 1; i >= 0; i--) {
            BoundExpression armValue = convert(values.get(i), type, syntax.arms().get(i).value().span(), "the switch value");
            result = new BoundExpression.Conditional(conditions.get(i), armValue, result, type, syntax.span());
        }
        return new BoundExpression.Let(temporary, subject, result, syntax.span());
    }

    // ------------------------------------------------------------------- try / throw

    private BoundStatement bindTry(Statement.Try statement) {
        Flow before = flow;
        Set<String> assigned = assignedNames(statement);
        BoundStatement.Block body = bindBlock(statement.body(), true);
        LocalSymbol catchLocal = null;
        BoundStatement.Block catchBody = null;
        if (statement.catchBody() != null) {
            flow = before.withoutNames(assigned);
            scope = new Scope(scope);
            String name = statement.catchVariable() != null ? statement.catchVariable().name() : "$error";
            Span declared = statement.catchVariable() != null ? statement.catchVariable().span() : statement.span();
            catchLocal = new LocalSymbol(name, Types.EXCEPTION, false, LocalSymbol.Kind.IMPLICIT, declared, null);
            if (statement.catchVariable() != null) {
                LocalSymbol outer = scope.peek(name);
                if (outer != null) {
                    module.report(module.diagnostic(DiagnosticCode.SHADOWED_VARIABLE, declared,
                                    "'" + name + "' shadows " + describeKind(outer) + " with the same name.")
                            .label(outer.declaration(), "declared here").build());
                }
                scope.declare(catchLocal);
            }
            catchBody = bindBlock(statement.catchBody(), false);
            scope = scope.parent();
        }
        BoundStatement.Block finallyBody = null;
        if (statement.finallyBody() != null) {
            flow = before.withoutNames(assigned);
            int savedFloor = finallyLoopFloor;
            finallyLoopFloor = loopDepth;
            try {
                finallyBody = bindBlock(statement.finallyBody(), true);
            } finally {
                finallyLoopFloor = savedFloor;
            }
        }
        flow = before.withoutNames(assigned);
        return new BoundStatement.Try(body, catchLocal, catchBody, finallyBody, statement.span());
    }

    private BoundStatement bindThrow(Statement.Throw statement) {
        BoundExpression value = bindValue(statement.value(), null);
        Type type = value.type();
        if (type.isError()) {
            return new BoundStatement.Throw(value, statement.span());
        }
        if (type == Types.STRING || type == Types.EXCEPTION) {
            return new BoundStatement.Throw(value, statement.span());
        }
        if (type.nonNullable() == Types.STRING || type.nonNullable() == Types.EXCEPTION) {
            reportNullableAccess(value, statement.value(), "throw");
            return new BoundStatement.Throw(new BoundExpression.Error(value.span()), statement.span());
        }
        module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, statement.value().span(),
                        "'throw' needs a message (string) or a caught Error.")
                .expectedReceived("string or Error", type.displayName())
                .note("Example: throw \"Not enough money\"").build());
        return new BoundStatement.Throw(new BoundExpression.Error(value.span()), statement.span());
    }

    // ------------------------------------------------------------------- scheduling

    private BoundStatement bindSchedule(Statement.Schedule statement) {
        Span span = statement.span();
        BoundExpression delay = null;
        if (statement.delay() != null) {
            delay = convert(bindValue(statement.delay(), PrimitiveType.DURATION), PrimitiveType.DURATION,
                    statement.delay().span(), "the delay");
        }
        BoundExpression ownerValue = null;
        if (statement.owner() != null) {
            ClassType entity = requireStandardType("Entity", statement.owner().span());
            ownerValue = entity == null ? new BoundExpression.Error(statement.owner().span())
                    : convert(bindValue(statement.owner(), entity), entity, statement.owner().span(), "the owner of the block");
        }
        List<Type> parameterTypes = new ArrayList<>();
        List<String> parameterNames = new ArrayList<>();
        if (statement.kind() == Statement.ScheduleKind.EVERY) {
            ClassType task = requireStandardType("Task", span);
            if (task != null) {
                parameterTypes.add(task);
                parameterNames.add("task");
            }
        }
        String what = statement.kind().name().toLowerCase(java.util.Locale.ROOT);
        BoundExpression block = bindLambdaBody(parameterNames, parameterTypes, List.of(), PrimitiveType.VOID, null,
                statement.body(), span, true, "'" + what + "' block");
        NativeDeclaration target = switch (statement.kind()) {
            case AFTER -> ownerValue != null ? Intrinsics.SCHEDULE_AFTER_FOR : Intrinsics.SCHEDULE_AFTER;
            case EVERY -> ownerValue != null ? Intrinsics.SCHEDULE_EVERY_FOR : Intrinsics.SCHEDULE_EVERY;
            case ASYNC -> Intrinsics.SCHEDULE_ASYNC;
            case SYNC -> Intrinsics.SCHEDULE_SYNC;
        };
        List<BoundExpression> arguments = new ArrayList<>();
        if (ownerValue != null) {
            arguments.add(ownerValue);
        }
        if (delay != null) {
            if (delay instanceof BoundExpression.Literal literal && literal.value() instanceof Long millis
                    && statement.kind() == Statement.ScheduleKind.EVERY && millis < 50) {
                module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, delay.span(),
                        "A repeating block needs an interval of at least 1 tick (50 ms).").build());
            }
            arguments.add(new BoundExpression.Conversion(ConversionKind.REINTERPRET, delay, PrimitiveType.LONG, delay.span()));
        }
        arguments.add(block);
        if (arguments.stream().anyMatch(argument -> argument.type().isError())) {
            return errorStatement(span);
        }
        return new BoundStatement.ExpressionStatement(new BoundExpression.NativeCall(target, arguments,
                PrimitiveType.VOID, span), span);
    }

    ClassType requireStandardType(String name, Span span) {
        ClassType type = module.standardType(name);
        if (type == null) {
            module.error(DiagnosticCode.MISSING_STANDARD_TYPE, span,
                    "This needs the type '" + name + "' of the standard library, which is not available.");
        }
        return type;
    }

    // ------------------------------------------------------------------- lambdas

    /** Binds a lambda expression against an expected type ({@code null} when unknown). */
    private BoundExpression bindLambda(Expression.Lambda syntax, Type expected) {
        FunctionType target = expected != null && expected.nonNullable() instanceof FunctionType function ? function : null;
        return bindLambdaAgainst(syntax, target == null ? null : target.parameters(), target == null ? null
                : target.returnType(), target != null);
    }

    /**
     * Binds a lambda with known parameter types. {@code returnType} {@code null} means the
     * result type is inferred from the body (for {@code list.map(x => ...)}).
     */
    BoundExpression bindLambdaAgainst(Expression.Lambda syntax, List<Type> expectedParameters, Type returnType,
                                      boolean haveExpectation) {
        List<String> names = new ArrayList<>();
        List<Type> types = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        boolean failed = false;
        if (expectedParameters != null && expectedParameters.size() != syntax.parameters().size()) {
            module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, syntax.span(),
                            "This lambda takes " + syntax.parameters().size() + " parameter"
                                    + (syntax.parameters().size() == 1 ? "" : "s") + ", but "
                                    + expectedParameters.size() + (expectedParameters.size() == 1 ? " is" : " are")
                                    + " expected here.")
                    .note("Expected: " + Types.function(expectedParameters, returnType == null ? Types.ANY : returnType)
                            .displayName().replace(": any", ": ...")).build());
            failed = true;
        }
        for (int i = 0; i < syntax.parameters().size(); i++) {
            Expression.LambdaParameter parameter = syntax.parameters().get(i);
            names.add(parameter.name().name());
            spans.add(parameter.name().span());
            Type type;
            if (parameter.type() != null) {
                type = module.types().resolve(parameter.type(), false);
                if (!failed && expectedParameters != null && !type.isError() && !type.equals(expectedParameters.get(i))) {
                    module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, parameter.type().span(),
                                    "Lambda parameter '" + parameter.name().name() + "' has the wrong type.")
                            .expectedReceived(expectedParameters.get(i).displayName(), type.displayName()).build());
                    type = Types.ERROR;
                }
            } else if (!failed && expectedParameters != null) {
                type = expectedParameters.get(i);
            } else {
                if (!failed) {
                    module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, parameter.name().span(),
                                    "Cannot infer the type of lambda parameter '" + parameter.name().name() + "'.")
                            .note("Write the type: (" + parameter.name().name() + ": Player) => ...").build());
                }
                type = Types.ERROR;
            }
            types.add(type);
        }
        Type resultType = haveExpectation ? returnType : null;
        return bindLambdaBody(names, types, spans, resultType, syntax.expressionBody(), syntax.blockBody(), syntax.span(),
                false, "lambda");
    }

    /**
     * Binds the body of a lambda or scheduled block in a nested binder whose root scope
     * captures enclosing locals.
     */
    BoundExpression bindLambdaBody(List<String> names, List<Type> types, List<Span> spans, Type resultType,
                                   Expression expressionBody, Statement.Block blockBody, Span span, boolean runsLater,
                                   String description) {
        LambdaBoundary boundary = new LambdaBoundary(span, runsLater);
        Scope root = new Scope(scope, boundary);
        List<LocalSymbol> parameters = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Span declared = i < spans.size() ? spans.get(i) : span;
            LocalSymbol parameter = new LocalSymbol(names.get(i), types.get(i), false,
                    names.get(i).equals("task") && runsLater ? LocalSymbol.Kind.IMPLICIT : LocalSymbol.Kind.PARAMETER,
                    declared, null);
            if (root.lookupHere(parameter.name()) != null) {
                module.error(DiagnosticCode.DUPLICATE_DECLARATION, declared, "Duplicate parameter '" + parameter.name() + "'.");
            }
            parameters.add(parameter);
            root.declare(parameter);
        }
        String key = module.newLambdaKey();
        int line = module.file().lineOf(span.start());
        String displayName = description + " at " + module.file().path() + ":" + line;
        BodyBinder inner = new BodyBinder(module, constants, root, resultType, null, description, receiver, assignedNames);
        inner.delayed = runsLater || delayed;
        BoundStatement.Block body;
        Type actualReturn;
        if (expressionBody != null) {
            if (resultType == PrimitiveType.VOID) {
                BoundExpression value = inner.bind(expressionBody, null);
                body = new BoundStatement.Block(List.of(new BoundStatement.ExpressionStatement(value, value.span())),
                        expressionBody.span());
                actualReturn = PrimitiveType.VOID;
            } else if (resultType != null) {
                BoundExpression value = inner.convert(inner.bindValue(expressionBody, resultType), resultType,
                        expressionBody.span(), "the result of the lambda");
                body = new BoundStatement.Block(List.of(new BoundStatement.Return(value, value.span())), expressionBody.span());
                actualReturn = resultType;
            } else {
                BoundExpression value = inner.bind(expressionBody, null);
                if (value.type() == PrimitiveType.VOID) {
                    body = new BoundStatement.Block(List.of(new BoundStatement.ExpressionStatement(value, value.span())),
                            expressionBody.span());
                    actualReturn = PrimitiveType.VOID;
                } else {
                    Type type = value.type() instanceof NullType ? Types.nullable(Types.ANY) : value.type();
                    body = new BoundStatement.Block(List.of(new BoundStatement.Return(value, value.span())),
                            expressionBody.span());
                    actualReturn = type;
                }
            }
        } else {
            body = inner.bindBlock(blockBody, false);
            inner.reportUnused(root);
            actualReturn = inner.effectiveReturnType();
            if (actualReturn != PrimitiveType.VOID && !actualReturn.isError() && Reachability.completesNormally(body)) {
                int end = Math.max(blockBody.span().start(), blockBody.span().end() - 1);
                module.report(module.diagnostic(DiagnosticCode.MISSING_RETURN, new Span(end, blockBody.span().end()),
                        "This " + description + " must return a value of type " + actualReturn.displayName()
                                + " on every path.").build());
            }
        }
        List<LocalSymbol> captures = new ArrayList<>(boundary.captured.values());
        FunctionType type = Types.function(types, actualReturn);
        boolean anyError = types.stream().anyMatch(Type::isError) || actualReturn.isError();
        BoundExpression.Lambda lambda = new BoundExpression.Lambda(key, displayName, parameters, captures,
                boundary.values, actualReturn, body, type, span);
        return anyError ? new BoundExpression.Error(span) : lambda;
    }

    /** Copies enclosing locals into a lambda when they are used inside it. */
    private final class LambdaBoundary implements Scope.Boundary {
        private final Map<LocalSymbol, LocalSymbol> captured = new LinkedHashMap<>();
        private final List<BoundExpression> values = new ArrayList<>();
        private final Map<LocalSymbol, LocalSymbol> failed = new LinkedHashMap<>();
        private final Span span;
        private final boolean runsLater;
        private boolean warnedEvent;

        LambdaBoundary(Span span, boolean runsLater) {
            this.span = span;
            this.runsLater = runsLater;
        }

        @Override
        public LocalSymbol capture(LocalSymbol outer) {
            LocalSymbol existing = captured.get(outer);
            if (existing != null) {
                return existing;
            }
            LocalSymbol broken = failed.get(outer);
            if (broken != null) {
                return broken;
            }
            LocalSymbol origin = outer.origin();
            if (origin.isMutable() && origin.kind() == LocalSymbol.Kind.VARIABLE && assignedNames.contains(origin.name())) {
                // The variable is used (wrongly): no 'never used' warning on top of this error.
                outer.markRead();
                module.report(module.diagnostic(DiagnosticCode.CAPTURED_VARIABLE_CHANGES, span,
                                "This block uses '" + origin.name() + "', which is changed after it is declared.")
                        .label(origin.declaration(), "declared here")
                        .note("A lambda or scheduled block copies the variables it uses when it is created, so it can "
                                + "only use variables that never change. Copy the value first: let "
                                + origin.name() + "Now = " + origin.name()).build());
                LocalSymbol error = new LocalSymbol(outer.name(), Types.ERROR, false, LocalSymbol.Kind.CAPTURE,
                        outer.declaration(), null);
                failed.put(outer, error);
                return error;
            }
            if (runsLater && origin.kind() == LocalSymbol.Kind.EVENT_OBJECT && !warnedEvent) {
                warnedEvent = true;
                module.report(module.diagnostic(DiagnosticCode.EVENT_USED_LATER, span,
                                "The event is over when this block runs: cancelling it or changing it has no effect.")
                        .note("Read what you need from the event before the block, e.g. let damage = event.damage").build());
            }
            Type type = flow.typeOf(outer);
            LocalSymbol inner = new LocalSymbol(outer.name(), type, false, LocalSymbol.Kind.CAPTURE, outer.declaration(),
                    outer.eventVariable());
            inner.capturedFrom(outer);
            captured.put(outer, inner);
            values.add(loadLocal(outer, span));
            return inner;
        }
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
                    ? new Facts(Facts.union(left.facts().whenTrue(), right.facts().whenTrue()), Map.of())
                    : new Facts(Map.of(), Facts.union(left.facts().whenFalse(), right.facts().whenFalse()));
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
                Map<LocalSymbol, Type> fact = Map.of(load.local(), current.nonNullable());
                return check.isNull() ? new Facts(Map.of(), fact) : new Facts(fact, Map.of());
            }
        }
        if (condition instanceof BoundExpression.TypeTest test && test.operand() instanceof BoundExpression.LocalLoad load) {
            return new Facts(Map.of(load.local(), test.target()), Map.of());
        }
        if (condition instanceof BoundExpression.Not not && not.operand() instanceof BoundExpression.TypeTest test
                && test.operand() instanceof BoundExpression.LocalLoad load) {
            return new Facts(Map.of(), Map.of(load.local(), test.target()));
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
        BoundExpression retyped = retypeLiteral(expression, target);
        if (retyped != null) {
            return retyped;
        }
        Type from = expression.type();
        if (Conversions.cost(from, target) != Conversions.NONE) {
            if (from.nonNullable() == Types.STRING && target.nonNullable() == Types.COMPONENT
                    && !(expression instanceof BoundExpression.Literal)) {
                return unsafeTextFormatting(expression, span);
            }
            if (from.nonNullable() == Types.STRING && target.nonNullable() instanceof ClassType classType
                    && classType.isConstantText() && !from.isError()
                    && ConstantEvaluator.evaluate(expression, ConstantEvaluator.SILENT) == ConstantEvaluator.NOT_CONSTANT) {
                module.report(module.diagnostic(DiagnosticCode.CONSTANT_TEXT_REQUIRED, span,
                                classType.name() + " text must be written in the script, not built while it runs.")
                        .note("Values must never be pasted into " + classType.name() + " text: a player could put "
                                + "commands into it. Write '?' where a value goes and pass the values separately:")
                        .note("db.query(\"SELECT coins FROM bank WHERE uuid = ?\", [player.uuid], rows => { ... })")
                        .build());
                return new BoundExpression.Error(span);
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
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.TYPE_MISMATCH, span, "Type mismatch for " + what + ".")
                    .expectedReceived(target.displayName(), from.displayName());
            if (from instanceof ListType && target instanceof ListType) {
                builder.note("Lists can only be passed where the same element type is expected. Build a new list "
                        + "if needed, e.g. list.map(x => x as " + ((ListType) target).element().displayName() + ")");
            }
            module.report(builder.build());
        }
        return new BoundExpression.Error(expression.span());
    }

    /**
     * A list or map literal whose elements convert one by one to the element types of
     * {@code target} ({@code [player.name, 5]} passed as a {@code List<any?>}): the literal is
     * re-typed, since nothing else can see it yet. Null if not applicable.
     */
    private BoundExpression retypeLiteral(BoundExpression expression, Type target) {
        Type base = target.nonNullable();
        if (expression.type().equals(base) || literalCost(expression, target) == Conversions.NONE) {
            return null;
        }
        if (expression instanceof BoundExpression.ListLiteral list && base instanceof ListType listType) {
            List<BoundExpression> elements = new ArrayList<>();
            for (BoundExpression element : list.elements()) {
                elements.add(convert(element, listType.element(), element.span(), "the list element"));
            }
            return Conversions.apply(new BoundExpression.ListLiteral(elements, listType, list.span()), target);
        }
        if (expression instanceof BoundExpression.MapLiteral map && base instanceof MapType mapType) {
            List<BoundExpression> keys = new ArrayList<>();
            List<BoundExpression> values = new ArrayList<>();
            for (int i = 0; i < map.keys().size(); i++) {
                keys.add(convert(map.keys().get(i), mapType.key(), map.keys().get(i).span(), "the map key"));
                values.add(convert(map.values().get(i), mapType.value(), map.values().get(i).span(), "the map value"));
            }
            return Conversions.apply(new BoundExpression.MapLiteral(keys, values, mapType, map.span()), target);
        }
        return null;
    }

    /** Conversion cost of a value, counting list and map literals as convertible element by element. */
    static int literalCost(BoundExpression expression, Type target) {
        Type base = target.nonNullable();
        if (expression instanceof BoundExpression.ListLiteral list && base instanceof ListType listType
                && !list.type().equals(listType)) {
            int cost = 0;
            for (BoundExpression element : list.elements()) {
                int elementCost = Conversions.cost(element.type(), listType.element());
                if (elementCost == Conversions.NONE) {
                    return Conversions.NONE;
                }
                cost = Math.max(cost, elementCost);
            }
            return cost;
        }
        if (expression instanceof BoundExpression.MapLiteral map && base instanceof MapType mapType
                && !map.type().equals(mapType)) {
            int cost = 0;
            for (int i = 0; i < map.keys().size(); i++) {
                int keyCost = Conversions.cost(map.keys().get(i).type(), mapType.key());
                int valueCost = Conversions.cost(map.values().get(i).type(), mapType.value());
                if (keyCost == Conversions.NONE || valueCost == Conversions.NONE) {
                    return Conversions.NONE;
                }
                cost = Math.max(cost, Math.max(keyCost, valueCost));
            }
            return cost;
        }
        return Conversions.cost(expression.type(), target);
    }

    BoundExpression bind(Expression syntax, Type expected) {
        return switch (syntax) {
            case Expression.Literal literal -> bindLiteral(literal, expected, false, literal.span());
            case Expression.Template template -> bindTemplate(template, expected);
            case Expression.Duration duration -> bindDuration(duration);
            case Expression.Name name -> bindName(name, expected);
            case Expression.Member member -> expectValue(bindTarget(member), member);
            case Expression.Call call -> bindCall(call, expected);
            case Expression.Index index -> bindIndex(index);
            case Expression.Unary unary -> bindUnary(unary, expected);
            case Expression.Binary binary -> bindBinary(binary, expected);
            case Expression.Is is -> bindIs(is);
            case Expression.Cast cast -> bindCast(cast);
            case Expression.Range range -> {
                module.report(module.diagnostic(DiagnosticCode.UNSUPPORTED_FEATURE, range.span(),
                                "Ranges can only be used in 'for' loops and with 'in'.")
                        .note("Examples: for i in 1..10 { }   if level in 10..20 { }").build());
                yield new BoundExpression.Error(range.span());
            }
            case Expression.ListLiteral list -> bindList(list, expected);
            case Expression.MapLiteral map -> bindMap(map, expected);
            case Expression.Lambda lambda -> bindLambda(lambda, expected);
            case Expression.Conditional conditional -> bindConditional(conditional, expected);
            case Expression.Switch sw -> bindSwitchExpression(sw, expected);
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
                current.append(ConstantEvaluator.toText(constant, part.type()));
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
    BoundExpression toText(BoundExpression value) {
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
                LocalSymbol receiverTemp = module.newTemporary(type, value.span());
                BoundExpression call = new BoundExpression.NativeCall(stringifier.invocable(),
                        List.of(new BoundExpression.LocalLoad(receiverTemp, classType, value.span())), Types.STRING, value.span());
                Type nullableString = Types.nullable(Types.STRING);
                BoundExpression safe = new BoundExpression.SafeAccess(value, receiverTemp, call, nullableString, value.span());
                LocalSymbol result = module.newTemporary(nullableString, value.span());
                return new BoundExpression.Coalesce(safe, result,
                        new BoundExpression.LocalLoad(result, Types.STRING, value.span()),
                        new BoundExpression.Literal("null", Types.STRING, value.span()), Types.STRING, value.span());
            }
        }
        if (type instanceof ListType list && needsElementText(list.element())) {
            // "[" + list.join(", ", x => "{x}") + "]": durations, players, materials... print as they do on their own.
            Span span = value.span();
            BoundExpression joined = BuiltinMembers.intrinsic(Intrinsics.LIST_JOIN, Types.STRING, span, value,
                    new BoundExpression.Literal(", ", Types.STRING, span), textLambda(list.element(), span));
            return new BoundExpression.Concat(List.of(new BoundExpression.Literal("[", Types.STRING, span), joined,
                    new BoundExpression.Literal("]", Types.STRING, span)), span);
        }
        if (type instanceof MapType map && (needsElementText(map.key()) || needsElementText(map.value()))) {
            Span span = value.span();
            return BuiltinMembers.intrinsic(Intrinsics.MAP_TEXT, Types.STRING, span, value,
                    textLambda(map.key(), span), textLambda(map.value(), span));
        }
        BoundExpression conversion = new BoundExpression.Conversion(ConversionKind.TO_STRING, value, Types.STRING, value.span());
        Object constant = ConstantEvaluator.evaluate(conversion, ConstantEvaluator.SILENT);
        if (constant instanceof String text) {
            return new BoundExpression.Literal(text, Types.STRING, value.span());
        }
        return conversion;
    }

    /**
     * Whether elements of this type print differently inside a list than on their own: text,
     * numbers and bool print the same either way, so their lists keep the plain (faster) form.
     */
    private static boolean needsElementText(Type element) {
        Type type = element.nonNullable();
        return !(type == Types.STRING || type == Types.ANY || type == PrimitiveType.INT || type == PrimitiveType.LONG
                || type == PrimitiveType.DOUBLE || type == PrimitiveType.FLOAT || type == PrimitiveType.BOOL
                || type.isError());
    }

    private FunctionDeclaration stringifier(ClassType type) {
        for (FunctionDeclaration method : module.members().lookup(type, "toString").methods()) {
            if (method.parameters().isEmpty() && method.returnType() == Types.STRING) {
                return method;
            }
        }
        return null;
    }

    /**
     * A lambda converting values of {@code element} to text the way templates do ({@code x =>
     * "{x}"}), for operations such as {@code list.join(", ")} that need text of each element.
     */
    BoundExpression textLambda(Type element, Span span) {
        LocalSymbol parameter = new LocalSymbol("value", element, false, LocalSymbol.Kind.PARAMETER, span, null);
        BoundExpression text = toText(new BoundExpression.LocalLoad(parameter, element, span));
        BoundStatement.Block body = new BoundStatement.Block(List.of(new BoundStatement.Return(text, span)), span);
        String key = module.newLambdaKey();
        return new BoundExpression.Lambda(key, "text of an element at " + module.file().path() + ":"
                + module.file().lineOf(span.start()), List.of(parameter), List.of(), List.of(), Types.STRING, body,
                Types.function(List.of(element), Types.STRING), span);
    }

    // ------------------------------------------------------------------- names and paths

    /** What a name or dotted path refers to. */
    sealed interface PathResult {
        record Value(BoundExpression expression) implements PathResult {
        }

        record Namespace(String name) implements PathResult {
        }

        record Functions(String name, List<FunctionSymbol> user, List<FunctionDeclaration> natives) implements PathResult {
        }

        /** Methods of the record whose method is being bound, called without {@code this.}. */
        record Methods(String name, BoundExpression receiver, List<FunctionSymbol> methods) implements PathResult {
        }

        record TypeName(String name) implements PathResult {
        }

        record Record(RecordSymbol record) implements PathResult {
        }

        /** An imported module ({@code import economy}). */
        record Module(String alias, BoundModule module) implements PathResult {
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
        if (receiver != null) {
            var field = receiver.field(name);
            if (field.isPresent()) {
                return new PathResult.Value(new BoundExpression.RecordGet(thisValue(identifier.span()), field.get(),
                        identifier.span()));
            }
            List<FunctionSymbol> methods = receiver.methods(name);
            if (!methods.isEmpty()) {
                return new PathResult.Methods(name, thisValue(identifier.span()), methods);
            }
        }
        ConstantSymbol constant = module.constants().get(name);
        if (constant != null) {
            return new PathResult.Value(constants.constant(constant, identifier.span()));
        }
        GlobalSymbol global = module.globals().get(name);
        if (global != null) {
            return globalValue(global, identifier.span());
        }
        List<FunctionSymbol> user = module.functions(name);
        if (!user.isEmpty()) {
            return new PathResult.Functions(name, user, List.of());
        }
        RecordSymbol record = module.records().get(name);
        if (record != null) {
            return new PathResult.Record(record);
        }
        Object imported = module.importedNames().get(name);
        if (imported != null) {
            return importedValue(name, imported, identifier.span());
        }
        BoundModule aliased = module.moduleAliases().get(name);
        if (aliased != null) {
            return new PathResult.Module(name, aliased);
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
        if (module.failedImports().contains(name)) {
            // The import itself was reported; do not add an error for every use.
            return new PathResult.Failed(identifier.span());
        }
        return new PathResult.Unknown(identifier);
    }

    private BoundExpression thisValue(Span span) {
        LocalSymbol self = scope.lookup("this");
        if (self == null) {
            return new BoundExpression.Error(span);
        }
        return loadLocal(self, span);
    }

    private PathResult globalValue(GlobalSymbol global, Span span) {
        if (global.isPlayerData()) {
            module.report(module.diagnostic(DiagnosticCode.NOT_A_VALUE, span,
                            "'" + global.name() + "' is saved per player; read it on a player.")
                    .note("Example: player." + global.name()).build());
            return new PathResult.Failed(span);
        }
        if (initializer && !global.isInitialized() && global.module().equals(module.moduleName())) {
            module.report(module.diagnostic(DiagnosticCode.USED_BEFORE_DECLARATION, span,
                            "'" + global.name() + "' is used before its declaration.")
                    .label(global.declaration(), "declared here")
                    .note("Top-level variables are initialized from top to bottom; move this declaration below it.")
                    .build());
            return new PathResult.Failed(span);
        }
        return new PathResult.Value(new BoundExpression.GlobalLoad(global, global.type(), span));
    }

    @SuppressWarnings("unchecked")
    private PathResult importedValue(String name, Object imported, Span span) {
        return switch (imported) {
            case ConstantSymbol constant -> constant.type() == null
                    ? new PathResult.Failed(span)
                    : new PathResult.Value(new BoundExpression.Literal(constant.value(), constant.type(), span));
            case GlobalSymbol global -> globalValue(global, span);
            case RecordSymbol record -> new PathResult.Record(record);
            case List<?> functions -> new PathResult.Functions(name, (List<FunctionSymbol>) functions, List.of());
            default -> new PathResult.Failed(span);
        };
    }

    /** Binds a bare name, which may denote a function used as a value when a function type is expected. */
    private BoundExpression bindName(Expression.Name name, Type expected) {
        PathResult result = resolveName(name.identifier());
        if (result instanceof PathResult.Functions functions && !functions.user().isEmpty()
                && expected != null && expected.nonNullable() instanceof FunctionType target) {
            return functionReference(functions.name(), functions.user(), target, name.span());
        }
        return expectValue(result, name);
    }

    private BoundExpression functionReference(String name, List<FunctionSymbol> overloads, FunctionType target, Span span) {
        for (FunctionSymbol symbol : overloads) {
            if (symbol.parameterTypes().equals(target.parameters())
                    && Conversions.isAssignable(symbol.returnType(), target.returnType())
                    && (symbol.returnType() == PrimitiveType.VOID) == (target.returnType() == PrimitiveType.VOID)) {
                FunctionType type = Types.function(symbol.parameterTypes(), symbol.returnType());
                return new BoundExpression.FunctionReference(symbol, type, span);
            }
        }
        StringJoiner candidates = new StringJoiner("\n    ", "Functions named '" + name + "':\n    ", "");
        overloads.forEach(symbol -> candidates.add(symbol.toString()));
        module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, span,
                        "No function '" + name + "' matches " + target.displayName() + ".")
                .note(candidates.toString()).build());
        return new BoundExpression.Error(span);
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
                case PathResult.Methods methods -> {
                    module.error(DiagnosticCode.NOT_A_VALUE, member.target().span(),
                            "'" + methods.name() + "' is a method; call it with parentheses: " + methods.name() + "(...)");
                    yield new PathResult.Failed(member.span());
                }
                case PathResult.TypeName typeName -> typeMember(typeName.name(), member.member());
                case PathResult.Record record -> {
                    module.error(DiagnosticCode.UNKNOWN_MEMBER, member.member().span(),
                            "Record '" + record.record().name() + "' has no static member '" + name + "'.");
                    yield new PathResult.Failed(member.span());
                }
                case PathResult.Module imported -> moduleMember(imported, member.member());
            };
        }
        return new PathResult.Value(bindValue(expression, null));
    }

    private PathResult moduleMember(PathResult.Module imported, Identifier member) {
        Object found = imported.module().member(member.name());
        if (found == null) {
            List<String> names = new ArrayList<>();
            imported.module().constants().forEach(c -> names.add(c.name()));
            imported.module().globals().forEach(g -> names.add(g.name()));
            imported.module().records().forEach(r -> names.add(r.name()));
            imported.module().functions().forEach(f -> {
                if (f.symbol() != null && !f.symbol().isMethod()) {
                    names.add(f.symbol().name());
                }
            });
            module.report(module.diagnostic(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                            "Module '" + imported.alias() + "' has no '" + member.name() + "'.")
                    .suggestions(Suggestions.closest(member.name(), names, 3)).build());
            return new PathResult.Failed(member.span());
        }
        return importedValue(member.name(), found, member.span());
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
        ClassType keyed = module.registry().type(namespace).filter(ClassType::isKeyed).orElse(null);
        if (keyed != null) {
            return keyedConstant(keyed, member);
        }
        reportUnknownNamespaceMember(namespace, member);
        return new PathResult.Failed(member.span());
    }

    private PathResult typeMember(String typeName, Identifier member) {
        ClassType type = module.registry().type(typeName).orElse(null);
        if (type != null && type.isKeyed()) {
            return keyedConstant(type, member);
        }
        module.error(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                "Type '" + typeName + "' has no static member '" + member.name() + "'.");
        return new PathResult.Failed(member.span());
    }

    private PathResult keyedConstant(ClassType type, Identifier member) {
        var table = module.registry().keys(type);
        String key = table.key(member.name()).orElse(null);
        if (key == null) {
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.UNKNOWN_CONSTANT, member.span(),
                    "Unknown " + type.name() + " '" + member.name() + "'.");
            String upper = member.name().toUpperCase(java.util.Locale.ROOT);
            if (!upper.equals(member.name()) && table.key(upper).isPresent()) {
                builder.suggestions(List.of(upper)).note(type.name() + " names are written in capitals.");
            } else {
                builder.suggestions(Suggestions.closest(upper, table.names(), 3));
            }
            if (table.size() == 0) {
                builder.note("No " + type.name() + " names are known to the compiler.");
            }
            module.report(builder.build());
            return new PathResult.Failed(member.span());
        }
        return new PathResult.Value(new BoundExpression.KeyedConstant(type, member.name(), key, member.span()));
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
                module.report(module.diagnostic(DiagnosticCode.NOT_A_VALUE, syntax.span(),
                                "'" + functions.name() + "' is a function; call it with parentheses: " + functions.name() + "(...)")
                        .note("To pass the function itself, use it where a function type is expected, "
                                + "e.g. list.sortBy(" + functions.name() + ")").build());
                yield new BoundExpression.Error(syntax.span());
            }
            case PathResult.Methods methods -> {
                module.error(DiagnosticCode.NOT_A_VALUE, syntax.span(),
                        "'" + methods.name() + "' is a method; call it with parentheses: " + methods.name() + "(...)");
                yield new BoundExpression.Error(syntax.span());
            }
            case PathResult.TypeName typeName -> {
                module.error(DiagnosticCode.NOT_A_VALUE, syntax.span(), "'" + typeName.name() + "' is a type, not a value.");
                yield new BoundExpression.Error(syntax.span());
            }
            case PathResult.Record record -> {
                module.report(module.diagnostic(DiagnosticCode.NOT_A_VALUE, syntax.span(),
                                "'" + record.record().name() + "' is a record type, not a value.")
                        .note("Create a value with " + record.record().name() + "(...)").build());
                yield new BoundExpression.Error(syntax.span());
            }
            case PathResult.Module imported -> {
                module.error(DiagnosticCode.NOT_A_VALUE, syntax.span(),
                        "'" + imported.alias() + "' is an imported module, not a value.");
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

    private BoundExpression memberOnValue(BoundExpression receiverValue, Expression.Member member) {
        return nullSafe(receiverValue, member.target(), member.member().name(), member.nullSafe(), member.span(),
                value -> memberAccess(value, member.member(), member.span()));
    }

    interface Access {
        BoundExpression apply(BoundExpression receiver);
    }

    /**
     * Applies {@code access} to a receiver, handling {@code ?.}: a nullable receiver without
     * {@code ?.} is an error; with {@code ?.} the access runs only for non-null receivers.
     */
    BoundExpression nullSafe(BoundExpression receiverValue, Expression receiverSyntax, String memberName, boolean safe,
                             Span span, Access access) {
        Type type = receiverValue.type();
        if (type.isError()) {
            return new BoundExpression.Error(span);
        }
        if (safe && !type.isNullable()) {
            module.report(module.diagnostic(DiagnosticCode.UNNECESSARY_SAFE_CALL, span,
                    "'?.' is unnecessary: " + describeValue(receiverValue) + " is never null.").build());
            safe = false;
        }
        if (type instanceof NullType) {
            module.error(DiagnosticCode.NULLABLE_ACCESS, span, "Cannot access members of 'null'.");
            return new BoundExpression.Error(span);
        }
        if (type.isNullable()) {
            if (!safe) {
                reportNullableAccess(receiverValue, receiverSyntax, memberName);
                return new BoundExpression.Error(span);
            }
            LocalSymbol temporary = module.newTemporary(type, receiverValue.span());
            BoundExpression nonNull = type.nonNullable().representation().isPrimitive()
                    ? new BoundExpression.Conversion(ConversionKind.UNBOX,
                    new BoundExpression.LocalLoad(temporary, type, receiverValue.span()), type.nonNullable(), receiverValue.span())
                    : new BoundExpression.LocalLoad(temporary, type.nonNullable(), receiverValue.span());
            BoundExpression inner = access.apply(nonNull);
            if (inner.type().isError()) {
                return inner;
            }
            if (inner.type() == PrimitiveType.VOID) {
                return new BoundExpression.SafeAccess(receiverValue, temporary, inner, PrimitiveType.VOID, span);
            }
            Type resultType = Types.nullable(inner.type());
            return new BoundExpression.SafeAccess(receiverValue, temporary, Conversions.apply(inner, resultType), resultType, span);
        }
        return access.apply(receiverValue);
    }

    private BoundExpression memberAccess(BoundExpression receiverValue, Identifier member, Span span) {
        String name = member.name();
        Type type = receiverValue.type();
        if (type instanceof ClassType classType) {
            MemberLookup.Result result = module.members().lookup(classType, name);
            if (result.property() != null) {
                return propertyGet(receiverValue, result.property(), classType.name() + "." + name, span);
            }
            if (!result.methods().isEmpty()) {
                module.error(DiagnosticCode.NOT_A_VALUE, member.span(),
                        "'" + name + "' is a method; call it with parentheses: " + name + "(...)");
                return new BoundExpression.Error(span);
            }
            RecordSymbol record = module.record(classType);
            if (record != null) {
                var field = record.field(name);
                if (field.isPresent()) {
                    return new BoundExpression.RecordGet(receiverValue, field.get(), span);
                }
                if (!record.methods(name).isEmpty()) {
                    module.error(DiagnosticCode.NOT_A_VALUE, member.span(),
                            "'" + name + "' is a method; call it with parentheses: " + name + "(...)");
                    return new BoundExpression.Error(span);
                }
            }
            GlobalSymbol playerData = playerData(classType, name);
            if (playerData != null) {
                return new BoundExpression.PlayerDataLoad(playerData, receiverValue, playerData.type(), span);
            }
            if (classType == Types.EXCEPTION) {
                return builtins.errorProperty(receiverValue, member, span);
            }
            reportUnknownMember(classType, member, receiverValue);
            return new BoundExpression.Error(span);
        }
        BoundExpression builtin = builtins.property(receiverValue, member, span);
        if (builtin != null) {
            return builtin;
        }
        module.error(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                "Values of type " + type.displayName() + " have no member '" + name + "'.");
        return new BoundExpression.Error(span);
    }

    /**
     * The {@code playerdata var} named {@code name} readable on values of {@code type}
     * (players and offline players), declared in this module or imported; null if none.
     */
    GlobalSymbol playerData(ClassType type, String name) {
        ClassType offline = module.standardType("OfflinePlayer");
        ClassType player = module.standardType("Player");
        boolean isPlayer = (offline != null && type.isSubtypeOf(offline)) || (player != null && type.isSubtypeOf(player));
        if (!isPlayer) {
            return null;
        }
        GlobalSymbol own = module.globals().get(name);
        if (own != null && own.isPlayerData()) {
            return own;
        }
        if (module.importedNames().get(name) instanceof GlobalSymbol imported && imported.isPlayerData()) {
            return imported;
        }
        for (BoundModule imported : module.moduleAliases().values()) {
            if (imported.member(name) instanceof GlobalSymbol global && global.isPlayerData()) {
                return global;
            }
        }
        return null;
    }

    BoundExpression propertyGet(BoundExpression receiverValue, PropertyDeclaration property, String display, Span span) {
        warnDeprecated(property.deprecation().orElse(null), display, span);
        List<BoundExpression> arguments = receiverValue == null ? List.of() : List.of(receiverValue);
        return new BoundExpression.NativeCall(property.getter(), arguments, property.type(), span);
    }

    // ------------------------------------------------------------------- calls

    private BoundExpression bindCall(Expression.Call call, Type expected) {
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
                        call.span(), receiverValue -> methodCall(receiverValue, member.member(), call));
                case PathResult.Module imported -> {
                    Object found = imported.module().member(name);
                    if (found instanceof RecordSymbol record) {
                        yield construct(record, call);
                    }
                    if (found instanceof List<?> functions) {
                        @SuppressWarnings("unchecked")
                        List<FunctionSymbol> overloads = (List<FunctionSymbol>) functions;
                        yield callFunctions(imported.alias() + "." + name, overloads, List.of(), null, call);
                    }
                    if (found instanceof GlobalSymbol global && global.type() instanceof FunctionType) {
                        yield closureCall(new BoundExpression.GlobalLoad(global, global.type(), member.span()), call);
                    }
                    bindArgumentsForErrors(call.arguments());
                    if (found == null) {
                        moduleMember(imported, member.member());
                    } else {
                        module.error(DiagnosticCode.NOT_CALLABLE, member.member().span(),
                                "'" + imported.alias() + "." + name + "' is not a function.");
                    }
                    yield new BoundExpression.Error(call.span());
                }
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
                case PathResult.Methods methods -> {
                    bindArgumentsForErrors(call.arguments());
                    module.error(DiagnosticCode.NOT_A_VALUE, member.target().span(),
                            "'" + methods.name() + "' is a method; call it with parentheses: " + methods.name() + "(...)");
                    yield new BoundExpression.Error(call.span());
                }
                case PathResult.TypeName typeName -> {
                    bindArgumentsForErrors(call.arguments());
                    List<FunctionDeclaration> natives = module.registry().functions(typeName.name() + "." + name);
                    if (!natives.isEmpty()) {
                        yield callFunctions(typeName.name() + "." + name, List.of(), natives, null, call);
                    }
                    module.error(DiagnosticCode.UNKNOWN_MEMBER, member.member().span(),
                            "Type '" + typeName.name() + "' has no static function '" + name + "'.");
                    yield new BoundExpression.Error(call.span());
                }
                case PathResult.Record record -> {
                    bindArgumentsForErrors(call.arguments());
                    module.error(DiagnosticCode.UNKNOWN_MEMBER, member.member().span(),
                            "Record '" + record.record().name() + "' has no static function '" + name + "'.");
                    yield new BoundExpression.Error(call.span());
                }
            };
        }
        if (callee instanceof Expression.Name name) {
            String text = name.name();
            PathResult resolved = resolveName(name.identifier());
            return switch (resolved) {
                case PathResult.Value value -> {
                    if (value.expression().type() instanceof FunctionType || value.expression().type().isError()) {
                        yield closureCall(value.expression(), call);
                    }
                    bindArgumentsForErrors(call.arguments());
                    Type calleeType = value.expression().type();
                    if (calleeType.isNullable() && calleeType.nonNullable() instanceof FunctionType) {
                        // A function looked up in a map (or a nullable variable): it may be missing.
                        module.report(module.diagnostic(DiagnosticCode.NULLABLE_ACCESS, name.span(),
                                        "'" + text + "' may be null (type " + calleeType.displayName() + ").")
                                .note("Check for null before calling it:\n    if " + text + " != null {\n        "
                                        + text + "(...)\n    }").build());
                        yield new BoundExpression.Error(call.span());
                    }
                    String kind = value.expression() instanceof BoundExpression.LocalLoad load
                            ? "a " + describeKind(load.local()) : "a value";
                    module.error(DiagnosticCode.NOT_CALLABLE, name.span(), "'" + text + "' is " + kind + " of type "
                            + calleeType.displayName() + ", not a function.");
                    yield new BoundExpression.Error(call.span());
                }
                case PathResult.Methods methods -> callFunctions(text, methods.methods(), List.of(), methods.receiver(), call);
                case PathResult.Functions functions -> callFunctions(text, functions.user(), functions.natives(), null, call);
                case PathResult.Record record -> construct(record.record(), call);
                case PathResult.Failed ignored -> {
                    bindArgumentsForErrors(call.arguments());
                    yield new BoundExpression.Error(call.span());
                }
                // A name can be both a namespace and a function ('log' and 'log.info'), or a type and a
                // function creating values of it ('Location(...)').
                case PathResult.Namespace ignored when !module.registry().functions(text).isEmpty() ->
                        callFunctions(text, List.of(), module.registry().functions(text), null, call);
                case PathResult.TypeName ignored when !module.registry().functions(text).isEmpty() ->
                        callFunctions(text, List.of(), module.registry().functions(text), null, call);
                default -> {
                    bindArgumentsForErrors(call.arguments());
                    List<String> candidates = new ArrayList<>(module.functions().keySet());
                    candidates.addAll(module.records().keySet());
                    for (String member : module.registry().namespaceMembers("")) {
                        if (!module.registry().functions(member).isEmpty()) {
                            candidates.add(member);
                        }
                    }
                    module.report(module.diagnostic(DiagnosticCode.UNKNOWN_FUNCTION, name.span(),
                            "Unknown function '" + text + "'.").suggestions(Suggestions.closest(text, candidates, 3)).build());
                    yield new BoundExpression.Error(call.span());
                }
            };
        }
        BoundExpression value = bindValue(callee, null);
        if (value.type() instanceof FunctionType || value.type().isError()) {
            return closureCall(value, call);
        }
        bindArgumentsForErrors(call.arguments());
        module.error(DiagnosticCode.NOT_CALLABLE, callee.span(), "This expression cannot be called.");
        return new BoundExpression.Error(call.span());
    }

    /** Calls a function value. */
    private BoundExpression closureCall(BoundExpression callee, Expression.Call call) {
        if (callee.type().isError()) {
            bindArgumentsForErrors(call.arguments());
            return new BoundExpression.Error(call.span());
        }
        FunctionType type = (FunctionType) callee.type();
        if (type.arity() != call.arguments().size()) {
            bindArgumentsForErrors(call.arguments());
            module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                    "This function takes " + type.arity() + " argument" + (type.arity() == 1 ? "" : "s") + ", but "
                            + call.arguments().size() + (call.arguments().size() == 1 ? " was" : " were") + " given.")
                    .note("Its type is " + type.displayName()).build());
            return new BoundExpression.Error(call.span());
        }
        List<BoundExpression> arguments = new ArrayList<>();
        for (int i = 0; i < type.arity(); i++) {
            Type parameter = type.parameters().get(i);
            arguments.add(convert(bindValue(call.arguments().get(i), parameter), parameter, call.arguments().get(i).span(),
                    "argument #" + (i + 1)));
        }
        return new BoundExpression.ClosureCall(callee, arguments, type.returnType(), call.span());
    }

    /** {@code Record(field1, field2, ...)}; missing trailing fields take their defaults. */
    private BoundExpression construct(RecordSymbol record, Expression.Call call) {
        List<RecordSymbol.Field> fields = record.fields();
        if (call.arguments().size() > fields.size()) {
            bindArgumentsForErrors(call.arguments());
            module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                    "Record '" + record.name() + "' has " + fields.size() + " field" + (fields.size() == 1 ? "" : "s")
                            + ", but " + call.arguments().size() + " values were given.")
                    .note("Fields: " + fieldList(record)).build());
            return new BoundExpression.Error(call.span());
        }
        List<BoundExpression> values = new ArrayList<>();
        boolean failed = false;
        for (int i = 0; i < fields.size(); i++) {
            RecordSymbol.Field field = fields.get(i);
            if (i < call.arguments().size()) {
                Expression argument = call.arguments().get(i);
                values.add(convert(bindValue(argument, field.type()), field.type(), argument.span(),
                        "field '" + field.name() + "' of " + record.name()));
            } else if (field.syntax() != null && field.syntax().defaultValue() != null) {
                values.add(constants.defaultValue(field.syntax().defaultValue(), field.type(),
                        "field '" + field.name() + "' of " + record.name()));
            } else {
                if (!failed) {
                    module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                                    "Missing a value for field '" + field.name() + "' of record '" + record.name() + "'.")
                            .note("Fields: " + fieldList(record)).build());
                }
                failed = true;
            }
        }
        if (failed) {
            return new BoundExpression.Error(call.span());
        }
        return new BoundExpression.NewRecord(record, values, call.span());
    }

    private static String fieldList(RecordSymbol record) {
        StringJoiner joiner = new StringJoiner(", ", record.name() + "(", ")");
        for (RecordSymbol.Field field : record.fields()) {
            joiner.add(field.name() + ": " + field.type().displayName());
        }
        return joiner.toString();
    }

    private BoundExpression methodCall(BoundExpression receiverValue, Identifier method, Expression.Call call) {
        String name = method.name();
        Type type = receiverValue.type();
        if (type instanceof ClassType classType) {
            MemberLookup.Result result = module.members().lookup(classType, name);
            if (!result.methods().isEmpty()) {
                return callFunctions(classType.name() + "." + name, List.of(), result.methods(), receiverValue, call);
            }
            RecordSymbol record = module.record(classType);
            if (record != null && !record.methods(name).isEmpty()) {
                return callFunctions(classType.name() + "." + name, record.methods(name), List.of(), receiverValue, call);
            }
            if (result.property() != null && result.property().type() instanceof FunctionType) {
                return closureCall(propertyGet(receiverValue, result.property(), classType.name() + "." + name,
                        method.span()), call);
            }
            if (record != null && record.field(name).isPresent() && record.field(name).get().type() instanceof FunctionType) {
                return closureCall(new BoundExpression.RecordGet(receiverValue, record.field(name).get(), method.span()), call);
            }
            bindArgumentsForErrors(call.arguments());
            if (result.property() != null || (record != null && record.field(name).isPresent())) {
                module.error(DiagnosticCode.NOT_CALLABLE, method.span(), "'" + name + "' is a property, not a method; "
                        + "remove the parentheses.");
            } else {
                reportUnknownMember(classType, method, receiverValue);
            }
            return new BoundExpression.Error(call.span());
        }
        if (type instanceof PrimitiveType primitive) {
            List<FunctionDeclaration> extensions = module.registry().functions(primitive.displayName() + "." + name);
            if (!extensions.isEmpty()) {
                return callFunctions(primitive.displayName() + "." + name, List.of(), extensions, receiverValue, call);
            }
        }
        BoundExpression builtin = builtins.method(receiverValue, method, call);
        if (builtin != null) {
            return builtin;
        }
        bindArgumentsForErrors(call.arguments());
        module.error(DiagnosticCode.UNKNOWN_MEMBER, method.span(),
                "Values of type " + type.displayName() + " have no method '" + name + "'.");
        return new BoundExpression.Error(call.span());
    }

    void bindArgumentsForErrors(List<Expression> arguments) {
        for (Expression argument : arguments) {
            bind(argument, null);
        }
    }

    // ------------------------------------------------------------------- overloads

    /** A call target: a script function or a host function/method. */
    private record Candidate(FunctionSymbol user, FunctionDeclaration host, List<Type> parameters, List<String> names,
                             int required) {
        static Candidate of(FunctionSymbol symbol) {
            return new Candidate(symbol, null, symbol.parameterTypes(), symbol.parameterNames(), symbol.requiredParameters());
        }

        static Candidate of(FunctionDeclaration declaration) {
            List<Type> types = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Parameter parameter : declaration.parameters()) {
                types.add(parameter.type());
                names.add(parameter.name());
            }
            return new Candidate(null, declaration, types, names, types.size());
        }

        Type returnType() {
            return user != null ? user.returnType() : host.returnType();
        }

        boolean accepts(int count) {
            return count >= required && count <= parameters.size();
        }

        String signature(String display) {
            StringJoiner joiner = new StringJoiner(", ", display + "(", ")");
            for (int i = 0; i < parameters.size(); i++) {
                joiner.add(names.get(i) + ": " + parameters.get(i).displayName() + (i >= required ? " = ..." : ""));
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

        /** String literals, templates, numeric literals, null, empty collections and lambdas: typed by the parameter. */
        record Deferred(Expression syntax, Type naturalType, boolean text, boolean emptyList, boolean emptyMap,
                        int lambdaArity) implements Argument {
            public Span span() {
                return syntax.span();
            }
        }
    }

    private Argument prebind(Expression syntax) {
        Expression expression = unwrap(syntax);
        if (expression instanceof Expression.Template || isTextChoice(expression)
                && (expression instanceof Expression.Conditional || expression instanceof Expression.Switch
                || expression instanceof Expression.Binary)) {
            return new Argument.Deferred(syntax, Types.STRING, true, false, false, -1);
        }
        if (expression instanceof Expression.ListLiteral list && list.elements().isEmpty()) {
            return new Argument.Deferred(syntax, Types.ERROR, false, true, false, -1);
        }
        if (expression instanceof Expression.MapLiteral map && map.entries().isEmpty()) {
            return new Argument.Deferred(syntax, Types.ERROR, false, false, true, -1);
        }
        if (expression instanceof Expression.Lambda lambda) {
            return new Argument.Deferred(syntax, Types.ERROR, false, false, false, lambda.parameters().size());
        }
        if (expression instanceof Expression.Name name && !module.functions(name.name()).isEmpty()
                && scope.peek(name.name()) == null) {
            return new Argument.Deferred(syntax, Types.ERROR, false, false, false, -2);
        }
        Expression.Literal literal = expression instanceof Expression.Literal l ? l
                : expression instanceof Expression.Unary unary && unary.operator() == UnaryOperator.NEGATE
                && unwrap(unary.operand()) instanceof Expression.Literal l2 ? l2 : null;
        if (literal != null) {
            switch (literal.kind()) {
                case STRING -> {
                    return new Argument.Deferred(syntax, Types.STRING, true, false, false, -1);
                }
                case NULL -> {
                    return new Argument.Deferred(syntax, Types.NULL, false, false, false, -1);
                }
                case INT -> {
                    long magnitude = (Long) literal.value();
                    long limit = literal == expression ? Integer.MAX_VALUE : 2_147_483_648L;
                    Type natural = Long.compareUnsigned(magnitude, limit) <= 0 ? PrimitiveType.INT : PrimitiveType.LONG;
                    return new Argument.Deferred(syntax, natural, false, false, false, -1);
                }
                default -> {
                }
            }
        }
        return new Argument.Bound(bindValue(syntax, null));
    }

    /**
     * Whether an expression only chooses between texts written in the script: a string literal,
     * a template, or a conditional or switch whose values are all such texts
     * ({@code ok ? "<green>Yes" : "<red>No"}). Like a single literal, it is typed by the
     * parameter it is passed to, so it can be a message: every possible value is the script's
     * own text, and values inside templates stay plain text.
     */
    private static boolean isTextChoice(Expression syntax) {
        return switch (unwrap(syntax)) {
            case Expression.Literal literal -> literal.kind() == Expression.LiteralKind.STRING;
            case Expression.Template ignored -> true;
            case Expression.Binary binary -> joinedText(binary) != null;
            case Expression.Conditional conditional -> isTextChoice(conditional.whenTrue())
                    && isTextChoice(conditional.whenFalse());
            case Expression.Switch choice -> choice.defaultValue() != null && isTextChoice(choice.defaultValue())
                    && choice.arms().stream().allMatch(arm -> isTextChoice(arm.value()));
            default -> false;
        };
    }

    /**
     * Texts written in the script joined with '+' — usually a long message split over lines,
     * {@code "<gray>Hello {player.name}, " + "welcome to {server.name}!"} — as the single template
     * they spell; null if any piece is not a string literal or template. Where a message is
     * expected, the join is formatted like one template: the values inside stay plain text.
     */
    private static Expression.Template joinedText(Expression.Binary binary) {
        if (binary.operator() != BinaryOperator.ADD) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        List<Expression> parts = new ArrayList<>();
        return appendText(binary, segments, parts) ? new Expression.Template(segments, parts, binary.span()) : null;
    }

    private static boolean appendText(Expression syntax, List<String> segments, List<Expression> parts) {
        switch (unwrap(syntax)) {
            case Expression.Binary binary when binary.operator() == BinaryOperator.ADD -> {
                return appendText(binary.left(), segments, parts) && appendText(binary.right(), segments, parts);
            }
            case Expression.Literal literal when literal.kind() == Expression.LiteralKind.STRING -> {
                appendSegment(segments, (String) literal.value());
                return true;
            }
            case Expression.Template template -> {
                appendSegment(segments, template.segments().getFirst());
                for (int i = 0; i < template.parts().size(); i++) {
                    parts.add(template.parts().get(i));
                    segments.add(template.segments().get(i + 1));
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void appendSegment(List<String> segments, String text) {
        if (segments.isEmpty()) {
            segments.add(text);
        } else {
            segments.set(segments.size() - 1, segments.getLast() + text);
        }
    }

    private static int argumentCost(Argument argument, Type parameter) {
        if (argument instanceof Argument.Deferred deferred) {
            if (deferred.emptyList()) {
                return parameter.nonNullable() instanceof ListType ? 0 : Conversions.NONE;
            }
            if (deferred.emptyMap()) {
                return parameter.nonNullable() instanceof MapType ? 0 : Conversions.NONE;
            }
            if (deferred.lambdaArity() >= 0) {
                return parameter.nonNullable() instanceof FunctionType function && function.arity() == deferred.lambdaArity()
                        ? 0 : Conversions.NONE;
            }
            if (deferred.lambdaArity() == -2) {
                return parameter.nonNullable() instanceof FunctionType ? 0 : Conversions.NONE;
            }
            if (deferred.text()) {
                Type target = parameter.nonNullable();
                if (target == Types.COMPONENT || (target instanceof ClassType classType && classType.isConstantText())) {
                    return 1;
                }
                return Conversions.cost(Types.STRING, parameter);
            }
        }
        if (argument instanceof Argument.Bound bound) {
            return literalCost(bound.expression(), parameter);
        }
        return Conversions.cost(argument.naturalType(), parameter);
    }

    private BoundExpression callFunctions(String display, List<FunctionSymbol> user, List<FunctionDeclaration> host,
                                          BoundExpression receiverValue, Expression.Call call) {
        List<Candidate> candidates = new ArrayList<>();
        user.forEach(symbol -> candidates.add(Candidate.of(symbol)));
        host.forEach(declaration -> candidates.add(Candidate.of(declaration)));
        List<Argument> arguments = new ArrayList<>();
        for (int i = 0; i < call.arguments().size(); i++) {
            Expression argument = call.arguments().get(i);
            Expression unwrapped = unwrap(argument);
            boolean collection = unwrapped instanceof Expression.ListLiteral list && !list.elements().isEmpty()
                    || unwrapped instanceof Expression.MapLiteral map && !map.entries().isEmpty();
            Type agreed = collection ? agreedParameter(candidates, i, call.arguments().size()) : null;
            // A list or map literal is typed by the parameter when every overload expects the same type, so
            // [player.uuid, "bread", 2.5] can be passed where a List<any?> is expected.
            arguments.add(agreed != null ? new Argument.Bound(bindValue(argument, agreed)) : prebind(argument));
        }
        if (arguments.stream().anyMatch(argument -> argument.naturalType().isError()
                && !(argument instanceof Argument.Deferred))) {
            bindDeferredForErrors(arguments);
            return new BoundExpression.Error(call.span());
        }

        List<Candidate> applicable = new ArrayList<>();
        List<int[]> costs = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!candidate.accepts(arguments.size())) {
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
        if (receiverValue != null) {
            converted.add(receiverValue);
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
        for (int i = arguments.size(); i < chosen.parameters().size(); i++) {
            Expression defaultSyntax = chosen.user().defaults().get(i);
            converted.add(constants.defaultValue(defaultSyntax, chosen.parameters().get(i),
                    "parameter '" + chosen.names().get(i) + "' of '" + display + "'"));
        }
        Type resultType = chosen.returnType();
        if (chosen.user() != null) {
            return new BoundExpression.FunctionCall(chosen.user(), converted, resultType, call.span());
        }
        warnDeprecated(chosen.host().deprecation().orElse(null), display + "(...)", call.span());
        return new BoundExpression.NativeCall(chosen.host().invocable(), converted, resultType, call.span());
    }

    /** The type of parameter {@code index} if every candidate taking {@code count} arguments declares the same one. */
    private static Type agreedParameter(List<Candidate> candidates, int index, int count) {
        Type agreed = null;
        for (Candidate candidate : candidates) {
            if (!candidate.accepts(count)) {
                continue;
            }
            Type parameter = candidate.parameters().get(index);
            if (agreed == null) {
                agreed = parameter;
            } else if (!agreed.equals(parameter)) {
                return null;
            }
        }
        return agreed;
    }

    private void bindDeferredForErrors(List<Argument> arguments) {
        for (Argument argument : arguments) {
            if (argument instanceof Argument.Deferred deferred && deferred.lambdaArity() < 0 && deferred.lambdaArity() != -2) {
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
        // Equal costs: prefer more specific parameter types (e.g. Player over Entity), then fewer defaults.
        boolean aToB = true;
        boolean bToA = true;
        for (int i = 0; i < costA.length; i++) {
            aToB &= Conversions.isAssignable(a.parameters().get(i), b.parameters().get(i));
            bToA &= Conversions.isAssignable(b.parameters().get(i), a.parameters().get(i));
        }
        if (aToB && !bToA) {
            return true;
        }
        return aToB && bToA && a.parameters().size() < b.parameters().size();
    }

    private void reportNoApplicable(String display, List<Candidate> candidates, List<Argument> arguments, Expression.Call call) {
        List<Candidate> sameArity = candidates.stream().filter(c -> c.accepts(arguments.size())).toList();
        StringJoiner received = new StringJoiner(", ", "(", ")");
        arguments.forEach(argument -> received.add(describeArgument(argument)));
        if (candidates.size() == 1) {
            Candidate only = candidates.getFirst();
            if (sameArity.isEmpty()) {
                int expected = only.parameters().size();
                String count = only.required == expected ? String.valueOf(expected) : only.required + " to " + expected;
                module.report(module.diagnostic(DiagnosticCode.WRONG_ARGUMENT_COUNT, call.span(),
                                "'" + display + "' expects " + count + " argument" + (expected == 1 ? "" : "s")
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
                            .expectedReceived(parameter.displayName(), describeArgument(argument));
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

    private static String describeArgument(Argument argument) {
        if (argument instanceof Argument.Deferred deferred) {
            if (deferred.emptyList()) {
                return "empty list";
            }
            if (deferred.emptyMap()) {
                return "empty map";
            }
            if (deferred.lambdaArity() >= 0) {
                return "lambda with " + deferred.lambdaArity() + " parameter" + (deferred.lambdaArity() == 1 ? "" : "s");
            }
            if (deferred.lambdaArity() == -2) {
                return "function";
            }
        }
        return argument.naturalType().displayName();
    }

    // ------------------------------------------------------------------- operators

    private BoundExpression bindUnary(Expression.Unary unary, Type expected) {
        if (unary.operator() == UnaryOperator.NOT) {
            Condition condition = bindCondition(unary);
            return condition.expression();
        }
        Expression operandSyntax = unwrap(unary.operand());
        if (unary.operator() == UnaryOperator.NEGATE && operandSyntax instanceof Expression.Literal literal
                && isNumericLiteral(literal)) {
            return bindLiteral(literal, expected, true, unary.span());
        }
        BoundExpression operand = bindValue(unary.operand(), expected);
        Type type = operand.type();
        if (type.isError()) {
            return new BoundExpression.Error(unary.span());
        }
        if (unary.operator() == UnaryOperator.BIT_NOT) {
            if (type == PrimitiveType.INT || type == PrimitiveType.LONG) {
                BoundExpression allOnes = new BoundExpression.Literal(type == PrimitiveType.INT ? (Object) (-1) : (Object) (-1L),
                        type, unary.span());
                return new BoundExpression.Arithmetic(ArithmeticOp.BIT_XOR, type.representation(), operand, allOnes, type,
                        unary.span());
            }
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.INVALID_OPERATOR, unary.span(),
                    "Operator '~' cannot be applied to a value of type " + type.displayName() + ".");
            if (type == PrimitiveType.BOOL) {
                builder.note("Use '!' to negate a condition.");
            }
            module.report(builder.build());
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
        if (expected != null && expected.nonNullable() == Types.COMPONENT) {
            Expression.Template joined = joinedText(binary);
            if (joined != null) {
                return bindTemplate(joined, expected);
            }
        }
        if (operator.isLogical()) {
            return bindCondition(binary).expression();
        }
        if (operator == BinaryOperator.COALESCE) {
            return bindCoalesce(binary, expected);
        }
        if (operator.isMembership()) {
            BoundExpression membership = builtins.membership(binary);
            return operator == BinaryOperator.NOT_IN && !membership.type().isError()
                    ? new BoundExpression.Not(membership, binary.span()) : membership;
        }
        if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL) {
            boolean leftNull = isNullLiteral(binary.left());
            boolean rightNull = isNullLiteral(binary.right());
            if (leftNull || rightNull) {
                return nullComparison(binary, leftNull ? binary.right() : binary.left(), operator == BinaryOperator.EQUAL);
            }
        }
        BoundExpression left = bindValue(binary.left(), null);
        Type hint = left.type() instanceof PrimitiveType primitive && primitive.isNumeric() ? primitive
                : left.type() instanceof ClassType classType && classType.isKeyed() ? classType : null;
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
        if (operator.isBitwise()) {
            return bitwise(operator, left, right, span);
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
        boolean aInstant = a == PrimitiveType.INSTANT;
        boolean bInstant = b == PrimitiveType.INSTANT;
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
        if (aInstant && bDuration && (op == ArithmeticOp.ADD || op == ArithmeticOp.SUBTRACT)) {
            return new BoundExpression.Arithmetic(op, Representation.LONG, left, right, PrimitiveType.INSTANT, span);
        }
        if (aDuration && bInstant && op == ArithmeticOp.ADD) {
            return new BoundExpression.Arithmetic(op, Representation.LONG, left, right, PrimitiveType.INSTANT, span);
        }
        if (aInstant && bInstant && op == ArithmeticOp.SUBTRACT) {
            return new BoundExpression.Arithmetic(op, Representation.LONG, left, right, PrimitiveType.DURATION, span);
        }
        return invalidOperator(operator, a, b, span);
    }

    private BoundExpression bitwise(BinaryOperator operator, BoundExpression left, BoundExpression right, Span span) {
        Type a = left.type();
        Type b = right.type();
        boolean aIntegral = a == PrimitiveType.INT || a == PrimitiveType.LONG;
        boolean bIntegral = b == PrimitiveType.INT || b == PrimitiveType.LONG;
        if (!aIntegral || !bIntegral) {
            Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.INVALID_OPERATOR, span,
                    "Operator '" + operator.symbol() + "' cannot be applied to " + a.displayName() + " and "
                            + b.displayName() + ".");
            if (a == PrimitiveType.BOOL || b == PrimitiveType.BOOL) {
                builder.note(operator == BinaryOperator.BIT_OR ? "Did you mean '||' (logical or)?"
                        : operator == BinaryOperator.BIT_AND ? "Did you mean '&&' (logical and)?"
                        : "Bitwise operators work on int and long values.");
            } else {
                builder.note("Bitwise operators work on int and long values.");
            }
            module.report(builder.build());
            return new BoundExpression.Error(span);
        }
        ArithmeticOp op = switch (operator) {
            case BIT_AND -> ArithmeticOp.BIT_AND;
            case BIT_OR -> ArithmeticOp.BIT_OR;
            case BIT_XOR -> ArithmeticOp.BIT_XOR;
            case SHIFT_LEFT -> ArithmeticOp.SHIFT_LEFT;
            case SHIFT_RIGHT -> ArithmeticOp.SHIFT_RIGHT;
            default -> ArithmeticOp.UNSIGNED_SHIFT_RIGHT;
        };
        boolean shift = op == ArithmeticOp.SHIFT_LEFT || op == ArithmeticOp.SHIFT_RIGHT || op == ArithmeticOp.UNSIGNED_SHIFT_RIGHT;
        // A shift keeps the type of its left operand (Java); other operators promote both sides.
        PrimitiveType type = shift ? (PrimitiveType) a : (a == PrimitiveType.LONG || b == PrimitiveType.LONG
                ? PrimitiveType.LONG : PrimitiveType.INT);
        BoundExpression l = Conversions.apply(left, type);
        BoundExpression r = shift && b != type
                ? (b == PrimitiveType.LONG ? new BoundExpression.Conversion(ConversionKind.NUMERIC, right, PrimitiveType.INT, right.span())
                : Conversions.apply(right, type))
                : Conversions.apply(right, type);
        return new BoundExpression.Arithmetic(op, type.representation(), l, r, type, span);
    }

    private void checkDivisionByZero(ArithmeticOp op, PrimitiveType type, BoundExpression divisor) {
        if ((op == ArithmeticOp.DIVIDE || op == ArithmeticOp.REMAINDER)
                && (type == PrimitiveType.INT || type == PrimitiveType.LONG)
                && divisor instanceof BoundExpression.Literal literal && ((Number) literal.value()).longValue() == 0) {
            module.error(DiagnosticCode.DIVISION_BY_ZERO, divisor.span(), "Division by zero.");
        }
    }

    /** Binary numeric promotion; {@code null} if either operand is not a numeric primitive. */
    static PrimitiveType promote(Type a, Type b) {
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
        if ((a == PrimitiveType.DURATION && b == PrimitiveType.DURATION) || (a == PrimitiveType.INSTANT && b == PrimitiveType.INSTANT)) {
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
        if (a == b && (a == PrimitiveType.BOOL || a == PrimitiveType.DURATION || a == PrimitiveType.INSTANT)) {
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
            builder.note("Strings can only be joined with '+' or compared with '==' and '!='. "
                    + "To put text in alphabetical order, sort a list: names.sort() or players.sortedBy(p => p.name).");
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

    private BoundExpression bindConditional(Expression.Conditional conditional, Type expected) {
        Condition condition = bindCondition(conditional.condition());
        Flow before = flow;
        flow = before.with(condition.facts().whenTrue());
        BoundExpression whenTrue = bindValue(conditional.whenTrue(), expected);
        flow = before.with(condition.facts().whenFalse());
        BoundExpression whenFalse = bindValue(conditional.whenFalse(), expected);
        flow = before;
        if (whenTrue.type().isError() || whenFalse.type().isError() || condition.expression().type().isError()) {
            return new BoundExpression.Error(conditional.span());
        }
        Type type = expected != null ? expected : unifyBranches(List.of(whenTrue, whenFalse), conditional.span());
        if (type.isError()) {
            return new BoundExpression.Error(conditional.span());
        }
        BoundExpression a = convert(whenTrue, type, conditional.whenTrue().span(), "the value of the conditional");
        BoundExpression b = convert(whenFalse, type, conditional.whenFalse().span(), "the value of the conditional");
        if (condition.expression() instanceof BoundExpression.Literal literal && literal.value() instanceof Boolean constant) {
            return constant ? a : b;
        }
        return new BoundExpression.Conditional(condition.expression(), a, b, type, conditional.span());
    }

    /** The common type of several branch values (null widens to a nullable type, numbers promote). */
    private Type unifyBranches(List<BoundExpression> values, Span span) {
        Type result = null;
        boolean nullable = false;
        for (BoundExpression value : values) {
            Type type = value.type();
            if (type.isError()) {
                return Types.ERROR;
            }
            if (type instanceof NullType) {
                nullable = true;
                continue;
            }
            if (type.isNullable()) {
                nullable = true;
                type = type.nonNullable();
            }
            if (result == null || Conversions.isAssignable(result, type)) {
                result = type;
            } else if (!Conversions.isAssignable(type, result)) {
                PrimitiveType promoted = promote(result, type);
                if (promoted == null) {
                    module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, value.span(),
                                    "The possible values have incompatible types " + result.displayName() + " and "
                                            + type.displayName() + ".")
                            .note("Convert one of them, or declare the type of the variable that receives the value.").build());
                    return Types.ERROR;
                }
                result = promoted;
            }
        }
        if (result == null) {
            module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, span,
                    "Cannot infer a type when every value is null.")
                    .note("Declare the type of the variable that receives the value.").build());
            return Types.ERROR;
        }
        return nullable ? Types.nullable(result) : result;
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
        BoundExpression test = new BoundExpression.TypeTest(operand, classType, module.record(classType), is.span());
        return is.negated() ? new BoundExpression.Not(test, is.span()) : test;
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
        if (source == PrimitiveType.DURATION && target == PrimitiveType.LONG || source == PrimitiveType.INSTANT && target == PrimitiveType.LONG
                || source == PrimitiveType.LONG && (target == PrimitiveType.DURATION || target == PrimitiveType.INSTANT)) {
            return new BoundExpression.Conversion(ConversionKind.REINTERPRET, operand, target, cast.span());
        }
        if (target instanceof ClassType classType && source.representation() == Representation.REF) {
            if (source.nonNullable() instanceof ClassType from && from.isSubtypeOf(classType) && !source.isNullable()) {
                return operand;
            }
            Type resultType = cast.safe() ? Types.nullable(classType) : classType;
            return new BoundExpression.Cast(operand, classType, module.record(classType), cast.safe(), resultType, cast.span());
        }
        // A value of unknown type (from json.parse, a map of any?, ...) as a list or map: the runtime checks
        // that it is one; the element types are trusted, like Java generics.
        if ((target instanceof ListType || target instanceof MapType) && source.representation() == Representation.REF
                && (source.nonNullable() == Types.ANY || source.nonNullable() instanceof ListType
                || source.nonNullable() instanceof MapType)) {
            ClassType check = target instanceof ListType ? Types.LIST_VALUE : Types.MAP_VALUE;
            Type resultType = cast.safe() ? Types.nullable(target) : target;
            return new BoundExpression.Cast(operand, check, null, cast.safe(), resultType, cast.span());
        }
        Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.INVALID_CAST, cast.span(),
                "Cannot convert " + source.displayName() + " to " + target.displayName() + ".");
        if (target.isNullable()) {
            builder.note("Use 'as?' to get null when the value is not a " + target.nonNullable().displayName() + ".");
        } else if (cast.safe()) {
            builder.note("'as?' works on reference types; numbers are converted with 'as'.");
        } else if (target == Types.STRING) {
            builder.note("Put the value in a template to get its text: \"{value}\"");
        }
        module.report(builder.build());
        return new BoundExpression.Error(cast.span());
    }

    // ------------------------------------------------------------------- lists and maps

    private BoundExpression bindIndex(Expression.Index index) {
        BoundExpression container = bindValue(index.target(), null);
        Type type = container.type();
        if (type.isError()) {
            bindValue(index.index(), null);
            return new BoundExpression.Error(index.span());
        }
        if (type instanceof ListType listType) {
            BoundExpression position = convert(bindValue(index.index(), PrimitiveType.INT), PrimitiveType.INT,
                    index.index().span(), "the list index");
            return new BoundExpression.ListGet(container, position, listType.element(), index.span());
        }
        if (type instanceof MapType mapType) {
            return builtins.mapGet(container, mapType, index.index(), index.span());
        }
        bindValue(index.index(), null);
        if (type.isNullable()) {
            reportNullableAccess(container, index.target(), "[...]");
        } else if (type == Types.STRING) {
            module.report(module.diagnostic(DiagnosticCode.INVALID_OPERATOR, index.span(),
                    "Text cannot be indexed with [...].").note("Use text.charAt(i) or text.substring(from, to).").build());
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
        Type elementType = expectedElement != null ? expectedElement : unify(elements, list.span(), "List elements");
        if (elementType.isError()) {
            return new BoundExpression.Error(list.span());
        }
        List<BoundExpression> converted = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            converted.add(convert(elements.get(i), elementType, list.elements().get(i).span(), "the list element"));
        }
        return new BoundExpression.ListLiteral(converted, Types.list(elementType), list.span());
    }

    private BoundExpression bindMap(Expression.MapLiteral map, Type expected) {
        MapType expectedMap = expected != null && expected.nonNullable() instanceof MapType mapType ? mapType : null;
        if (map.entries().isEmpty()) {
            if (expectedMap == null) {
                module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, map.span(),
                                "Cannot infer the key and value types of an empty map.")
                        .note("Declare the type, e.g. let coins: Map<string, int> = {}").build());
                return new BoundExpression.Error(map.span());
            }
            return new BoundExpression.MapLiteral(List.of(), List.of(), expectedMap, map.span());
        }
        List<BoundExpression> keys = new ArrayList<>();
        List<BoundExpression> values = new ArrayList<>();
        for (Expression.MapEntry entry : map.entries()) {
            keys.add(bindValue(entry.key(), expectedMap != null ? expectedMap.key() : null));
            values.add(bindValue(entry.value(), expectedMap != null ? expectedMap.value() : null));
        }
        Type keyType = expectedMap != null ? expectedMap.key() : unify(keys, map.span(), "Map keys");
        Type valueType = expectedMap != null ? expectedMap.value() : unify(values, map.span(), "Map values");
        if (keyType.isError() || valueType.isError()) {
            return new BoundExpression.Error(map.span());
        }
        if (keyType.isNullable()) {
            module.error(DiagnosticCode.TYPE_MISMATCH, map.span(), "Map keys cannot be null.");
            return new BoundExpression.Error(map.span());
        }
        List<BoundExpression> convertedKeys = new ArrayList<>();
        List<BoundExpression> convertedValues = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < keys.size(); i++) {
            BoundExpression key = convert(keys.get(i), keyType, map.entries().get(i).key().span(), "the map key");
            Object constant = ConstantEvaluator.evaluate(key, ConstantEvaluator.SILENT);
            if (constant != ConstantEvaluator.NOT_CONSTANT && !seen.add(Objects.requireNonNullElse(constant, "null"))) {
                module.report(module.diagnostic(DiagnosticCode.DUPLICATE_CASE, map.entries().get(i).key().span(),
                        "This key is already in the map; the later value replaces the earlier one.").build());
            }
            convertedKeys.add(key);
            convertedValues.add(convert(values.get(i), valueType, map.entries().get(i).value().span(), "the map value"));
        }
        return new BoundExpression.MapLiteral(convertedKeys, convertedValues, Types.map(keyType, valueType), map.span());
    }

    private Type unify(List<BoundExpression> elements, Span span, String what) {
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
                                    what + " have incompatible types " + result.displayName() + " and "
                                            + type.displayName() + ".")
                            .note(what.startsWith("Map")
                                    ? "Declare the type, e.g. let values: Map<string, any> = {...}"
                                    : "Declare the type, e.g. let values: List<any> = [...]").build());
                    return Types.ERROR;
                }
                result = promoted;
            }
        }
        if (result == null) {
            module.report(module.diagnostic(DiagnosticCode.TYPE_MISMATCH, span,
                    "Cannot infer the type when every value is null.")
                    .note("Declare the type, e.g. let values: List<Player?> = [null]").build());
            return Types.ERROR;
        }
        return nullable ? Types.nullable(result) : result;
    }

    // ------------------------------------------------------------------- diagnostics helpers

    void reportUnknownName(Identifier identifier) {
        String name = identifier.name();
        Span later = module.pendingGlobals().get(name);
        if (later != null) {
            module.report(module.diagnostic(DiagnosticCode.USED_BEFORE_DECLARATION, identifier.span(),
                            "'" + name + "' is used before its declaration.")
                    .label(later, "declared here")
                    .note("Top-level variables are initialized from top to bottom; move this declaration below it.")
                    .build());
            return;
        }
        List<String> candidates = new ArrayList<>(scope.visibleNames());
        candidates.addAll(module.constants().keySet());
        candidates.addAll(module.globals().keySet());
        candidates.addAll(module.functions().keySet());
        candidates.addAll(module.records().keySet());
        candidates.addAll(module.importedNames().keySet());
        candidates.addAll(module.moduleAliases().keySet());
        candidates.addAll(module.registry().namespaceMembers(""));
        Diagnostic.Builder builder = module.diagnostic(DiagnosticCode.UNKNOWN_NAME, identifier.span(),
                "Unknown name '" + name + "'.").suggestions(Suggestions.closest(name, new LinkedHashSet<>(candidates), 3));
        if (event == null && (name.equals("player") || name.equals("event"))) {
            builder.note("'" + name + "' is only available inside event handlers and commands that provide it.");
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

    private void reportUnknownMember(ClassType type, Identifier member, BoundExpression receiverValue) {
        String name = member.name();
        if (event != null && !event.isCancellable() && CANCEL_MEMBERS.contains(name)
                && receiverValue instanceof BoundExpression.LocalLoad load && load.local().kind() == LocalSymbol.Kind.EVENT_OBJECT) {
            module.report(module.diagnostic(DiagnosticCode.EVENT_NOT_CANCELLABLE, member.span(),
                    "Event '" + event.name() + "' cannot be cancelled.").build());
            return;
        }
        Set<String> names = new java.util.TreeSet<>(module.members().memberNames(type));
        RecordSymbol record = module.record(type);
        if (record != null) {
            record.fields().forEach(field -> names.add(field.name()));
            record.methods().forEach(method -> names.add(method.name()));
        }
        module.report(module.diagnostic(DiagnosticCode.UNKNOWN_MEMBER, member.span(),
                        "Unknown member '" + name + "' on " + type.name() + ".")
                .suggestions(Suggestions.closest(name, names, 3)).build());
    }

    void reportNullableAccess(BoundExpression receiverValue, Expression receiverSyntax, String member) {
        String name = describeValue(receiverValue);
        String text = receiverSyntax.span().length() <= 40 ? module.file().text(receiverSyntax.span()) : "value";
        if (member.equals("[...]")) {
            // There is no '?.[...]': index a checked copy instead.
            module.report(module.diagnostic(DiagnosticCode.NULLABLE_ACCESS, receiverSyntax.span(),
                            name + " may be null (type " + receiverValue.type().displayName() + ").")
                    .note("Check for null first:\n    let inner = " + text + "\n    if inner != null {\n        inner[...]\n    }")
                    .build());
            return;
        }
        module.report(module.diagnostic(DiagnosticCode.NULLABLE_ACCESS, receiverSyntax.span(),
                        name + " may be null (type " + receiverValue.type().displayName() + ").")
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

    static String describeKind(LocalSymbol local) {
        return switch (local.kind()) {
            case VARIABLE, TEMPORARY -> "variable";
            case PARAMETER -> "parameter";
            case LOOP_VARIABLE -> "loop variable";
            case EVENT_VARIABLE -> "event variable";
            case EVENT_OBJECT -> "the event object";
            case CAPTURE -> "captured variable";
            case THIS -> "the record";
            case IMPLICIT -> "built-in variable";
        };
    }

    String describeValue(BoundExpression expression) {
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

    static BoundStatement errorStatement(Span span) {
        return new BoundStatement.ExpressionStatement(new BoundExpression.Error(span), span);
    }

    static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current instanceof Expression.Parenthesized parenthesized) {
            current = parenthesized.inner();
        }
        return current;
    }

    /** A declaration's annotation argument as text, for diagnostics. */
    static String annotationName(Declaration.Annotation annotation) {
        return "@" + annotation.name().name();
    }
}
