package dev.tachyonscript.compiler;

import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.ir.FunctionBuilder;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.ir.RecordRef;
import dev.tachyonscript.ir.Register;
import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.ir.Terminator;
import dev.tachyonscript.ir.opt.RemoveUnreachableBlocks;
import dev.tachyonscript.language.semantic.BoundCommand;
import dev.tachyonscript.language.semantic.BoundEventHandler;
import dev.tachyonscript.language.semantic.BoundExpression;
import dev.tachyonscript.language.semantic.BoundFunction;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.semantic.BoundPlaceholder;
import dev.tachyonscript.language.semantic.BoundTask;
import dev.tachyonscript.language.semantic.FunctionSymbol;
import dev.tachyonscript.language.semantic.GlobalSymbol;
import dev.tachyonscript.language.semantic.LocalSymbol;
import dev.tachyonscript.language.semantic.RecordSymbol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Lowers a {@link BoundModule} (free of errors) to an {@link IrModule}.
 *
 * <p>Every unit of code becomes one IR function: script functions and record methods, event
 * handlers, commands, tasks, lifecycle hooks, placeholders, the initializer of the top-level
 * variables and the initial values of {@code playerdata} variables. Lambdas and scheduled
 * blocks found while lowering are queued and lowered afterwards as functions whose first
 * parameters receive the captured values.
 */
public final class Lowering {

    private static final RemoveUnreachableBlocks CLEANUP = new RemoveUnreachableBlocks();

    private final BoundModule module;
    private final List<IrFunction> functions = new ArrayList<>();
    private final Deque<BoundExpression.Lambda> lambdas = new ArrayDeque<>();

    private Lowering(BoundModule module) {
        this.module = module;
    }

    public static IrModule lower(BoundModule module) {
        return new Lowering(module).run();
    }

    private IrModule run() {
        List<IrModule.EventHandler> handlers = new ArrayList<>();
        for (BoundFunction function : module.functions()) {
            functions.add(lowerFunction(function, IrFunction.Kind.FUNCTION));
        }
        int index = 0;
        for (BoundEventHandler handler : module.handlers()) {
            IrFunction function = lowerHandler(handler, index++);
            functions.add(function);
            handlers.add(new IrModule.EventHandler(handler.event(), function.key(), handler.priority(),
                    handler.ignoreCancelled()));
        }
        for (BoundCommand command : module.commands()) {
            functions.add(lowerFunction(command.function(), IrFunction.Kind.COMMAND));
        }
        for (BoundTask task : module.tasks()) {
            functions.add(lowerFunction(task.function(), IrFunction.Kind.TASK));
        }
        for (BoundFunction hook : module.loadHooks()) {
            functions.add(lowerFunction(hook, IrFunction.Kind.LIFECYCLE));
        }
        for (BoundFunction hook : module.unloadHooks()) {
            functions.add(lowerFunction(hook, IrFunction.Kind.LIFECYCLE));
        }
        for (BoundPlaceholder placeholder : module.placeholders()) {
            functions.add(lowerFunction(placeholder.function(), IrFunction.Kind.PLACEHOLDER));
        }
        if (module.initializer() != null) {
            functions.add(lowerFunction(module.initializer(), IrFunction.Kind.INITIALIZER));
        }
        for (BoundFunction value : module.defaults().values()) {
            functions.add(lowerFunction(value, IrFunction.Kind.PLAYERDATA_DEFAULT));
        }
        while (!lambdas.isEmpty()) {
            functions.add(lowerLambda(lambdas.poll()));
        }
        List<GlobalRef> globals = module.globals().stream().map(Lowering::global).toList();
        List<RecordRef> records = module.records().stream().map(Lowering::record).toList();
        SourceText source = new SourceText(module.file().path(), module.file().content());
        return new IrModule(module.name(), source, functions, handlers, globals, records);
    }

    // ------------------------------------------------------------------ units

    private IrFunction lowerFunction(BoundFunction function, IrFunction.Kind kind) {
        long span = FunctionLowering.span(function.span());
        FunctionBuilder builder = new FunctionBuilder(function.key(), function.displayName(), kind, function.returnType(), span);
        FunctionLowering lowering = new FunctionLowering(this, builder, function.returnType());
        for (LocalSymbol parameter : function.parameters()) {
            lowering.bind(parameter, builder.parameter(parameter.type(), parameter.name()));
        }
        lowering.statement(function.body());
        finish(builder, function.returnType() == PrimitiveType.VOID, span);
        return CLEANUP.run(builder.build());
    }

