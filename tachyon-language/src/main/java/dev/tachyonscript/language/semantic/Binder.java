package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.EventVariable;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.source.Span;
import dev.tachyonscript.language.syntax.Declaration;
import dev.tachyonscript.language.syntax.SourceUnit;
import dev.tachyonscript.language.util.Suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces the semantic model ({@link BoundModule}) of one file.
 *
 * <p>Binding runs in two passes so that declaration order never matters: first every
 * function signature and constant is collected, then constants are evaluated (on demand,
 * with cycle detection) and function and handler bodies are checked.
 */
public final class Binder implements BodyBinder.ConstantResolver {

    private final SourceUnit unit;
    private final ModuleContext context;

    private Binder(SourceUnit unit, SymbolRegistry registry, DiagnosticCollector diagnostics) {
        this.unit = unit;
        this.context = new ModuleContext(unit.file(), registry, diagnostics);
    }

    /** Binds {@code unit} against {@code registry}; problems are reported to {@code diagnostics}. */
    public static BoundModule bind(SourceUnit unit, SymbolRegistry registry, DiagnosticCollector diagnostics) {
        return new Binder(unit, registry, diagnostics).run();
    }

    /** Module name: the {@code module} declaration, or the file path without extension. */
    public static String moduleName(SourceUnit unit) {
        if (unit.module() != null) {
            return unit.module().name().text();
        }
        String path = unit.file().path();
        if (path.endsWith(".tys")) {
            path = path.substring(0, path.length() - 4);
        }
        return path.replace('/', '.');
    }

    private BoundModule run() {
        for (Declaration.Import anImport : unit.imports()) {
            context.report(context.diagnostic(DiagnosticCode.UNSUPPORTED_FEATURE, anImport.span(),
                    "Imports are not supported yet.").note("See docs/status.md for the module system roadmap.").build());
        }
        collect();
        List<ConstantSymbol> constants = new ArrayList<>(context.constants().values());
        for (ConstantSymbol constant : constants) {
            resolve(constant, constant.syntax().name().span());
        }
        List<BoundFunction> functions = new ArrayList<>();
        List<BoundEventHandler> handlers = new ArrayList<>();
        for (Declaration declaration : unit.declarations()) {
            switch (declaration) {
                case Declaration.Function function -> {
                    FunctionSymbol symbol = symbolOf(function);
                    if (symbol != null) {
                        functions.add(bindFunction(function, symbol));
                    }
                }
                case Declaration.Event event -> {
                    BoundEventHandler handler = bindHandler(event);
                    if (handler != null) {
                        handlers.add(handler);
                    }
                }
                default -> {
                }
            }
        }
        return new BoundModule(unit.file(), moduleName(unit), functions, handlers, constants);
    }

    // ------------------------------------------------------------------ collection

    private final List<FunctionSymbol> declaredFunctions = new ArrayList<>();

    private void collect() {
        for (Declaration declaration : unit.declarations()) {
            switch (declaration) {
                case Declaration.Function function -> collectFunction(function);
                case Declaration.Const constant -> collectConstant(constant);
                default -> {
                }
            }
        }
    }

