package dev.tachyonscript.security;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.lexer.Lexer;
import dev.tachyonscript.language.lexer.TokenKind;
import dev.tachyonscript.language.parser.Parser;
import dev.tachyonscript.language.semantic.*;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, context-sensitive semantic traversal with conservative branch/global joins.
 * Unanalysable behavior is reported as uncertainty; it is never claimed to be proven safe. */
public final class SecurityAnalyzer {
    private final SecurityOptions options;
    private SecretRedactor redactor;
    private final Map<String, BoundModule> modules = new LinkedHashMap<>();
    private final Map<String, BoundFunction> functions = new HashMap<>();
    private final Map<String, Flow> globals = new HashMap<>();
    private final Map<String, LinkedHashMap<String, SecurityNode>> nodes = new HashMap<>();
    private final Map<String, LinkedHashMap<String, SecurityFinding>> findings = new HashMap<>();
    private final Set<String> calls = new HashSet<>();
    private int visits;
    private boolean globalsChanged;

    public SecurityAnalyzer(SecurityOptions options) {
        this.options = options;
        this.redactor = options.redactor();
    }

    public Map<String, SecurityManifest> analyze(List<BoundModule> input) {
        if (!modules.isEmpty()) throw new IllegalStateException("An analyzer processes one immutable source snapshot");
        List<String> secrets = new ArrayList<>(List.of(options.ai().apiKey(), options.discord().webhook()));
        for (BoundModule module : input) {
            var tokens = Lexer.lex(module.file(), new DiagnosticCollector()).tokens();
            for (int i = 0; i < tokens.size(); i++) {
                if (!tokens.get(i).is(TokenKind.IDENTIFIER) || !secretName(tokens.get(i).text())) continue;
                for (int j = i + 1; j < Math.min(tokens.size() - 1, i + 8); j++) {
                    if (tokens.get(j).is(TokenKind.NEWLINE)) break;
                    if (tokens.get(j).is(TokenKind.EQ) && tokens.get(j + 1).value() instanceof String secret && secret.length() >= 4) {
                        secrets.add(secret); secrets.add(tokens.get(j + 1).text()); break;
                    }
                }
            }
        }
        redactor = new SecretRedactor(secrets);
        for (BoundModule module : input) {
            ScriptIdentity.of(module.file().path());
            modules.put(module.name(), module);
            nodes.put(module.name(), new LinkedHashMap<>());
            findings.put(module.name(), new LinkedHashMap<>());
            for (BoundFunction function : module.functions()) functions.put(module.name() + "::" + function.key(), function);
        }
        // Seed globals, then reach a fixed point across independent handlers. Paths are bounded and de-duplicated.
        int passes = 0;
        do {
            globalsChanged = false;
            visits = 0;
            for (BoundModule module : input) scanModule(module);
            if (++passes > 16 && globalsChanged) throw new IllegalStateException("Global taint fixed point exceeded security budget");
        } while (globalsChanged);
        Map<String, SecurityManifest> result = new LinkedHashMap<>();
        for (BoundModule module : input) {
            Map<String, String> dependencies = new LinkedHashMap<>();
            dependencyHashes(module, dependencies, new HashSet<>());
            result.put(module.file().path(), SecurityManifest.of(module, List.copyOf(nodes.get(module.name()).values()),
                    List.copyOf(findings.get(module.name()).values()), dependencies));
        }
        return result;
    }

    private void dependencyHashes(BoundModule module, Map<String, String> result, Set<String> seen) {
        if (!seen.add(module.name())) return;
        for (String name : module.imports()) {
            BoundModule dependency = modules.get(name);
            if (dependency != null) {
                result.put(dependency.file().path(), dependency.file().hash());
                dependencyHashes(dependency, result, seen);
            }
        }
    }

