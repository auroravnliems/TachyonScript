package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.IrModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the IR optimization pipeline over every function of a module.
 *
 * <p>Every pass must preserve semantics exactly (including evaluation order of side
 * effects and runtime errors) and keep the IR verifiable; the compiler verifies the result.
 */
public final class Optimizer {

    private static final List<IrPass> PIPELINE = List.of(
            new RemoveUnreachableBlocks());

    private Optimizer() {
    }

    public static IrModule optimize(IrModule module) {
        List<IrFunction> optimized = new ArrayList<>(module.functions().size());
        for (IrFunction function : module.functions()) {
            IrFunction current = function;
            for (IrPass pass : PIPELINE) {
                current = pass.run(current);
            }
            optimized.add(current);
        }
        return module.withFunctions(optimized);
    }
}
