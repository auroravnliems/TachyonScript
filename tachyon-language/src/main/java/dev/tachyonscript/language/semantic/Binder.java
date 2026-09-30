package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.EventVariable;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.FunctionType;
import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.MapType;
import dev.tachyonscript.api.type.NullType;
import dev.tachyonscript.api.type.NullableType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.source.Span;
import dev.tachyonscript.language.syntax.Declaration;
import dev.tachyonscript.language.syntax.Expression;
import dev.tachyonscript.language.syntax.Identifier;
import dev.tachyonscript.language.syntax.SourceUnit;
import dev.tachyonscript.language.util.Suggestions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Produces the semantic model ({@link BoundModule}) of one file.
 *
 * <p>Binding runs in passes so that declaration order never matters for functions, records,
 * constants and commands: first imports are resolved and every record, function signature and
 * constant is collected; then constants are evaluated (on demand, with cycle detection); then
 * top-level variables are bound in declaration order (their initializers run in that order);
 * finally every body is checked.
 */
public final class Binder implements BodyBinder.ConstantResolver {

    /** Annotations accepted on each kind of declaration. */
    private static final Set<String> EVENT_ANNOTATIONS = Set.of("priority", "ignoreCancelled");
    private static final Set<String> COMMAND_ANNOTATIONS = Set.of("permission", "permissionMessage", "aliases",
            "description", "usage", "cooldown", "cooldownMessage", "cooldownBypass", "playerOnly");
    private static final Set<String> TASK_ANNOTATIONS = Set.of("async");

    private final SourceUnit unit;
    private final ModuleContext context;
    private final Map<String, BoundModule> available;
    private final List<FunctionSymbol> declaredFunctions = new ArrayList<>();
    private final Map<Expression, BoundExpression> defaults = new IdentityHashMap<>();

    private final Set<String> failedModules;

    private Binder(SourceUnit unit, SymbolRegistry registry, DiagnosticCollector diagnostics,
                   Map<String, BoundModule> available, Set<String> failedModules) {
        this.unit = unit;
        this.context = new ModuleContext(unit.file(), moduleName(unit), registry, diagnostics);
        this.available = available;
        this.failedModules = failedModules;
    }

    /** Binds {@code unit} against {@code registry}; problems are reported to {@code diagnostics}. */
    public static BoundModule bind(SourceUnit unit, SymbolRegistry registry, DiagnosticCollector diagnostics) {
        return bind(unit, registry, diagnostics, Map.of());
    }

    /**
     * Binds {@code unit}; {@code modules} are the already bound modules it may import, by name.
     */
    public static BoundModule bind(SourceUnit unit, SymbolRegistry registry, DiagnosticCollector diagnostics,
                                   Map<String, BoundModule> modules) {
        return bind(unit, registry, diagnostics, modules, Set.of());
    }

