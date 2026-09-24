package dev.tachyonscript.compiler;

import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.ir.FunctionBuilder;
import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.ir.Register;
import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.ir.Terminator;
import dev.tachyonscript.ir.opt.RemoveUnreachableBlocks;
import dev.tachyonscript.language.semantic.BoundEventHandler;
import dev.tachyonscript.language.semantic.BoundFunction;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.semantic.LocalSymbol;

import java.util.ArrayList;
import java.util.List;

/** Lowers a {@link BoundModule} (free of errors) to an {@link IrModule}. */
public final class Lowering {

    private static final RemoveUnreachableBlocks CLEANUP = new RemoveUnreachableBlocks();

    private Lowering() {
    }

    public static IrModule lower(BoundModule module) {
        List<IrFunction> functions = new ArrayList<>();
        List<IrModule.EventHandler> handlers = new ArrayList<>();
        for (BoundFunction function : module.functions()) {
            functions.add(lowerFunction(function));
        }
        int index = 0;
        for (BoundEventHandler handler : module.handlers()) {
            IrFunction function = lowerHandler(handler, index++);
            functions.add(function);
            handlers.add(new IrModule.EventHandler(handler.event(), function.key()));
        }
        SourceText source = new SourceText(module.file().path(), module.file().content());
        return new IrModule(module.name(), source, functions, handlers);
    }

    private static IrFunction lowerFunction(BoundFunction function) {
        FunctionBuilder builder = new FunctionBuilder(function.symbol().key(), "function " + function.symbol().name(),
                IrFunction.Kind.FUNCTION, function.symbol().returnType(), FunctionLowering.span(function.span()));
        FunctionLowering lowering = new FunctionLowering(builder);
        for (LocalSymbol parameter : function.parameters()) {
            lowering.bind(parameter, builder.parameter(parameter.type(), parameter.name()));
        }
        lowering.statement(function.body());
        finish(builder, function.symbol().returnType() == PrimitiveType.VOID, FunctionLowering.span(function.span()));
        return CLEANUP.run(builder.build());
    }

    private static IrFunction lowerHandler(BoundEventHandler handler, int index) {
        long span = FunctionLowering.span(handler.span());
        String name = handler.event().name();
        FunctionBuilder builder = new FunctionBuilder("event " + name + " #" + index, "event " + name,
                IrFunction.Kind.EVENT_HANDLER, PrimitiveType.VOID, span);
        FunctionLowering lowering = new FunctionLowering(builder);
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

    private static void finish(FunctionBuilder builder, boolean isVoid, long span) {
        if (!builder.isTerminated()) {
            // Non-void functions cannot reach their end (the binder checks every path returns).
            builder.terminate(isVoid ? new Terminator.Return(null, span) : new Terminator.Unreachable(span));
        }
    }
}
