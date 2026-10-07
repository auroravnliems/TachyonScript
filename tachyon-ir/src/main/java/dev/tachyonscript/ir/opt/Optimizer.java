package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.ir.verify.IrVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Runs the IR optimization pipeline over every function of a module.
 *
 * <p>Every pass must preserve semantics exactly (including evaluation order of side
 * effects and runtime errors) and keep the IR verifiable; the compiler verifies the result.
 */
public final class Optimizer {

    private record Pass(String name, IrPass implementation) { }

    private static final List<Pass> PIPELINE = List.of(
            new Pass("unreachable-blocks", new RemoveUnreachableBlocks()),
            new Pass("copy-propagation", new CopyPropagation()),
            new Pass("constant-propagation", new ConstantPropagation()),
            new Pass("dead-code", new DeadCodeElimination()),
            new Pass("jump-threading", new JumpThreading()),
            new Pass("unreachable-blocks", new RemoveUnreachableBlocks()));

    private Optimizer() {
    }

    public static IrModule optimize(IrModule module) {
        return optimize(module, Set.of(), null);
    }

    /** Stable debugging identifiers in pipeline order; an identifier may run more than once. */
    public static List<String> passes() {
        return PIPELINE.stream().map(Pass::name).distinct().toList();
    }

    /**
     * Disables selected passes independently. When supplied, the observer receives verified
     * snapshots after every enabled pass. No mutable process-wide optimizer state is used.
     */
    public static IrModule optimize(IrModule module, Set<String> disabled,
                                    BiConsumer<String, IrModule> observer) {
        for (String name : disabled) {
            if (!passes().contains(name)) {
                throw new IllegalArgumentException("Unknown optimization pass: " + name);
            }
        }
        IrVerifier.verify(module);
        IrModule current = module;
        for (Pass pass : PIPELINE) {
            if (disabled.contains(pass.name())) {
                continue;
            }
            List<IrFunction> optimized = new ArrayList<>(current.functions().size());
            for (IrFunction function : current.functions()) {
                optimized.add(pass.implementation().run(function));
            }
            current = current.withFunctions(optimized);
            if (observer != null) {
                IrVerifier.verify(current);
                observer.accept(pass.name(), current);
            }
        }
        IrVerifier.verify(current);
        return current;
    }
}