    /**
     * Binds {@code unit}; {@code modules} are the already bound modules it may import, by name, and
     * {@code failedModules} names modules that exist but have errors (importing them is reported as such).
     */
    public static BoundModule bind(SourceUnit unit, SymbolRegistry registry, DiagnosticCollector diagnostics,
                                   Map<String, BoundModule> modules, Set<String> failedModules) {
        return new Binder(unit, registry, diagnostics, modules, failedModules).run();
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

    /** Names of the modules {@code unit} imports, in order (for the module graph). */
    public static List<String> importedModules(SourceUnit unit) {
        List<String> names = new ArrayList<>();
        for (Declaration.Import anImport : unit.imports()) {
            String name = anImport.module().text();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private BoundModule run() {
        resolveImports();
        collect();
        List<ConstantSymbol> constantList = new ArrayList<>(context.constants().values());
        for (ConstantSymbol constant : constantList) {
            resolve(constant, constant.syntax().name().span());
        }
        List<GlobalSymbol> globals = new ArrayList<>();
        Map<GlobalSymbol, BoundFunction> playerDefaults = new LinkedHashMap<>();
        BoundFunction initializer = bindGlobals(globals, playerDefaults);

        List<BoundFunction> functions = new ArrayList<>();
        List<BoundEventHandler> handlers = new ArrayList<>();
        List<BoundCommand> commands = new ArrayList<>();
        List<BoundTask> tasks = new ArrayList<>();
        List<BoundFunction> loadHooks = new ArrayList<>();
        List<BoundFunction> unloadHooks = new ArrayList<>();
        List<BoundPlaceholder> placeholders = new ArrayList<>();
        Set<String> commandPaths = new HashSet<>();
        Set<String> placeholderNames = new HashSet<>();
        int taskIndex = 0;
        int hookIndex = 0;
        for (Declaration declaration : unit.declarations()) {
            switch (declaration) {
                case Declaration.Function function -> {
                    FunctionSymbol symbol = symbolOf(function);
                    if (symbol != null) {
                        functions.add(bindFunction(function, symbol));
                    }
                }
                case Declaration.Record record -> {
                    RecordSymbol symbol = context.records().get(record.name().name());
                    if (symbol != null && symbol.syntax() == record) {
                        for (Declaration.Function method : record.methods()) {
                            FunctionSymbol methodSymbol = symbolOf(method);
                            if (methodSymbol != null) {
                                functions.add(bindFunction(method, methodSymbol));
                            }
                        }
                    }
                }
                case Declaration.Event event -> {
                    BoundEventHandler handler = bindHandler(event);
                    if (handler != null) {
                        handlers.add(handler);
                    }
                }
                case Declaration.Command command -> {
                    BoundCommand bound = bindCommand(command, commandPaths);
                    if (bound != null) {
                        commands.add(bound);
                    }
                }
                case Declaration.Task task -> {
                    BoundTask bound = bindTask(task, taskIndex++);
                    if (bound != null) {
                        tasks.add(bound);
                    }
                }
                case Declaration.Lifecycle lifecycle -> {
                    BoundFunction hook = bindHook(lifecycle, hookIndex++);
                    (lifecycle.load() ? loadHooks : unloadHooks).add(hook);
                }
                case Declaration.Placeholder placeholder -> {
                    BoundPlaceholder bound = bindPlaceholder(placeholder, placeholderNames);
                    if (bound != null) {
                        placeholders.add(bound);
                    }
                }
                default -> {
                }
            }
        }
        return new BoundModule(unit.file(), context.moduleName(), functions, handlers, constantList, globals,
                new ArrayList<>(context.records().values()), commands, tasks, loadHooks, unloadHooks, placeholders,
                initializer, playerDefaults, importedModules(unit));
    }

    // ------------------------------------------------------------------ imports

    private void resolveImports() {
        for (Declaration.Import anImport : unit.imports()) {
            String target = anImport.module().text();
            if (target.equals(context.moduleName())) {
                context.error(DiagnosticCode.IMPORT_CYCLE, anImport.module().span(), "A module cannot import itself.");
                continue;
            }
            BoundModule module = available.get(target);
            if (module == null && failedModules.contains(target)) {
                context.report(context.diagnostic(DiagnosticCode.UNKNOWN_MODULE, anImport.module().span(),
                                "Module '" + target + "' has errors, so it cannot be imported.")
                        .note("Fix the errors reported for that module first.").build());
                continue;
            }
            if (module == null) {
                context.report(context.diagnostic(DiagnosticCode.UNKNOWN_MODULE, anImport.module().span(),
                                "Unknown module '" + target + "'.")
                        .suggestions(Suggestions.closest(target, available.keySet(), 3))
                        .note("A module is a script file: import economy reads economy.tys (or a file declaring "
                                + "'module economy'). The imported file must compile without errors.").build());
                continue;
            }
            if (anImport.names().isEmpty()) {
                String alias = anImport.alias() != null ? anImport.alias().name()
                        : target.substring(target.lastIndexOf('.') + 1);
                if (context.moduleAliases().put(alias, module) != null) {
                    context.error(DiagnosticCode.DUPLICATE_DECLARATION, anImport.span(),
                            "'" + alias + "' is already the name of another import.");
                }
                continue;
            }
            for (Identifier name : anImport.names()) {
                Object member = module.member(name.name());
                if (member == null) {
                    List<String> names = new ArrayList<>();
                    module.constants().forEach(c -> names.add(c.name()));
                    module.globals().forEach(g -> names.add(g.name()));
                    module.records().forEach(r -> names.add(r.name()));
                    module.functions().forEach(f -> {
                        if (f.symbol() != null && !f.symbol().isMethod()) {
                            names.add(f.symbol().name());
                        }
                    });
                    context.report(context.diagnostic(DiagnosticCode.UNKNOWN_NAME, name.span(),
                                    "Module '" + target + "' has no '" + name.name() + "'.")
                            .suggestions(Suggestions.closest(name.name(), names, 3)).build());
                    continue;
                }
                if (context.importedNames().put(name.name(), member) != null) {
                    context.error(DiagnosticCode.DUPLICATE_DECLARATION, name.span(),
                            "'" + name.name() + "' is already imported.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ collection

    private void collect() {
        for (Declaration declaration : unit.declarations()) {
            if (declaration instanceof Declaration.Record record) {
                collectRecord(record);
            }
        }
        for (RecordSymbol record : context.records().values()) {
            collectFields(record);
        }
        for (Declaration declaration : unit.declarations()) {
            switch (declaration) {
                case Declaration.Function function -> collectFunction(function, null);
                case Declaration.Const constant -> collectConstant(constant);
                case Declaration.Record record -> {
                    RecordSymbol symbol = context.records().get(record.name().name());
                    if (symbol != null && symbol.syntax() == record) {
                        for (Declaration.Function method : record.methods()) {
                            collectFunction(method, symbol);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void collectRecord(Declaration.Record record) {
        String name = record.name().name();
        if (context.records().containsKey(name)) {
            context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, record.name().span(),
                            "Record '" + name + "' is already declared.")
                    .label(context.records().get(name).syntax().name().span(), "first declared here").build());
            return;
        }
        if (context.registry().type(name).isPresent() || SymbolRegistry.isReservedTypeName(name)) {
            context.error(DiagnosticCode.DUPLICATE_DECLARATION, record.name().span(),
                    "'" + name + "' is already the name of a built-in type.");
            return;
        }
        if (!Character.isUpperCase(name.charAt(0))) {
            context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, record.name().span(),
                            "Record names start with a capital letter.")
                    .suggestions(List.of(Character.toUpperCase(name.charAt(0)) + name.substring(1))).build());
            return;
        }
        ClassType type = ClassType.builder(name).scriptDefined(context.moduleName())
                .doc(record.documentation().isEmpty() ? "A record declared in " + unit.file().path() : record.documentation())
                .build();
        context.addRecord(new RecordSymbol(context.moduleName(), type, record));
    }

    private void collectFields(RecordSymbol record) {
        Set<String> seen = new HashSet<>();
        int index = 0;
        boolean sawDefault = false;
        for (Declaration.RecordField field : record.syntax().fields()) {
            String name = field.name().name();
            if (!seen.add(name)) {
                context.error(DiagnosticCode.DUPLICATE_DECLARATION, field.name().span(), "Duplicate field '" + name + "'.");
                continue;
            }
            Type type = context.types().resolve(field.type(), false);
            if (field.defaultValue() == null && sawDefault) {
                context.error(DiagnosticCode.MISSING_INITIALIZER, field.name().span(),
                        "Fields without a default value must come before fields with one.");
            }
            sawDefault |= field.defaultValue() != null;
            record.addField(new RecordSymbol.Field(name, type, index++, field));
        }
        if (record.syntax().fields().isEmpty()) {
            context.error(DiagnosticCode.MISSING_INITIALIZER, record.syntax().name().span(),
                    "A record needs at least one field.");
        }
    }

    private void collectFunction(Declaration.Function function, RecordSymbol owner) {
        String name = function.name().name();
        List<String> names = new ArrayList<>();
        List<Type> types = new ArrayList<>();
        List<Expression> defaultValues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean sawDefault = false;
        for (Declaration.Parameter parameter : function.parameters()) {
            String parameterName = parameter.name().name();
            if (!seen.add(parameterName)) {
                context.error(DiagnosticCode.DUPLICATE_DECLARATION, parameter.name().span(),
                        "Duplicate parameter '" + parameterName + "'.");
            }
            if (parameter.rest()) {
                context.report(context.diagnostic(DiagnosticCode.UNEXPECTED_TOKEN, parameter.span(),
                                "'...' can only be used on the last parameter of a command.")
                        .note("For a function, take a list: " + parameterName + ": List<string>").build());
            }
            if (parameter.defaultValue() == null && sawDefault) {
                context.error(DiagnosticCode.MISSING_INITIALIZER, parameter.name().span(),
                        "Parameters without a default value must come before parameters with one.");
            }
            sawDefault |= parameter.defaultValue() != null;
            names.add(parameterName);
            types.add(context.types().resolve(parameter.type(), false));
            defaultValues.add(parameter.defaultValue());
        }
        Type returnType = function.returnType() == null ? PrimitiveType.VOID : context.types().resolve(function.returnType(), true);
        FunctionSymbol symbol = new FunctionSymbol(context.moduleName(), name, names, types, defaultValues, returnType,
                function, owner);
        if (!function.annotations().isEmpty()) {
            for (Declaration.Annotation annotation : function.annotations()) {
                context.error(DiagnosticCode.INVALID_ANNOTATION, annotation.span(),
                        "Unknown annotation '@" + annotation.name().name() + "' on a function.");
            }
        }
        if (owner != null) {
            if (owner.field(name).isPresent()) {
                context.error(DiagnosticCode.DUPLICATE_DECLARATION, function.name().span(),
                        "'" + name + "' is already a field of record '" + owner.name() + "'.");
                return;
            }
            for (FunctionSymbol existing : owner.methods(name)) {
                if (existing.parameterTypes().equals(types)) {
                    context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, function.name().span(),
                                    "Method '" + symbol.key() + "' is already declared.")
                            .label(existing.syntax().name().span(), "first declared here").build());
                    return;
                }
            }
            owner.addMethod(symbol);
            declaredFunctions.add(symbol);
            return;
        }
        if (context.constants().containsKey(name)) {
            context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, function.name().span(),
                            "'" + name + "' is already declared as a constant.")
                    .label(context.constants().get(name).syntax().name().span(), "constant declared here").build());
            return;
        }
        if (context.records().containsKey(name) || context.importedNames().containsKey(name)) {
            context.error(DiagnosticCode.DUPLICATE_DECLARATION, function.name().span(),
                    "'" + name + "' is already declared as a record or imported name.");
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
        if (context.records().containsKey(name) || context.importedNames().containsKey(name)) {
            context.error(DiagnosticCode.DUPLICATE_DECLARATION, constant.name().span(),
                    "'" + name + "' is already declared as a record or imported name.");
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

    @Override
    public BoundExpression defaultValue(Expression syntax, Type type, String what) {
        BoundExpression cached = defaults.get(syntax);
        if (cached != null) {
            return cached;
        }
        BodyBinder binder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null,
                "the default value of " + what);
        BoundExpression value = binder.convert(binder.bindValue(syntax, type), type, syntax.span(),
                "the default value of " + what);
        defaults.put(syntax, value);
        return value;
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
            Diagnostic.Builder builder = context.diagnostic(DiagnosticCode.NOT_CONSTANT, syntax.value().span(),
                    "Constants must be numbers, booleans, durations or strings, not " + type.displayName() + ".");
            if (type == Types.COMPONENT) {
                builder.note("Declare a string constant; it converts to a Component where a message is expected.");
            } else {
                builder.note("Use a top-level variable instead: let " + constant.name() + " = ...");
            }
            context.report(builder.build());
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
                                + "Use a top-level variable for computed values: let " + constant.name() + " = ...")
                        .build());
            }
            constant.state(ConstantSymbol.State.FAILED);
            return;
        }
        constant.resolve(type, result);
    }

    // ------------------------------------------------------------------ top-level variables

    /**
     * Binds the top-level variables in declaration order into the module initializer, creating
     * their symbols as their types become known.
     */
    private BoundFunction bindGlobals(List<GlobalSymbol> globals, Map<GlobalSymbol, BoundFunction> playerDefaults) {
        List<Declaration.Global> declarations = new ArrayList<>();
        for (Declaration declaration : unit.declarations()) {
            if (declaration instanceof Declaration.Global global) {
                declarations.add(global);
            }
        }
        if (declarations.isEmpty()) {
            return null;
        }
        Set<String> assigned = new HashSet<>();
        BodyBinder binder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null,
                "the initializer of the top-level variables", null, assigned);
        binder.initializerMode();
        List<BoundStatement> statements = new ArrayList<>();
        Map<String, Span> pending = context.pendingGlobals();
        for (Declaration.Global declaration : declarations) {
            pending.putIfAbsent(declaration.name().name(), declaration.name().span());
        }
        int order = 0;
        for (Declaration.Global declaration : declarations) {
            try {
                order = bindGlobal(declaration, binder, order, globals, statements, playerDefaults);
            } finally {
                pending.remove(declaration.name().name(), declaration.name().span());
            }
        }
        Span span = declarations.getFirst().span().to(declarations.getLast().span());
        return new BoundFunction("$init", "initializer of " + unit.file().path(), List.of(), PrimitiveType.VOID,
                new BoundStatement.Block(statements, span), span, null);
    }

    /** Binds one top-level variable; returns the next free variable position. */
    private int bindGlobal(Declaration.Global declaration, BodyBinder binder, int order, List<GlobalSymbol> globals,
                           List<BoundStatement> statements, Map<GlobalSymbol, BoundFunction> playerDefaults) {
        String name = declaration.name().name();
        for (Declaration.Annotation annotation : declaration.annotations()) {
            context.error(DiagnosticCode.INVALID_ANNOTATION, annotation.span(),
                    "Unknown annotation '@" + annotation.name().name() + "' on a variable.");
        }
        if (!checkGlobalName(declaration)) {
            return order;
        }
        Type declared = declaration.type() != null ? context.types().resolve(declaration.type(), false) : null;
        BoundExpression value;
        Type type;
        if (declaration.storage() == Declaration.Storage.PLAYERDATA) {
            BodyBinder defaultBinder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null,
                    "the default value of '" + name + "'");
            value = defaultBinder.bindValue(declaration.initializer(), declared);
        } else {
            value = binder.bindValue(declaration.initializer(), declared);
        }
        if (declared != null) {
            value = binder.convert(value, declared, declaration.initializer().span(), "variable '" + name + "'");
            type = declared;
        } else {
            type = value.type();
            if (type instanceof NullType) {
                context.report(context.diagnostic(DiagnosticCode.TYPE_MISMATCH, declaration.initializer().span(),
                                "Cannot infer the type of '" + name + "' from 'null'.")
                        .note("Declare the type explicitly, e.g. var " + name + ": Player? = null").build());
                type = Types.ERROR;
            }
        }
        if (type.isError()) {
            // Keep the name known so that its uses do not report it as unknown.
            keepFailed(declaration, Types.ERROR);
            return order;
        }
        if (declaration.storage() != Declaration.Storage.SCRIPT && !isStorable(type, new HashSet<>())) {
            context.report(context.diagnostic(DiagnosticCode.NOT_STORABLE, declaration.type() != null
                                    ? declaration.type().span() : declaration.name().span(),
                            "Values of type " + type.displayName() + " cannot be saved.")
                    .note("Saved variables can hold numbers, bool, string, Duration, Instant, UUID, Location, "
                            + "ItemStack, world and Minecraft constants, records of those, and lists and maps of them.")
                    .build());
            keepFailed(declaration, type);
            return order;
        }
        GlobalSymbol global = new GlobalSymbol(context.moduleName(), name, type, declaration.mutable(),
                declaration.storage(), declaration.name().span(), declaration, order);
        if (declaration.storage() == Declaration.Storage.PLAYERDATA && conflictsWithPlayerMember(global)) {
            return order;
        }
        order++;
        context.globals().put(name, global);
        globals.add(global);
        switch (declaration.storage()) {
            case SCRIPT -> statements.add(new BoundStatement.GlobalStore(global, value, declaration.span()));
            case PERSISTENT -> statements.add(new BoundStatement.If(
                    new BoundExpression.Not(new BoundExpression.GlobalRestore(global, declaration.span()), declaration.span()),
                    new BoundStatement.GlobalStore(global, value, declaration.span()), null, declaration.span()));
            case PLAYERDATA -> playerDefaults.put(global, new BoundFunction("$default:" + name,
                    "default value of playerdata '" + name + "'", List.of(), type,
                    new BoundStatement.Block(List.of(new BoundStatement.Return(value, declaration.span())),
                            declaration.span()), declaration.span(), null));
        }
        global.markInitialized();
        return order;
    }

    /** Registers a variable whose declaration has errors, so that later uses are not reported again. */
    private void keepFailed(Declaration.Global declaration, Type type) {
        GlobalSymbol failed = new GlobalSymbol(context.moduleName(), declaration.name().name(), type, true,
                declaration.storage(), declaration.name().span(), declaration, -1);
        failed.markInitialized();
        context.globals().put(declaration.name().name(), failed);
    }

    private boolean checkGlobalName(Declaration.Global declaration) {
        String name = declaration.name().name();
        Span span = declaration.name().span();
        if (context.globals().containsKey(name)) {
            context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, span, "'" + name + "' is already declared.")
                    .label(context.globals().get(name).declaration(), "first declared here").build());
            return false;
        }
        if (context.constants().containsKey(name) || !context.functions(name).isEmpty()
                || context.records().containsKey(name) || context.importedNames().containsKey(name)) {
            context.error(DiagnosticCode.DUPLICATE_DECLARATION, span,
                    "'" + name + "' is already declared as a constant, function, record or imported name.");
            return false;
        }
        return true;
    }

