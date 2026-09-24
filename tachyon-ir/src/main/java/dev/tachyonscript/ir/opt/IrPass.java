package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.IrFunction;

/** A function-level IR transformation. Passes must preserve semantics and verifiability. */
@FunctionalInterface
public interface IrPass {

    IrFunction run(IrFunction function);
}