    private IrFunction lowerHandler(BoundEventHandler handler, int index) {
        long span = FunctionLowering.span(handler.span());
        String name = handler.event().name();
        FunctionBuilder builder = new FunctionBuilder("event " + name + " #" + index, "event " + name,
                IrFunction.Kind.EVENT_HANDLER, PrimitiveType.VOID, span);
        FunctionLowering lowering = new FunctionLowering(this, builder, PrimitiveType.VOID);
        Register eventObject = builder.parameter(handler.eventObject().type(), handler.eventObject().name());
        lowering.bind(handler.eventObject(), eventObject);
        for (LocalSymbol variable : handler.eventVariables()) {
            Register register = builder.register(variable.type(), variable.name());
            builder.emit(new Instruction.CallNative(register, variable.eventVariable().getter(), List.of(eventObject), span));
            lowering.bind(variable, register);
        }
        lowering.statement(handler.body());
        finish(builder, true, span);
        return CLEANUP.run(builder.build());
    }

    /** A lambda: captured values first, then its own parameters. */
    private IrFunction lowerLambda(BoundExpression.Lambda lambda) {
        long span = FunctionLowering.span(lambda.span());
        FunctionBuilder builder = new FunctionBuilder(lambda.key(), lambda.displayName(), IrFunction.Kind.LAMBDA,
                lambda.returnType(), span);
        FunctionLowering lowering = new FunctionLowering(this, builder, lambda.returnType());
        for (LocalSymbol capture : lambda.captures()) {
            lowering.bind(capture, builder.parameter(capture.type(), capture.name()));
        }
        for (LocalSymbol parameter : lambda.parameters()) {
            lowering.bind(parameter, builder.parameter(parameter.type(), parameter.name()));
        }
        lowering.statement(lambda.body());
        finish(builder, lambda.returnType() == PrimitiveType.VOID, span);
        return CLEANUP.run(builder.build());
    }

    private static void finish(FunctionBuilder builder, boolean isVoid, long span) {
        if (!builder.isTerminated()) {
            // Non-void functions cannot reach their end (the binder checks every path returns).
            builder.terminate(isVoid ? new Terminator.Return(null, span) : new Terminator.Unreachable(span));
        }
    }

    // ------------------------------------------------------------------ references

    /** Queues a lambda for lowering and returns the reference its closure is created with. */
    FunctionRef lambda(BoundExpression.Lambda lambda) {
        lambdas.add(lambda);
        List<Type> parameters = new ArrayList<>();
        lambda.captures().forEach(capture -> parameters.add(capture.type()));
        lambda.parameters().forEach(parameter -> parameters.add(parameter.type()));
        return new FunctionRef(module.name(), lambda.key(), parameters, lambda.returnType());
    }

    static FunctionRef function(FunctionSymbol symbol) {
        List<Type> parameters = new ArrayList<>();
        if (symbol.receiver() != null) {
            parameters.add(symbol.receiver().type());
        }
        parameters.addAll(symbol.parameterTypes());
        return new FunctionRef(symbol.module(), symbol.key(), parameters, symbol.returnType());
    }

    static GlobalRef global(GlobalSymbol symbol) {
        GlobalRef.Storage storage = switch (symbol.storage()) {
            case SCRIPT -> GlobalRef.Storage.SCRIPT;
            case PERSISTENT -> GlobalRef.Storage.PERSISTENT;
            case PLAYERDATA -> GlobalRef.Storage.PLAYERDATA;
        };
        return new GlobalRef(symbol.module(), symbol.name(), symbol.type(), storage);
    }

    static RecordRef record(RecordSymbol symbol) {
        List<String> names = new ArrayList<>();
        List<Type> types = new ArrayList<>();
        for (RecordSymbol.Field field : symbol.fields()) {
            names.add(field.name());
            types.add(field.type());
        }
        return new RecordRef(symbol.module(), symbol.name(), names, types);
    }
}