    private boolean conflictsWithPlayerMember(GlobalSymbol global) {
        for (String typeName : List.of("Player", "OfflinePlayer")) {
            ClassType type = context.standardType(typeName);
            if (type != null && context.members().lookup(type, global.name()).found()) {
                context.report(context.diagnostic(DiagnosticCode.DUPLICATE_DECLARATION, global.declaration(),
                                "'" + global.name() + "' is already a member of " + typeName + ".")
                        .note("Choose another name for the saved value, e.g. " + global.name() + "Saved").build());
                return true;
            }
        }
        if (context.standardType("Player") == null && context.standardType("OfflinePlayer") == null) {
            context.error(DiagnosticCode.MISSING_STANDARD_TYPE, global.declaration(),
                    "'playerdata' needs the Player type of the standard library.");
            return true;
        }
        return false;
    }

    /** Whether values of {@code type} can be saved (records: when all their fields can). */
    private boolean isStorable(Type type, Set<ClassType> visiting) {
        return switch (type) {
            case PrimitiveType primitive -> primitive != PrimitiveType.VOID;
            case NullableType nullable -> isStorable(nullable.inner(), visiting);
            case ListType list -> isStorable(list.element(), visiting);
            case MapType map -> isStorable(map.key(), visiting) && isStorable(map.value(), visiting);
            case FunctionType ignored -> false;
            case ClassType classType -> {
                if (!classType.isScriptDefined()) {
                    yield classType.isStorable() || classType.isKeyed();
                }
                RecordSymbol record = context.record(classType);
                if (record == null || !visiting.add(classType)) {
                    yield false;
                }
                boolean all = true;
                for (RecordSymbol.Field field : record.fields()) {
                    all &= isStorable(field.type(), visiting);
                }
                visiting.remove(classType);
                yield all;
            }
            default -> false;
        };
    }