    public static SourceSpan importLocation(BoundModule module, String dependency) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        var syntax = Parser.parse(Lexer.lex(module.file(), diagnostics), diagnostics);
        return syntax.imports().stream().filter(i -> i.module().text().equals(dependency)).findFirst()
                .map(i -> SourceSpan.from(module.file(), i.span())).orElse(SourceSpan.unknown(module.file().path()));
    }

    private void scanModule(BoundModule module) {
        Context moduleContext = new Context(module, "", "module imports", "", 0, 0, false);
        var syntax = Parser.parse(Lexer.lex(module.file(), new DiagnosticCollector()), new DiagnosticCollector());
        for (var imported : syntax.imports()) node(moduleContext, imported.span(), "Import", imported.module().text(),
                Capability.UNKNOWN, List.of(), List.of(), Flow.clean(), false);
        if (module.initializer() != null) scanFunction(module, module.initializer(), List.of(), "on load", "", 0);
        for (BoundFunction function : module.functions()) scanFunction(module, function, List.of(), "", "", 0);
        for (BoundFunction function : module.loadHooks()) scanFunction(module, function, List.of(), "on load", "", 0);
        for (BoundFunction function : module.unloadHooks()) scanFunction(module, function, List.of(), "on unload", "", 0);
        for (BoundFunction function : module.defaults().values()) scanFunction(module, function, List.of(), "playerdata default", "", 0);
        for (BoundFunction function : module.fieldDefaults().values()) scanFunction(module, function, List.of(), "record default", "", 0);
        for (BoundEventHandler handler : module.handlers()) {
            Context context = new Context(module, "", "event " + handler.event().name(), "", 0, 0, false);
            State state = new State();
            state.values.put(handler.eventObject(), Flow.unknown());
            for (LocalSymbol variable : handler.eventVariables()) state.values.put(variable, Flow.unknown());
            statement(handler.body(), state, context);
        }
        for (BoundCommand command : module.commands()) {
            List<Flow> arguments = new ArrayList<>();
            Context context = new Context(module, command.function().displayName(), "command /" + String.join(" ", command.path()),
                    command.options().permission(), 0, 0, false);
            for (LocalSymbol parameter : command.function().parameters()) {
                arguments.add(Set.of("sender", "player").contains(parameter.name()) ? Flow.clean()
                        : source(context, parameter.declaration(), "command argument " + parameter.name(), textType(parameter.type().displayName())));
            }
            scanFunction(module, command.function(), arguments, context.handler, context.permission, 0);
        }
        for (BoundPlaceholder placeholder : module.placeholders()) {
            List<Flow> arguments = placeholder.function().parameters().stream().map(parameter -> parameter.name().equals("argument")
                    ? source(new Context(module, placeholder.function().displayName(), "placeholder " + placeholder.name(), "", 0, 0, false),
                    parameter.declaration(), "placeholder argument", true) : Flow.clean()).toList();
            scanFunction(module, placeholder.function(), arguments, "placeholder " + placeholder.name(), "", 0);
        }
        for (BoundTask task : module.tasks()) {
            Context context = new Context(module, task.function().displayName(), "scheduled task", "", 0, 0, true);
            if (module.tasks().size() > options.maxPendingTasks())
                finding(context, task.span(), "TASK_QUOTA", SecurityCategory.TASK_EXPLOSION, SecuritySeverity.CRITICAL,
                        "Declared repeating tasks exceed the configured per-script task quota.", Flow.clean(), "scheduler", Capability.SCHEDULE, true);
            if (task.intervalMillis() > 0 && task.intervalMillis() < 50)
                finding(context, task.span(), "TASK_INTERVAL", SecurityCategory.TASK_EXPLOSION, SecuritySeverity.HIGH,
                        "A task repeats more often than one server tick.", Flow.clean(), "scheduler", Capability.SCHEDULE, true);
            State state = new State();
            statement(task.function().body(), state, context);
        }
    }

    private Flow scanFunction(BoundModule module, BoundFunction function, List<Flow> arguments,
                              String handler, String permission, int depth) {
        Context context = new Context(module, function.displayName(), handler, permission, depth, 0, false);
        State state = new State();
        for (int i = 0; i < function.parameters().size(); i++) {
            LocalSymbol parameter = function.parameters().get(i);
            state.values.put(parameter, i < arguments.size() ? step(arguments.get(i), context, parameter.declaration(),
                    "parameter " + parameter.name(), "PARAMETER") : Flow.unknown());
        }
        statement(function.body(), state, context);
        return state.returned;
    }

    private void budget() {
        if (++visits > options.maxNodes() * 32) throw new IllegalStateException("Security traversal budget exceeded");
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Security scan interrupted");
    }

    private void statement(BoundStatement statement, State state, Context context) {
        if (statement == null) return;
        budget();
        switch (statement) {
            case BoundStatement.Block block -> {
                for (BoundStatement child : block.statements()) {
                    statement(child, state, context);
                    if (child instanceof BoundStatement.If branch && branch.condition() instanceof BoundExpression.Not not
                            && permissionCondition(not.operand()) && exits(branch.thenBranch()) && branch.elseBranch() == null)
                        state.guarded = true;
                    if (child instanceof BoundStatement.Return || child instanceof BoundStatement.Throw) break;
                }
            }
            case BoundStatement.LocalDeclaration declaration -> assign(declaration.local(), declaration.initializer(), declaration.span(), state, context);
            case BoundStatement.LocalAssignment assignment -> assign(assignment.local(), assignment.value(), assignment.span(), state, context);
            case BoundStatement.GlobalStore store -> storeGlobal(store.global(), expression(store.value(), state, context), store.span(), context);
            case BoundStatement.GlobalAdd add -> storeGlobal(add.global(), expression(add.delta(), state, context), add.span(), context);
            case BoundStatement.PlayerDataStore store -> { expression(store.owner(), state, context); expression(store.value(), state, context); }
            case BoundStatement.PlayerDataAdd add -> { expression(add.owner(), state, context); expression(add.delta(), state, context); }
            case BoundStatement.PropertyAssignment assignment -> {
                List<Flow> values = new ArrayList<>();
                if (assignment.receiver() != null) values.add(expression(assignment.receiver(), state, context));
                values.add(expression(assignment.value(), state, context));
                nativeCall(assignment.setter(), values, assignment.span(), state, context);
            }
            case BoundStatement.ListSet set -> {
                Flow flow = expression(set.value(), state, context).join(expression(set.list(), state, context));
                expression(set.index(), state, context);
                updateContainer(set.list(), flow, state, context);
            }
            case BoundStatement.ExpressionStatement e -> expression(e.expression(), state, context);
            case BoundStatement.If branch -> {
                expression(branch.condition(), state, context);
                State whenTrue = state.copy(), whenFalse = state.copy();
                whenTrue.guarded |= permissionCondition(branch.condition());
                statement(branch.thenBranch(), whenTrue, context);
                statement(branch.elseBranch(), whenFalse, context);
                state.merge(whenTrue, whenFalse);
            }
            case BoundStatement.While loop -> {
                Flow condition = expression(loop.condition(), state, context);
                node(context, loop.span(), "Loop", "while", Capability.PURE, List.of(), List.of(condition), condition, false);
                if (Boolean.TRUE.equals(condition.constant) && invariant(loop.condition()) && !loopExit(loop.body()))
                    finding(context, loop.span(), "LOOP_NO_EXIT", SecurityCategory.UNBOUNDED_LOOP, SecuritySeverity.CRITICAL,
                            "A constant-true loop has no reachable break, return or throw.", condition, "interpreter loop", Capability.PURE, true);
                loop(loop.body(), state, context.inLoop());
            }
            case BoundStatement.ForRange loop -> {
                Flow bounds = expression(loop.start(), state, context).join(expression(loop.end(), state, context));
                state.values.put(loop.variable(), bounds.safeText());
                node(context, loop.span(), "Loop", "range", Capability.PURE, List.of(), List.of(bounds), bounds, false);
                loop(loop.body(), state, context.inLoop());
            }
            case BoundStatement.ForEach loop -> {
                Flow iterable = expression(loop.iterable(), state, context);
                state.values.put(loop.variable(), iterable);
                node(context, loop.span(), "Loop", "foreach", Capability.PURE, List.of(), List.of(iterable), iterable, false);
                loop(loop.body(), state, context.inLoop());
            }
            case BoundStatement.Return returned -> state.returned = state.returned.join(expression(returned.value(), state, context));
            case BoundStatement.Try tried -> {
                State normal = state.copy(), caught = state.copy();
                statement(tried.body(), normal, context);
                statement(tried.catchBody(), caught, context);
                state.merge(normal, caught);
                statement(tried.finallyBody(), state, context);
            }
            case BoundStatement.Throw thrown -> expression(thrown.value(), state, context);
            case BoundStatement.Break ignored -> { }
            case BoundStatement.Continue ignored -> { }
        }
    }

    private void loop(BoundStatement body, State state, Context context) {
        // A loop-carried flow may be produced after a sink in the preceding iteration.
        for (int i = 0; i < 32; i++) {
            String before = state.signature();
            State iteration = state.copy();
            statement(body, iteration, context);
            state.merge(state.copy(), iteration);
            if (state.signature().equals(before)) return;
        }
        throw new IllegalStateException("Loop-carried security data flow exceeded its fixed-point budget");
    }

    private void assign(LocalSymbol local, BoundExpression value, Span span, State state, Context context) {
        Flow flow = expression(value, state, context);
        if (secretName(local.name()) && flow.constant instanceof String text && text.length() >= 4) {
            flow = flow.secret();
            finding(context, value.span(), "SECRET_LITERAL", SecurityCategory.CREDENTIAL_EXPOSURE, SecuritySeverity.HIGH,
                    "A credential is embedded in source; move it to server configuration or a secret provider.",
                    flow, local.name(), Capability.IO, true);
        }
        state.values.put(local, step(flow, context, span, local.name(), "ASSIGNMENT"));
    }

    private void storeGlobal(GlobalSymbol global, Flow value, Span span, Context context) {
        Flow previous = globals.getOrDefault(global.key(), Flow.clean());
        Flow next = previous.join(step(secretName(global.name()) ? value.secret() : value, context, span, global.name(), "GLOBAL_STORE"));
        if (secretName(global.name()) && value.constant instanceof String text && text.length() >= 4)
            finding(context, span, "SECRET_GLOBAL", SecurityCategory.CREDENTIAL_EXPOSURE, SecuritySeverity.HIGH,
                    "A source credential is stored in a script global.", value.secret(), global.name(), Capability.IO, true);
        // UNKNOWN is the top of the constant lattice, not a fresh uninitialised value.
        if (globals.containsKey(global.key()) && previous.constant == null) next = next.withConstant(null);
        if (!previous.signature().equals(next.signature())) globalsChanged = true;
        globals.put(global.key(), next);
    }

    private void updateContainer(BoundExpression target, Flow value, State state, Context context) {
        if (target instanceof BoundExpression.LocalLoad load) state.values.put(load.local(), value);
        if (target instanceof BoundExpression.GlobalLoad load) storeGlobal(load.global(), value, target.span(), context);
    }

    private Flow expression(BoundExpression expression, State state, Context context) {
        if (expression == null) return Flow.clean();
        budget();
        return switch (expression) {
            case BoundExpression.Literal literal -> {
                Flow flow = Flow.constant(literal.value());
                if (literal.value() instanceof String text && redactor.containsSecret(text)) {
                    flow = flow.secret();
                    finding(context, literal.span(), "SECRET_LITERAL", SecurityCategory.CREDENTIAL_EXPOSURE, SecuritySeverity.HIGH,
                            "A recognizable secret is embedded in script source.", flow, "source literal", Capability.IO, true);
                }
                yield flow;
            }
            case BoundExpression.LocalLoad load -> {
                Flow flow = state.values.getOrDefault(load.local(), Flow.unknown());
                if (load.local().kind() == LocalSymbol.Kind.EVENT_VARIABLE)
                    flow = source(context, load.span(), load.local().name(), textType(load.type().displayName()));
                if (load.local().kind() == LocalSymbol.Kind.EVENT_OBJECT)
                    flow = source(context, load.span(), load.local().name(), true);
                yield step(flow, context, load.span(), load.local().name(), "READ");
            }
            case BoundExpression.GlobalLoad load -> step(globals.getOrDefault(load.global().key(), Flow.unknown()), context,
                    load.span(), load.global().name(), "GLOBAL_READ");
            case BoundExpression.PlayerDataLoad load -> {
                expression(load.owner(), state, context);
                yield source(context, load.span(), "playerdata " + load.global().name(), textType(load.type().displayName()));
            }
            case BoundExpression.NativeCall call -> {
                Capability capability = CapabilityCatalog.resolve(call.target(), options);
                Context callbackContext = capability == Capability.SCHEDULE
                        ? new Context(context.module, context.function, context.handler, context.permission, context.depth, context.loops, true) : context;
                List<Flow> arguments = call.arguments().stream().map(argument -> expression(argument, state,
                        argument instanceof BoundExpression.Lambda ? callbackContext : context)).toList();
                if (CapabilityCatalog.name(call.target().key()).equals("CommandSender.dispatch") && !call.arguments().isEmpty()
                        && call.arguments().getFirst().type().displayName().replace("?", "").equals("Player")) capability = Capability.PLAYER_COMMAND;
                yield nativeCall(call.target(), arguments, call.span(), state, context, capability);
            }
            case BoundExpression.FunctionCall call -> {
                List<Flow> arguments = call.arguments().stream().map(argument -> expression(argument, state, context)).toList();
                String key = call.function().module() + "::" + call.function().key();
                BoundFunction function = functions.get(key);
                BoundModule module = modules.get(call.function().module());
                Flow joined = join(arguments);
                node(context, call.span(), "FunctionCall", key, Capability.PURE, List.of(), arguments, joined, false);
                if (function == null || module == null) yield joined;
                String signature = key + ":" + arguments.stream().map(Flow::signature).toList() + ":" + context.permission;
                if (context.depth >= 64) throw new IllegalStateException("Interprocedural security scan depth exceeded");
                if (!calls.add(signature)) {
                    finding(context, call.span(), "RECURSIVE_FLOW", SecurityCategory.UNBOUNDED_LOOP, SecuritySeverity.MEDIUM,
                            "Recursive data flow is conservatively joined; interpreter recursion limits remain in force.", joined, key, Capability.PURE, false);
                    yield joined;
                }
                try {
                    yield step(scanFunction(module, function, arguments, context.handler,
                            state.guarded ? "validated permission guard" : context.permission, context.depth + 1),
                            context, call.span(), key + " return", "FUNCTION_RETURN");
                } finally { calls.remove(signature); }
            }
            case BoundExpression.Lambda lambda -> {
                State nested = state.copy();
                for (int i = 0; i < lambda.captures().size(); i++) nested.values.put(lambda.captures().get(i), expression(lambda.captureValues().get(i), state, context));
                for (LocalSymbol parameter : lambda.parameters()) nested.values.put(parameter,
                        source(context, parameter.declaration(), "callback " + parameter.name(), textType(parameter.type().displayName())));
                Context inner = new Context(context.module, lambda.displayName(), context.handler, context.permission,
                        context.depth, context.loops, context.scheduled);
                statement(lambda.body(), nested, inner);
                yield nested.returned;
            }
            case BoundExpression.FunctionReference reference -> Flow.clean();
            case BoundExpression.ClosureCall call -> {
                Flow flow = expression(call.callee(), state, context).join(join(call.arguments().stream().map(a -> expression(a, state, context)).toList()));
                if (call.callee() instanceof BoundExpression.FunctionReference reference) {
                    BoundFunction target = functions.get(reference.function().module() + "::" + reference.function().key());
                    if (target != null && context.depth < 24) flow = flow.join(scanFunction(modules.get(reference.function().module()), target,
                            call.arguments().stream().map(a -> expression(a, state, context)).toList(), context.handler, context.permission, context.depth + 1));
                }
                yield step(flow, context, call.span(), "closure invocation", "CALL");
            }
            case BoundExpression.Arithmetic arithmetic -> expression(arithmetic.left(), state, context).join(expression(arithmetic.right(), state, context)).safeText();
            case BoundExpression.Negate negate -> expression(negate.operand(), state, context).safeText();
            case BoundExpression.Not not -> { expression(not.operand(), state, context); yield Flow.clean(); }
            case BoundExpression.Compare compare -> { expression(compare.left(), state, context); expression(compare.right(), state, context); yield Flow.clean(); }
            case BoundExpression.NullCheck check -> { expression(check.operand(), state, context); yield Flow.clean(); }
            case BoundExpression.Logical logical -> { expression(logical.left(), state, context); expression(logical.right(), state, context); yield Flow.clean(); }
            case BoundExpression.Conditional conditional -> {
                expression(conditional.condition(), state, context);
                yield expression(conditional.whenTrue(), state, context).join(expression(conditional.whenFalse(), state, context));
            }
            case BoundExpression.Concat concat -> {
                List<Flow> parts = concat.parts().stream().map(part -> expression(part, state, context)).toList();
                Flow flow = join(parts);
                if (parts.stream().allMatch(part -> part.constant instanceof String))
                    flow = flow.withConstant(parts.stream().map(part -> (String) part.constant).reduce("", String::concat));
                else flow = flow.withConstant(null);
                yield step(flow, context, concat.span(), "string concatenation", "CONCAT");
            }
            case BoundExpression.ComponentTemplate template -> step(join(template.arguments().stream().map(a -> expression(a, state, context)).toList()), context, template.span(), "message template", "TEMPLATE");
            case BoundExpression.Conversion conversion -> expression(conversion.operand(), state, context);
            case BoundExpression.SafeAccess access -> {
                state.values.put(access.temporary(), expression(access.receiver(), state, context));
                yield expression(access.access(), state, context);
            }
            case BoundExpression.Coalesce coalesce -> {
                state.values.put(coalesce.temporary(), expression(coalesce.left(), state, context));
                yield expression(coalesce.nonNullValue(), state, context).join(expression(coalesce.fallback(), state, context));
            }
            case BoundExpression.TypeTest test -> { expression(test.operand(), state, context); yield Flow.clean(); }
            case BoundExpression.Cast cast -> expression(cast.operand(), state, context);
            case BoundExpression.ListLiteral list -> join(list.elements().stream().map(a -> expression(a, state, context)).toList());
            case BoundExpression.MapLiteral map -> join(map.keys().stream().map(a -> expression(a, state, context)).toList()).join(join(map.values().stream().map(a -> expression(a, state, context)).toList()));
            case BoundExpression.ListGet get -> expression(get.list(), state, context).join(expression(get.index(), state, context));
            case BoundExpression.ListSize size -> expression(size.list(), state, context).safeText();
            case BoundExpression.ListContains contains -> { expression(contains.list(), state, context); expression(contains.element(), state, context); yield Flow.clean(); }
            case BoundExpression.ListAdd add -> {
                Flow value = expression(add.list(), state, context).join(expression(add.element(), state, context));
                updateContainer(add.list(), value, state, context); yield value;
            }
            case BoundExpression.NewRecord record -> join(record.fields().stream().map(a -> expression(a, state, context)).toList());
            case BoundExpression.RecordGet get -> step(expression(get.receiver(), state, context), context, get.span(), "record field " + get.field().name(), "FIELD");
            case BoundExpression.Let let -> {
                state.values.put(let.local(), expression(let.value(), state, context));
                yield expression(let.body(), state, context);
            }
            case BoundExpression.KeyedConstant ignored -> Flow.clean();
            case BoundExpression.GlobalRestore restore -> Flow.clean();
            case BoundExpression.Error ignored -> Flow.unknown();
        };
    }

    private Flow nativeCall(NativeDeclaration target, List<Flow> arguments, Span span, State state, Context context) {
        return nativeCall(target, arguments, span, state, context, CapabilityCatalog.resolve(target, options));
    }
    private Flow nativeCall(NativeDeclaration target, List<Flow> arguments, Span span, State state, Context context, Capability capability) {
        Context evidenceContext = state.guarded && context.permission.isBlank()
                ? new Context(context.module, context.function, context.handler, "validated permission check", context.depth, context.loops, context.scheduled)
                : context;
        String name = CapabilityCatalog.name(target.key());
        Flow all = join(arguments);
        int argumentIndex = switch (target.kind()) { case METHOD -> 1; default -> 0; };
        Flow argument = arguments.size() > argumentIndex ? arguments.get(argumentIndex) : Flow.clean();
        Flow sinkFlow = step(argument, context, span, name, "SINK");
        node(evidenceContext, span, "NativeCall", target.key(), capability, target.effects().stream().map(Enum::name).sorted().toList(),
                arguments, sinkFlow, false);
        boolean guarded = !context.permission.isBlank() || state.guarded;
        boolean danger = false;
        switch (capability) {
            case CONSOLE_COMMAND, PLAYER_COMMAND -> {
                if (argument.unsafe) {
                    danger = true;
                    finding(context, span, "COMMAND_TAINT", SecurityCategory.COMMAND_INJECTION,
                            capability == Capability.CONSOLE_COMMAND && !guarded ? SecuritySeverity.CRITICAL : SecuritySeverity.HIGH,
                            "Untrusted text reaches " + name + " and can alter command syntax.", sinkFlow, name, capability, true);
                } else if (argument.constant instanceof String command && dangerousCommand(command)) {
                    danger = true;
                    finding(context, span, "DANGEROUS_COMMAND", SecurityCategory.PRIVILEGE_ESCALATION,
                            guarded ? SecuritySeverity.HIGH : SecuritySeverity.CRITICAL,
                            "A command changes administrator privileges or server control without an established permission boundary.", sinkFlow, name, capability, true);
                } else if (argument.constant == null) {
                    finding(context, span, "DYNAMIC_COMMAND", SecurityCategory.PERMISSION_BYPASS, SecuritySeverity.MEDIUM,
                            "Command text is dynamic; its command vocabulary cannot be proven from this source snapshot.", sinkFlow, name, capability, false);
                }
            }
            case PRIVILEGE, SERVER_CONTROL -> {
                danger = true;
                finding(context, span, "PRIVILEGED_OPERATION", SecurityCategory.PRIVILEGE_ESCALATION,
                        guarded ? SecuritySeverity.HIGH : SecuritySeverity.CRITICAL,
                        "A privileged native operation requires explicit administrator review or a validated permission boundary.", step(all, context, span, name, "SINK"), name, capability, true);
            }
            case NETWORK -> {
                if (argument.constant instanceof String url && unsafeUrl(url, options.allowedNetworkHosts())) {
                    danger = true;
                    finding(context, span, "NETWORK_PRIVATE_TARGET", SecurityCategory.SSRF, SecuritySeverity.CRITICAL,
                            "The request targets a local/private/metadata address or an invalid network scheme.", sinkFlow, name, capability, true);
                } else if (argument.constant == null) {
                    danger = argument.unsafe;
                    finding(context, span, "NETWORK_DYNAMIC_TARGET", argument.unsafe ? SecurityCategory.SSRF : SecurityCategory.DYNAMIC_URL,
                            argument.unsafe ? SecuritySeverity.CRITICAL : SecuritySeverity.HIGH,
                            "A dynamic URL can select an internal destination; runtime address and redirect checks are required.", sinkFlow, name, capability, danger);
                }
                if (arguments.size() > 1 && arguments.get(1).sensitive && name.equals("web.post")) {
                    danger = true;
                    finding(context, span, "NETWORK_SECRET", SecurityCategory.DATA_EXFILTRATION, SecuritySeverity.CRITICAL,
                            "Source credentials reach an outbound request body.", step(arguments.get(1), context, span, name, "SINK"), name, capability, true);
                }
            }
            case FILE_READ, FILE_WRITE, FILE_DELETE, DATABASE_OPEN -> {
                if (argument.constant instanceof String path && unsafePath(path)) {
                    danger = true;
                    finding(context, span, "FILES_ESCAPE", SecurityCategory.PATH_TRAVERSAL, SecuritySeverity.CRITICAL,
                            "The path attempts to leave TachyonScript's confined data directory.", sinkFlow, name, capability, true);
                } else if (argument.unsafe) {
                    danger = true;
                    finding(context, span, "FILES_DYNAMIC_PATH", SecurityCategory.UNSAFE_FILE_OPERATION, SecuritySeverity.HIGH,
                            "Untrusted data controls a filesystem path; confinement is enforced by the runtime.", sinkFlow, name, capability, true);
                }
            }
            case DATABASE_QUERY -> {
                if (argument.unsafe) {
                    danger = true;
                    finding(context, span, "SQL_TAINT", SecurityCategory.SQL_INJECTION, SecuritySeverity.CRITICAL,
                            "Untrusted text becomes SQL query syntax; use a constant statement with bound parameters.", sinkFlow, name, capability, true);
                } else if (argument.constant == null) finding(context, span, "SQL_DYNAMIC", SecurityCategory.DYNAMIC_SQL, SecuritySeverity.HIGH,
                        "SQL text is not statically constant. Bind values separately from the statement.", sinkFlow, name, capability, false);
            }
            case SCHEDULE -> {
                if (context.loops > 0) {
                    danger = true;
                    finding(context, span, "SCHEDULING_IN_LOOP", SecurityCategory.TASK_EXPLOSION, SecuritySeverity.HIGH,
                            "Scheduling inside a loop creates work proportional to loop iterations; use one bounded recurring task.", sinkFlow, name, capability, true);
                } else if (context.scheduled) {
                    danger = true;
                    finding(context, span, "SCHEDULING_FROM_TASK", SecurityCategory.RECURSIVE_SCHEDULING, SecuritySeverity.HIGH,
                            "A recurring task creates additional scheduled work; review multiplicative growth.", sinkFlow, name, capability, true);
                } else if (context.handler.startsWith("event ")) finding(context, span, "EVENT_SCHEDULING", SecurityCategory.EVENT_SPAM,
                        SecuritySeverity.MEDIUM, "Each event creates scheduled work; runtime task quotas provide a backstop.", sinkFlow, name, capability, false);
            }
            default -> { }
        }
        if (danger && argument.constant instanceof String command && dangerousCommand(command)
                && code(context, span).contains("+") && !code(context, span).contains("\"" + command.split("\\s+", 2)[0]))
            finding(context, span, "RECONSTRUCTED_COMMAND", SecurityCategory.OBFUSCATION, SecuritySeverity.HIGH,
                    "A privileged command is reconstructed from separate source expressions.", sinkFlow, name, capability, true);
        if (capability == Capability.IO)
            finding(context, span, "UNCLASSIFIED_IO", SecurityCategory.UNKNOWN_CAPABILITY, SecuritySeverity.MEDIUM,
                    "This resolved IO native requires an explicit capability classification in security.native-capabilities.",
                    all, name, capability, false);
        // Native declarations and their effects are always exported, including unknown addon capabilities.
        node(evidenceContext, span, "NativeCall", target.key(), capability, target.effects().stream().map(Enum::name).sorted().toList(),
                arguments, sinkFlow, danger);
        if (target.kind() == NativeDeclaration.Kind.EVENT_VARIABLE || name.matches(".*Event\\.(message|command|args|input|text|address|url).*"))
            return source(context, span, code(context, span), textType(target.returnType().displayName()));
        if (capability == Capability.EXTERNAL_DATA || capability == Capability.FILE_READ && (name.equals("files.read") || name.equals("files.lines")))
            return source(context, span, name, textType(target.returnType().displayName()));
        if (capability == Capability.DATABASE_QUERY && textType(target.returnType().displayName()))
            return source(context, span, name + " result", true);
        if (name.equals("Player.name:get") || name.equals("OfflinePlayer.name:get") || name.endsWith(".uuid:get"))
            return source(context, span, code(context, span), false);
        if (target.returnType().displayName().equals("void")) return Flow.clean();
        Flow returned = all.present ? all.withConstant(null) : Flow.unknown();
        Object folded = foldedNative(name, arguments);
        if (folded != null) returned = returned.withConstant(folded);
        if ((name.equals("string.charAt") || name.equals("string.substring")) && arguments.size() > 1
                && arguments.subList(1, arguments.size()).stream().anyMatch(Flow::tainted)) returned = returned.unsafeText();
        if (!textType(target.returnType().displayName())) returned = returned.safeText();
        return step(returned, context, span, name, "NATIVE_RETURN");
    }

    private SecurityNode node(Context context, Span span, String kind, String nativeId, Capability capability,
                              List<String> effects, List<Flow> arguments, Flow flow, boolean concrete) {
        SourceSpan location = SourceSpan.from(context.module.file(), span);
        String id = nodeId(context, span, kind + nativeId);
        List<Map<String, Object>> args = arguments.stream().<Map<String, Object>>map(argument -> Map.of(
                "tainted", argument.tainted(), "unsafeText", argument.unsafe, "secret", argument.sensitive,
                "constant", argument.sensitive ? "[REDACTED]" : argument.constant == null ? "UNKNOWN" : redactor.redact(String.valueOf(argument.constant)),
                "source", sourceNames(argument))).toList();
        SecurityNode result = new SecurityNode(id, kind, location, context.function, context.handler, nativeId,
                capability, effects, code(context, span), args, flow.steps, concrete, context.permission);
        var bucket = nodes.get(context.module.name());
        SecurityNode previous = bucket.get(id);
        if (previous == null || !previous.concreteDanger() || concrete) bucket.put(id, result);
        if (bucket.size() > options.maxNodes()) throw new IllegalStateException("Security manifest node budget exceeded");
        return result;
    }

    private void finding(Context context, Span span, String rule, SecurityCategory category, SecuritySeverity severity,
                         String explanation, Flow flow, String sink, Capability capability, boolean concrete) {
        String id = "ST-" + ScriptIdentity.hash(context.module.file().hash() + ":" + span.start() + ":" + span.end()
                + ":" + rule + ":" + context.handler).substring(0, 24);
        SecurityFinding finding = new SecurityFinding(id, rule, category, severity, 1,
                ScriptIdentity.of(context.module.file().path()), context.module.file().hash(),
                nodeId(context, span, "NativeCall" + nativeKeyAt(context, span, sink)), SourceSpan.from(context.module.file(), span),
                context.function, context.handler, SourceSnippets.context(context.module.file(), SourceSpan.from(context.module.file(), span), redactor),
                sourceNames(flow), sink, capability.name(), explanation, code(context, span), recommendation(category),
                SecurityFinding.Origin.STATIC, concrete, flow.steps);
        var bucket = findings.get(context.module.name());
        SecurityFinding previous = bucket.get(id);
        if (previous == null || finding.severity().ordinal() >= previous.severity().ordinal()) bucket.put(id, finding);
        if (bucket.size() > options.maxFindings()) throw new IllegalStateException("Security finding budget exceeded");
    }

    // Findings are reconciled to actual nodes by span after traversal; this fallback ID never grants AI authority.
    private String nativeKeyAt(Context context, Span span, String sink) {
        return nodes.get(context.module.name()).values().stream().filter(n -> n.span().startOffset() == span.start()
                && n.span().endOffset() == span.end() && CapabilityCatalog.name(n.nativeId()).equals(sink))
                .map(SecurityNode::nativeId).findFirst().orElse(sink);
    }

    private Flow source(Context context, Span span, String expression, boolean unsafe) {
        TaintStep step = new TaintStep(nodeId(context, span, "Source"), SourceSpan.from(context.module.file(), span), redactor.redact(expression), "SOURCE");
        node(context, span, "Source", "", Capability.EXTERNAL_DATA, List.of(), List.of(), Flow.clean(), false);
        return new Flow(null, unsafe, false, List.of(step), true);
    }

    private Flow step(Flow flow, Context context, Span span, String expression, String kind) {
        if (!flow.tainted() && !flow.sensitive) return flow;
        return flow.append(new TaintStep(nodeId(context, span, kind), SourceSpan.from(context.module.file(), span), redactor.redact(expression), kind));
    }

    private String nodeId(Context context, Span span, String kind) {
        return "node-" + ScriptIdentity.hash(context.module.file().hash() + ":" + context.module.file().path() + ":"
                + span.start() + ":" + span.end() + ":" + kind + ":" + context.function + ":" + context.handler).substring(0, 32);
    }
    private String code(Context context, Span span) {
        String text = context.module.file().text(span);
        return redactor.redact(text.length() > 1500 ? text.substring(0, 1500) + " [truncated]" : text);
    }
    private static String sourceNames(Flow flow) {
        return flow.steps.stream().filter(step -> step.kind().equals("SOURCE")).map(TaintStep::expression).distinct().reduce((a, b) -> a + ", " + b).orElse("");
    }
    private static boolean textType(String type) {
        return type.contains("string") || type.contains("Component") || type.startsWith("List<") || type.startsWith("Map<")
                || type.equals("Row") || type.equals("DatabaseRow") || type.equals("JsonValue");
    }
    private static boolean secretName(String name) { return name.toLowerCase(Locale.ROOT).matches(".*(password|passwd|api.?key|secret|token|webhook|credential).*"); }
    private static Flow join(List<Flow> values) { Flow result = Flow.clean(); for (Flow value : values) result = result.join(value); return result; }
    private static boolean dangerousCommand(String command) {
        String normalized = command.stripLeading().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        String verb = normalized.split("\\s+", 2)[0];
        int namespace = verb.indexOf(':');
        if (namespace >= 0) verb = verb.substring(namespace + 1);
        return Set.of("op", "deop", "stop", "restart", "reload", "execute", "lp", "luckperms", "pex", "permissions").contains(verb);
    }
    public static boolean unsafePath(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.startsWith("/") || normalized.indexOf(':') >= 0 || normalized.indexOf('\0') >= 0
                || List.of(normalized.split("/", -1)).contains("..");
    }
    public static boolean unsafeUrl(String url, Set<String> allowed) {
        try {
            URI uri = URI.create(url);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return true;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (allowed.contains(host)) return false;
            return NetworkPolicy.privateHost(host);
        } catch (IllegalArgumentException e) { return true; }
    }
    private static boolean permissionCondition(BoundExpression expression) {
        if (expression instanceof BoundExpression.NativeCall call && CapabilityCatalog.name(call.target().key()).endsWith(".hasPermission")
                && call.arguments().size() == 2 && call.arguments().get(0) instanceof BoundExpression.LocalLoad receiver
                && Set.of("player", "sender").contains(receiver.local().name())
                && call.arguments().get(1) instanceof BoundExpression.Literal literal && literal.value() instanceof String value && !value.isBlank()) return true;
        return expression instanceof BoundExpression.Logical logical && logical.and()
                && (permissionCondition(logical.left()) || permissionCondition(logical.right()));
    }
    private static boolean exits(BoundStatement statement) {
        if (statement instanceof BoundStatement.Return || statement instanceof BoundStatement.Throw) return true;
        if (statement instanceof BoundStatement.Block block) return block.statements().stream().anyMatch(SecurityAnalyzer::exits);
        return statement instanceof BoundStatement.If branch && branch.elseBranch() != null && exits(branch.thenBranch()) && exits(branch.elseBranch());
    }
    private static boolean loopExit(BoundStatement statement) {
        if (statement instanceof BoundStatement.Break || statement instanceof BoundStatement.Return || statement instanceof BoundStatement.Throw) return true;
        if (statement instanceof BoundStatement.Block block) return block.statements().stream().anyMatch(SecurityAnalyzer::loopExit);
        return statement instanceof BoundStatement.If branch && (loopExit(branch.thenBranch()) || branch.elseBranch() != null && loopExit(branch.elseBranch()));
    }
    private static boolean invariant(BoundExpression expression) {
        return switch (expression) {
            case BoundExpression.Literal ignored -> true;
            case BoundExpression.LocalLoad load -> !load.local().isMutable();
            case BoundExpression.Conversion conversion -> invariant(conversion.operand());
            case BoundExpression.Not not -> invariant(not.operand());
            case BoundExpression.Compare compare -> invariant(compare.left()) && invariant(compare.right());
            case BoundExpression.Logical logical -> invariant(logical.left()) && invariant(logical.right());
            default -> false;
        };
    }
    private static Object foldedNative(String name, List<Flow> arguments) {
        if (arguments.isEmpty() || !(arguments.getFirst().constant instanceof String text)) return null;
        try {
            return switch (name) {
                case "string.trim" -> text.trim();
                case "string.lower", "string.lowercase:get" -> text.toLowerCase(Locale.ROOT);
                case "string.upper", "string.uppercase:get" -> text.toUpperCase(Locale.ROOT);
                case "string.reversed" -> new StringBuilder(text).reverse().toString();
                case "string.substring" -> arguments.size() >= 2 && arguments.get(1).constant instanceof Number start
                        && (arguments.size() == 2 || arguments.get(2).constant instanceof Number)
                        ? text.substring(start.intValue(), arguments.size() == 2 ? text.length() : ((Number) arguments.get(2).constant).intValue()) : null;
                case "string.replace" -> arguments.size() == 3 && arguments.get(1).constant instanceof String from
                        && arguments.get(2).constant instanceof String to ? text.replace(from, to) : null;
                default -> null;
            };
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) { return null; }
    }
    private static String recommendation(SecurityCategory category) {
        return switch (category) {
            case COMMAND_INJECTION, PERMISSION_BYPASS -> "Require an administrator permission and use fixed commands with validated typed arguments.";
            case SSRF, DYNAMIC_URL, UNSAFE_REDIRECT -> "Use fixed allowlisted HTTPS destinations; validate resolved addresses and every redirect.";
            case SQL_INJECTION, DYNAMIC_SQL -> "Use constant SQL with bound parameters.";
            case CREDENTIAL_EXPOSURE, DATA_EXFILTRATION -> "Remove credentials from source and rotate exposed secrets.";
            case UNBOUNDED_LOOP, TASK_EXPLOSION, RECURSIVE_SCHEDULING, EVENT_SPAM -> "Bound work, rate-limit events and use one cancellable scheduled task.";
            default -> "Review this exact source expression and restrict the native capability.";
        };
    }

    private record Context(BoundModule module, String function, String handler, String permission, int depth, int loops, boolean scheduled) {
        Context inLoop() { return new Context(module, function, handler, permission, depth, loops + 1, scheduled); }
    }
    private static final class State {
        final Map<LocalSymbol, Flow> values = new IdentityHashMap<>();
        Flow returned = Flow.clean();
        boolean guarded;
        State copy() { State result = new State(); result.values.putAll(values); result.returned = returned; result.guarded = guarded; return result; }
        String signature() {
            return values.entrySet().stream().map(e -> System.identityHashCode(e.getKey()) + ":" + e.getValue().signature()).sorted().toList()
                    + ":" + returned.signature() + ":" + guarded;
        }
        void merge(State a, State b) {
            values.clear(); values.putAll(a.values);
            b.values.forEach((key, value) -> values.merge(key, value, Flow::join));
            returned = a.returned.join(b.returned); guarded = a.guarded && b.guarded;
        }
    }
    private record Flow(Object constant, boolean unsafe, boolean sensitive, List<TaintStep> steps, boolean present) {
        static Flow clean() { return new Flow(null, false, false, List.of(), false); }
        static Flow unknown() { return new Flow(null, false, false, List.of(), true); }
        static Flow constant(Object value) { return new Flow(value, false, false, List.of(), true); }
        boolean tainted() { return steps.stream().anyMatch(step -> step.kind().equals("SOURCE")); }
        Flow secret() { return new Flow(constant, unsafe, true, steps, true); }
        Flow safeText() { return new Flow(constant, false, sensitive, steps, present); }
        Flow unsafeText() { return new Flow(constant, true, sensitive, steps, true); }
        Flow withConstant(Object value) { return new Flow(value, unsafe, sensitive, steps, true); }
        Flow append(TaintStep step) {
            if (steps.contains(step)) return this;
            if (steps.size() >= 512) throw new IllegalStateException("Complete security flow exceeds path budget");
            List<TaintStep> result = new ArrayList<>(steps); result.add(step);
            return new Flow(constant, unsafe, sensitive, List.copyOf(result), true);
        }
        Flow join(Flow other) {
            if (!other.present) return this;
            if (!present) return other;
            List<TaintStep> joined = new ArrayList<>(steps);
            for (TaintStep step : other.steps) if (!joined.contains(step)) {
                if (joined.size() >= 512) throw new IllegalStateException("Complete security flow exceeds path budget");
                joined.add(step);
            }
            return new Flow(java.util.Objects.equals(constant, other.constant) ? constant : null,
                    unsafe || other.unsafe, sensitive || other.sensitive, List.copyOf(joined), true);
        }
        String signature() { return present + ":" + unsafe + ":" + sensitive + ":" + constant + ":" + steps.stream().filter(s -> s.kind().equals("SOURCE")).map(TaintStep::nodeId).sorted().toList(); }
    }
}