    private void collectFunction(Declaration.Function function) {
        String name = function.name().name();
        List<String> names = new ArrayList<>();
        List<Type> types = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Declaration.Parameter parameter : function.parameters()) {
            String parameterName = parameter.name().name();
            if (!seen.add(parameterName)) {
                context.error(DiagnosticCode.DUPLICATE_DECLARATION, parameter.name().span(),
                        "Duplicate parameter '" + parameterName + "'.");
            }
            names.add(parameterName);
            types.add(context.types().resolve(parameter.type(), false));
        }
        Type returnType = function.returnType() == null ? PrimitiveType.VOID : context.types().resolve(function.returnType(), true);
        FunctionSymbol symbol = new FunctionSymbol(name, names, types, returnType, function);
        if (context.constants().containsKey(name)) {
            context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, function.name().span(),
                            "'" + name + "' is already declared as a constant.")
                    .label(context.constants().get(name).syntax().name().span(), "constant declared here").build());
            return;
        }
        for (FunctionSymbol existing : context.functions(name)) {
            if (existing.parameterTypes().equals(types)) {
                context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, function.name().span(),
                                "Function '" + symbol.key() + "' is already declared.")
                        .label(existing.syntax().name().span(), "first declared here").build());
                return;
            }
        }
        context.addFunction(symbol);
        declaredFunctions.add(symbol);
    }

    private FunctionSymbol symbolOf(Declaration.Function function) {
        for (FunctionSymbol symbol : declaredFunctions) {
            if (symbol.syntax() == function) {
                return symbol;
            }
        }
        return null;
    }

    private void collectConstant(Declaration.Const constant) {
        String name = constant.name().name();
        if (context.constants().containsKey(name)) {
            context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, constant.name().span(),
                            "Constant '" + name + "' is already declared.")
                    .label(context.constants().get(name).syntax().name().span(), "first declared here").build());
            return;
        }
        if (!context.functions(name).isEmpty()) {
            context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, constant.name().span(),
                            "'" + name + "' is already declared as a function.")
                    .label(context.functions(name).getFirst().syntax().name().span(), "function declared here").build());
            return;
        }
        context.constants().put(name, new ConstantSymbol(name, constant));
    }

    // ------------------------------------------------------------------ constants

    @Override
    public BoundExpression constant(ConstantSymbol constant, Span use) {
        resolve(constant, use);
        if (constant.state() != ConstantSymbol.State.RESOLVED) {
            return new BoundExpression.Error(use);
        }
        return new BoundExpression.Literal(constant.value(), constant.type(), use);
    }

    private void resolve(ConstantSymbol constant, Span use) {
        switch (constant.state()) {
            case RESOLVED, FAILED -> {
                return;
            }
            case RESOLVING -> {
                context.report(context.diagnostic(DiagnosticCode.CONSTANT_CYCLE, use,
                                "Constant '" + constant.name() + "' depends on itself.")
                        .label(constant.syntax().name().span(), "declared here").build());
                constant.state(ConstantSymbol.State.FAILED);
                return;
            }
            case UNRESOLVED -> {
            }
        }
        constant.state(ConstantSymbol.State.RESOLVING);
        Declaration.Const syntax = constant.syntax();
        Type declared = syntax.type() != null ? context.types().resolve(syntax.type(), false) : null;
        BodyBinder binder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null,
                "constant '" + constant.name() + "'");
        BoundExpression value = binder.bindValue(syntax.value(), declared);
        if (declared != null) {
            value = binder.convert(value, declared, syntax.value().span(), "constant '" + constant.name() + "'");
        }
        if (constant.state() == ConstantSymbol.State.FAILED || value.type().isError()) {
            constant.state(ConstantSymbol.State.FAILED);
            return;
        }
        Type type = declared != null ? declared : value.type();
        if (!ConstantEvaluator.isConstantType(type)) {
            context.report(context.diagnostic(DiagnosticCode.NOT_CONSTANT, syntax.value().span(),
                            "Constants must be numbers, booleans, durations or strings, not " + type.displayName() + ".")
                    .note(type == Types.COMPONENT
                            ? "Declare a string constant; it converts to a Component where a message is expected."
                            : "Compute the value inside a function instead.").build());
            constant.state(ConstantSymbol.State.FAILED);
            return;
        }
        Object result = ConstantEvaluator.evaluate(value, new ConstantEvaluator.Problems() {
            @Override
            public void overflow(BoundExpression expression) {
                context.report(context.diagnostic(DiagnosticCode.LITERAL_OUT_OF_RANGE, expression.span(),
                                "Integer overflow in the value of constant '" + constant.name() + "'.")
                        .note("Use long values (e.g. 1L) for larger numbers.").build());
            }

            @Override
            public void divisionByZero(BoundExpression expression) {
                if (!context.hasErrorsAt(expression.span())) {
                    context.error(DiagnosticCode.DIVISION_BY_ZERO, expression.span(), "Division by zero.");
                }
            }
        });
        if (result == ConstantEvaluator.NOT_CONSTANT) {
            if (!context.hasErrorsAt(syntax.value().span())) {
                context.report(context.diagnostic(DiagnosticCode.NOT_CONSTANT, syntax.value().span(),
                                "The value of constant '" + constant.name() + "' must be known when the script loads.")
                        .note("Constants may only use literals, other constants and operators. "
                                + "Use a variable inside a function for computed values.").build());
            }
            constant.state(ConstantSymbol.State.FAILED);
            return;
        }
        constant.resolve(type, result);
    }

    // ------------------------------------------------------------------ bodies

    private BoundFunction bindFunction(Declaration.Function function, FunctionSymbol symbol) {
        Scope root = new Scope(null);
        List<LocalSymbol> parameters = new ArrayList<>();
        for (int i = 0; i < function.parameters().size(); i++) {
            Declaration.Parameter parameter = function.parameters().get(i);
            LocalSymbol local = new LocalSymbol(parameter.name().name(), symbol.parameterTypes().get(i), false,
                    LocalSymbol.Kind.PARAMETER, parameter.name().span(), null);
            parameters.add(local);
            if (root.lookupHere(local.name()) == null) {
                root.declare(local);
            }
        }
        String owner = "function '" + symbol.name() + "'";
        BodyBinder binder = new BodyBinder(context, this, root, symbol.returnType(), null, owner);
        BoundStatement.Block body = binder.bindBlock(function.body(), true);
        if (symbol.returnType() != PrimitiveType.VOID && !symbol.returnType().isError()
                && Reachability.completesNormally(body)) {
            int end = Math.max(function.body().span().start(), function.body().span().end() - 1);
            context.report(context.diagnostic(DiagnosticCode.MISSING_RETURN, new Span(end, function.body().span().end()),
                            "Function '" + symbol.name() + "' must return a value of type "
                                    + symbol.returnType().displayName() + " on every path.")
                    .label(function.name().span(), "function declared here").build());
        }
        return new BoundFunction(symbol, parameters, body, function.span());
    }

    private BoundEventHandler bindHandler(Declaration.Event declaration) {
        String name = declaration.name().text();
        EventDeclaration event = context.registry().event(name).orElse(null);
        if (event == null) {
            context.report(Diagnostic.builder(DiagnosticCode.UNKNOWN_EVENT, context.file(), declaration.name().span(),
                            "Unknown event '" + name + "'.")
                    .suggestions(Suggestions.closest(name, context.registry().eventNames(), 3)).build());
            return null;
        }
        Scope root = new Scope(null);
        LocalSymbol eventObject = new LocalSymbol(EventDeclaration.EVENT_OBJECT_VARIABLE, event.eventType(), false,
                LocalSymbol.Kind.EVENT_OBJECT, declaration.name().span(), null);
        root.declare(eventObject);
        List<LocalSymbol> variables = new ArrayList<>();
        for (EventVariable variable : event.variables()) {
            LocalSymbol local = new LocalSymbol(variable.name(), variable.type(), false, LocalSymbol.Kind.EVENT_VARIABLE,
                    declaration.name().span(), variable);
            variables.add(local);
            root.declare(local);
        }
        BodyBinder binder = new BodyBinder(context, this, root, PrimitiveType.VOID, event, "event handler '" + name + "'");
        BoundStatement.Block body = binder.bindBlock(declaration.body(), true);
        List<LocalSymbol> used = variables.stream().filter(LocalSymbol::isRead).toList();
        return new BoundEventHandler(event, eventObject, used, body, declaration.span());
    }
}