    // ------------------------------------------------------------------ bodies

    private BoundFunction bindFunction(Declaration.Function function, FunctionSymbol symbol) {
        Scope root = new Scope(null);
        List<LocalSymbol> parameters = new ArrayList<>();
        RecordSymbol receiver = symbol.receiver();
        if (receiver != null) {
            LocalSymbol self = new LocalSymbol("this", receiver.type(), false, LocalSymbol.Kind.THIS,
                    function.name().span(), null);
            parameters.add(self);
            root.declare(self);
        }
        for (int i = 0; i < function.parameters().size(); i++) {
            Declaration.Parameter parameter = function.parameters().get(i);
            LocalSymbol local = new LocalSymbol(parameter.name().name(), symbol.parameterTypes().get(i), false,
                    LocalSymbol.Kind.PARAMETER, parameter.name().span(), null);
            parameters.add(local);
            if (root.lookupHere(local.name()) == null) {
                root.declare(local);
            }
            if (parameter.defaultValue() != null) {
                defaultValue(parameter.defaultValue(), symbol.parameterTypes().get(i),
                        "parameter '" + parameter.name().name() + "'");
            }
        }
        String owner = receiver != null ? "method '" + receiver.name() + "." + symbol.name() + "'"
                : "function '" + symbol.name() + "'";
        BodyBinder binder = new BodyBinder(context, this, root, symbol.returnType(), null, owner, receiver,
                BodyBinder.allAssignedNames(function.body()));
        BoundStatement.Block body = binder.bindBlock(function.body(), true);
        if (symbol.returnType() != PrimitiveType.VOID && !symbol.returnType().isError()
                && Reachability.completesNormally(body)) {
            int end = Math.max(function.body().span().start(), function.body().span().end() - 1);
            context.report(context.diagnostic(DiagnosticCode.MISSING_RETURN, new Span(end, function.body().span().end()),
                            capitalize(owner) + " must return a value of type "
                                    + symbol.returnType().displayName() + " on every path.")
                    .label(function.name().span(), "declared here").build());
        }
        String display = receiver != null ? "method " + receiver.name() + "." + symbol.name() : "function " + symbol.name();
        return new BoundFunction(symbol.key(), display, parameters, symbol.returnType(), body, function.span(), symbol);
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
        Map<String, Declaration.Annotation> annotations = annotations(declaration.annotations(), EVENT_ANNOTATIONS, "an event");
        int priority = BoundEventHandler.NORMAL_PRIORITY;
        Declaration.Annotation priorityAnnotation = annotations.get("priority");
        if (priorityAnnotation != null) {
            String level = annotationWord(priorityAnnotation);
            int index = level == null ? -1 : BoundEventHandler.PRIORITIES.indexOf(level.toUpperCase(Locale.ROOT));
            if (index < 0) {
                context.report(context.diagnostic(DiagnosticCode.INVALID_ANNOTATION, priorityAnnotation.span(),
                                "@priority needs one of " + String.join(", ", BoundEventHandler.PRIORITIES) + ".")
                        .note("Example: @priority(HIGH)").build());
            } else {
                priority = index;
            }
        }
        boolean ignoreCancelled = annotations.containsKey("ignoreCancelled")
                && annotationFlag(annotations.get("ignoreCancelled"));
        if (ignoreCancelled && !event.isCancellable()) {
            context.error(DiagnosticCode.INVALID_ANNOTATION, annotations.get("ignoreCancelled").span(),
                    "@ignoreCancelled has no effect: event '" + name + "' cannot be cancelled.");
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
        BodyBinder binder = new BodyBinder(context, this, root, PrimitiveType.VOID, event, "event handler '" + name + "'",
                null, BodyBinder.allAssignedNames(declaration.body()));
        BoundStatement.Block body = binder.bindBlock(declaration.body(), true);
        List<LocalSymbol> used = variables.stream().filter(LocalSymbol::isRead).toList();
        return new BoundEventHandler(event, eventObject, used, body, priority, ignoreCancelled, declaration.span());
    }

    // ------------------------------------------------------------------ commands

    private BoundCommand bindCommand(Declaration.Command declaration, Set<String> paths) {
        List<String> path = new ArrayList<>();
        for (Identifier part : declaration.path().parts()) {
            String word = part.name();
            if (!word.matches("[a-z0-9_]+")) {
                context.report(context.diagnostic(DiagnosticCode.INVALID_COMMAND, part.span(),
                                "Command names are written in lower case letters, digits and '_'.")
                        .suggestions(List.of(word.toLowerCase(Locale.ROOT))).build());
                return null;
            }
            path.add(word);
        }
        String joined = String.join(" ", path);
        if (!paths.add(joined)) {
            context.error(DiagnosticCode.DUPLICATE_DECLARATION, declaration.path().span(),
                    "Command '/" + joined + "' is already declared in this script.");
            return null;
        }
        Map<String, Declaration.Annotation> annotations = annotations(declaration.annotations(), COMMAND_ANNOTATIONS, "a command");
        BoundCommand.Options options = commandOptions(annotations, path.size() > 1, declaration);
        ClassType senderType = requireType("CommandSender", declaration.path().span());
        ClassType playerType = requireType("Player", declaration.path().span());
        if (senderType == null || playerType == null) {
            return null;
        }
        Scope root = new Scope(null);
        List<LocalSymbol> parameters = new ArrayList<>();
        LocalSymbol sender = new LocalSymbol("sender", senderType, false, LocalSymbol.Kind.IMPLICIT,
                declaration.path().span(), null);
        LocalSymbol player = new LocalSymbol("player", options.playerOnly() ? playerType : Types.nullable(playerType), false,
                LocalSymbol.Kind.IMPLICIT, declaration.path().span(), null);
        parameters.add(sender);
        parameters.add(player);
        root.declare(sender);
        root.declare(player);
        List<BoundCommand.Parameter> commandParameters = new ArrayList<>();
        boolean sawOptional = false;
        boolean failed = false;
        for (int i = 0; i < declaration.parameters().size(); i++) {
            Declaration.Parameter parameter = declaration.parameters().get(i);
            String name = parameter.name().name();
            Type type = context.types().resolve(parameter.type(), false);
            if (type.isError()) {
                failed = true;
                continue;
            }
            if (!isArgumentType(type.nonNullable())) {
                context.report(context.diagnostic(DiagnosticCode.INVALID_COMMAND, parameter.type().span(),
                                "Commands cannot take a parameter of type " + type.displayName() + ".")
                        .note("Command parameters can be int, long, double, float, bool, string, Duration, Player, "
                                + "OfflinePlayer, World, GameMode and Minecraft constants such as Material.")
                        .build());
                failed = true;
                continue;
            }
            if (parameter.rest() && (i != declaration.parameters().size() - 1 || type.nonNullable() != Types.STRING)) {
                context.report(context.diagnostic(DiagnosticCode.INVALID_COMMAND, parameter.span(),
                                "Only the last parameter can take the rest of the command, and it must be a string.")
                        .note("Example: command msg(target: Player, message: string...) { }").build());
                failed = true;
                continue;
            }
            Object defaultValue = null;
            String defaultKey = null;
            boolean optional = type.isNullable();
            if (parameter.defaultValue() != null) {
                BoundExpression bound = defaultValue(parameter.defaultValue(), type, "parameter '" + name + "'");
                if (bound instanceof BoundExpression.KeyedConstant keyed) {
                    defaultKey = keyed.key();
                } else if (!bound.type().isError()) {
                    Object constant = ConstantEvaluator.evaluate(bound, ConstantEvaluator.SILENT);
                    if (constant == ConstantEvaluator.NOT_CONSTANT) {
                        context.report(context.diagnostic(DiagnosticCode.NOT_CONSTANT, parameter.defaultValue().span(),
                                        "The default value of a command parameter must be a constant.")
                                .note("Leave the parameter empty with '?' and decide in the body: "
                                        + name + ": " + type.nonNullable().displayName() + "?").build());
                        failed = true;
                        continue;
                    }
                    defaultValue = constant;
                } else {
                    failed = true;
                    continue;
                }
                optional = true;
            }
            if (!optional && sawOptional && !parameter.rest()) {
                context.report(context.diagnostic(DiagnosticCode.INVALID_COMMAND, parameter.name().span(),
                                "Required parameters must come before optional ones.")
                        .note("Move '" + name + "' before the parameters that have '?' or a default value.").build());
                failed = true;
                continue;
            }
            sawOptional |= optional;
            LocalSymbol local = new LocalSymbol(name, type, false, LocalSymbol.Kind.PARAMETER, parameter.name().span(), null);
            if (root.lookupHere(name) != null) {
                context.error(DiagnosticCode.DUPLICATE_DECLARATION, parameter.name().span(),
                        "'" + name + "' is already a parameter or built-in variable of this command.");
                failed = true;
                continue;
            }
            root.declare(local);
            parameters.add(local);
            commandParameters.add(new BoundCommand.Parameter(name, type, optional, defaultValue, defaultKey, parameter.rest()));
        }
        String display = "command /" + joined;
        BodyBinder binder = new BodyBinder(context, this, root, PrimitiveType.VOID, null, display, null,
                BodyBinder.allAssignedNames(declaration.body()));
        BoundStatement.Block body = binder.bindBlock(declaration.body(), true);
        if (failed) {
            return null;
        }
        BoundFunction function = new BoundFunction("command " + joined, display, parameters, PrimitiveType.VOID, body,
                declaration.span(), null);
        return new BoundCommand(path, commandParameters, options, function, declaration.span());
    }

    private boolean isArgumentType(Type type) {
        if (type instanceof PrimitiveType primitive) {
            return primitive.isNumeric() || primitive == PrimitiveType.BOOL || primitive == PrimitiveType.DURATION;
        }
        if (type instanceof ClassType classType) {
            return classType == Types.STRING || classType.isKeyed()
                    || List.of("Player", "OfflinePlayer", "World", "GameMode").contains(classType.name());
        }
        return false;
    }

    private BoundCommand.Options commandOptions(Map<String, Declaration.Annotation> annotations, boolean subcommand,
                                                Declaration.Command declaration) {
        String permission = annotationText(annotations.get("permission"), "");
        String permissionMessage = annotationText(annotations.get("permissionMessage"), "");
        List<String> aliases = new ArrayList<>();
        Declaration.Annotation aliasAnnotation = annotations.get("aliases");
        if (aliasAnnotation != null) {
            if (subcommand) {
                context.error(DiagnosticCode.INVALID_ANNOTATION, aliasAnnotation.span(),
                        "@aliases can only be used on a top-level command, not on a sub-command.");
            }
            for (Expression argument : aliasAnnotation.arguments()) {
                Object value = constantOf(argument);
                if (value instanceof String alias && alias.matches("[a-z0-9_]+")) {
                    aliases.add(alias);
                } else {
                    context.report(context.diagnostic(DiagnosticCode.INVALID_ANNOTATION, argument.span(),
                            "Aliases are lower-case names in quotes, e.g. @aliases(\"h\", \"hp\").").build());
                }
            }
        }
        String description = annotationText(annotations.get("description"), declaration.documentation());
        String usage = annotationText(annotations.get("usage"), "");
        long cooldown = 0;
        Declaration.Annotation cooldownAnnotation = annotations.get("cooldown");
        if (cooldownAnnotation != null) {
            Object value = cooldownAnnotation.arguments().size() == 1 ? constantDuration(cooldownAnnotation.arguments().getFirst())
                    : null;
            if (value instanceof Long millis && millis > 0) {
                cooldown = millis;
            } else {
                context.report(context.diagnostic(DiagnosticCode.INVALID_ANNOTATION, cooldownAnnotation.span(),
                        "@cooldown needs a duration, e.g. @cooldown(10 seconds).").build());
            }
        }
        String cooldownMessage = annotationText(annotations.get("cooldownMessage"), "");
        String cooldownBypass = annotationText(annotations.get("cooldownBypass"), "");
        boolean playerOnly = annotations.containsKey("playerOnly") && annotationFlag(annotations.get("playerOnly"));
        return new BoundCommand.Options(permission, permissionMessage, aliases, description, usage, cooldown,
                cooldownMessage, cooldownBypass, playerOnly);
    }

    // ------------------------------------------------------------------ tasks, hooks, placeholders

    private BoundTask bindTask(Declaration.Task task, int index) {
        Map<String, Declaration.Annotation> annotations = annotations(task.annotations(), TASK_ANNOTATIONS, "a task");
        boolean async = annotations.containsKey("async") && annotationFlag(annotations.get("async"));
        long interval = 0;
        int minute = -1;
        String label;
        if (task.interval() != null) {
            Object value = constantDuration(task.interval());
            if (!(value instanceof Long millis) || millis < 50) {
                context.report(context.diagnostic(DiagnosticCode.NOT_CONSTANT, task.interval().span(),
                                "'every' needs a duration known when the script loads, of at least 1 tick.")
                        .note("Example: every 5 minutes { ... }").build());
                return null;
            }
            interval = millis;
            label = "every " + dev.tachyonscript.api.value.Values.durationToString(millis);
        } else {
            Object value = constantOf(task.time());
            if (!(value instanceof String text) || (minute = parseTime(text)) < 0) {
                context.report(context.diagnostic(DiagnosticCode.NOT_CONSTANT, task.time().span(),
                                "'at' needs a time of day written as \"HH:mm\" (server time), e.g. at \"20:30\".").build());
                return null;
            }
            label = "at " + text;
        }
        String display = label + " (" + unit.file().path() + ":" + unit.file().lineOf(task.span().start()) + ")";
        BodyBinder binder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null, "the task " + label,
                null, BodyBinder.allAssignedNames(task.body()));
        BoundStatement.Block body = binder.bindBlock(task.body(), true);
        BoundFunction function = new BoundFunction("task #" + index, display, List.of(), PrimitiveType.VOID, body,
                task.span(), null);
        return new BoundTask(interval, minute, async, function, task.span());
    }

    /** Minutes after midnight of {@code HH:mm}, or -1 if malformed. */
    private static int parseTime(String text) {
        if (!text.matches("\\d{1,2}:\\d{2}")) {
            return -1;
        }
        int colon = text.indexOf(':');
        int hour = Integer.parseInt(text.substring(0, colon));
        int minute = Integer.parseInt(text.substring(colon + 1));
        return hour < 24 && minute < 60 ? hour * 60 + minute : -1;
    }

    private BoundFunction bindHook(Declaration.Lifecycle lifecycle, int index) {
        String what = lifecycle.load() ? "on load" : "on unload";
        BodyBinder binder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null, "'" + what + "'",
                null, BodyBinder.allAssignedNames(lifecycle.body()));
        BoundStatement.Block body = binder.bindBlock(lifecycle.body(), true);
        return new BoundFunction((lifecycle.load() ? "load #" : "unload #") + index, what + " (" + unit.file().path() + ")",
                List.of(), PrimitiveType.VOID, body, lifecycle.span(), null);
    }

    private BoundPlaceholder bindPlaceholder(Declaration.Placeholder declaration, Set<String> names) {
        String name = declaration.name().name();
        if (!name.matches("[a-z0-9_]+")) {
            context.report(context.diagnostic(DiagnosticCode.INVALID_COMMAND, declaration.name().span(),
                    "Placeholder names are written in lower case letters, digits and '_'.").build());
            return null;
        }
        if (!names.add(name)) {
            context.error(DiagnosticCode.DUPLICATE_DECLARATION, declaration.name().span(),
                    "Placeholder '" + name + "' is already declared in this script.");
            return null;
        }
        ClassType offline = context.standardType("OfflinePlayer");
        if (offline == null) {
            offline = requireType("Player", declaration.name().span());
            if (offline == null) {
                return null;
            }
        }
        Scope root = new Scope(null);
        LocalSymbol player = new LocalSymbol("player", Types.nullable(offline), false, LocalSymbol.Kind.IMPLICIT,
                declaration.name().span(), null);
        LocalSymbol argument = new LocalSymbol("argument", Types.STRING, false, LocalSymbol.Kind.IMPLICIT,
                declaration.name().span(), null);
        root.declare(player);
        root.declare(argument);
        String display = "placeholder " + name;
        BodyBinder binder = new BodyBinder(context, this, root, Types.STRING, null, display, null,
                BodyBinder.allAssignedNames(declaration.body()));
        BoundStatement.Block body = binder.bindBlock(declaration.body(), true);
        if (Reachability.completesNormally(body)) {
            int end = Math.max(declaration.body().span().start(), declaration.body().span().end() - 1);
            context.report(context.diagnostic(DiagnosticCode.MISSING_RETURN, new Span(end, declaration.body().span().end()),
                            "Placeholder '" + name + "' must return the text to show on every path.")
                    .note("Example: return \"{player?.name ?? \"nobody\"}\"").build());
        }
        BoundFunction function = new BoundFunction("placeholder " + name, display, List.of(player, argument), Types.STRING,
                body, declaration.span(), null);
        return new BoundPlaceholder(name, function, declaration.span());
    }

    // ------------------------------------------------------------------ annotations

    private Map<String, Declaration.Annotation> annotations(List<Declaration.Annotation> list, Set<String> allowed,
                                                           String what) {
        Map<String, Declaration.Annotation> result = new LinkedHashMap<>();
        for (Declaration.Annotation annotation : list) {
            String name = annotation.name().name();
            if (!allowed.contains(name)) {
                context.report(context.diagnostic(DiagnosticCode.INVALID_ANNOTATION, annotation.span(),
                                "Unknown annotation '@" + name + "' on " + what + ".")
                        .suggestions(Suggestions.closest(name, allowed, 3).stream().map(s -> "@" + s).toList()).build());
                continue;
            }
            if (result.put(name, annotation) != null) {
                context.error(DiagnosticCode.INVALID_ANNOTATION, annotation.span(), "Duplicate annotation '@" + name + "'.");
            }
        }
        return result;
    }

    private String annotationText(Declaration.Annotation annotation, String fallback) {
        if (annotation == null) {
            return fallback;
        }
        Object value = annotation.arguments().size() == 1 ? constantOf(annotation.arguments().getFirst()) : null;
        if (value instanceof String text) {
            return text;
        }
        context.report(context.diagnostic(DiagnosticCode.INVALID_ANNOTATION, annotation.span(),
                "@" + annotation.name().name() + " needs one text value in quotes.").build());
        return fallback;
    }

    /** A bare word ({@code HIGH}) or a string argument. */
    private String annotationWord(Declaration.Annotation annotation) {
        if (annotation.arguments().size() != 1) {
            return null;
        }
        Expression argument = BodyBinder.unwrap(annotation.arguments().getFirst());
        if (argument instanceof Expression.Name name) {
            return name.name();
        }
        return constantOf(argument) instanceof String text ? text : null;
    }

    private boolean annotationFlag(Declaration.Annotation annotation) {
        if (annotation.arguments().isEmpty()) {
            return true;
        }
        Object value = annotation.arguments().size() == 1 ? constantOf(annotation.arguments().getFirst()) : null;
        if (value instanceof Boolean flag) {
            return flag;
        }
        context.report(context.diagnostic(DiagnosticCode.INVALID_ANNOTATION, annotation.span(),
                "@" + annotation.name().name() + " takes no value, or true/false.").build());
        return false;
    }

    /** The compile-time value of an expression, or null (errors from binding are reported). */
    private Object constantOf(Expression expression) {
        BodyBinder binder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null, "an annotation");
        BoundExpression bound = binder.bindValue(expression, null);
        if (bound.type().isError()) {
            return null;
        }
        Object value = ConstantEvaluator.evaluate(bound, ConstantEvaluator.SILENT);
        return value == ConstantEvaluator.NOT_CONSTANT ? null : value;
    }

    private Object constantDuration(Expression expression) {
        BodyBinder binder = new BodyBinder(context, this, new Scope(null), PrimitiveType.VOID, null, "a duration");
        BoundExpression bound = binder.bindValue(expression, PrimitiveType.DURATION);
        if (bound.type() != PrimitiveType.DURATION) {
            return null;
        }
        Object value = ConstantEvaluator.evaluate(bound, ConstantEvaluator.SILENT);
        return value == ConstantEvaluator.NOT_CONSTANT ? null : value;
    }

    private ClassType requireType(String name, Span span) {
        ClassType type = context.standardType(name);
        if (type == null) {
            context.error(DiagnosticCode.MISSING_STANDARD_TYPE, span,
                    "This needs the type '" + name + "' of the standard library, which is not available.");
        }
        return type;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
