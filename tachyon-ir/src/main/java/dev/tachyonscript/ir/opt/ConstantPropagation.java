package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.Register;
import dev.tachyonscript.ir.Terminator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact primitive evaluation within each block; no facts cross joins, loops or handlers. */
public final class ConstantPropagation implements IrPass {
    @Override
    public IrFunction run(IrFunction function) {
        List<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : function.blocks()) {
            Map<Register, Object> constants = new HashMap<>();
            List<Instruction> instructions = new ArrayList<>();
            for (Instruction instruction : block.instructions()) {
                Instruction replacement = instruction;
                if (instruction instanceof Instruction.Move move && constants.containsKey(move.source())) {
                    replacement = new Instruction.Const(move.target(), constants.get(move.source()), move.span());
                } else if (instruction instanceof Instruction.Unary unary && constants.containsKey(unary.operand())) {
                    replacement = new Instruction.Const(unary.target(),
                            ConstantEvaluation.unary(unary.op(), constants.get(unary.operand())), unary.span());
                } else if (instruction instanceof Instruction.Binary binary
                        && constants.containsKey(binary.left()) && constants.containsKey(binary.right())) {
                    try {
                        replacement = new Instruction.Const(binary.target(), ConstantEvaluation.binary(binary.op(),
                                constants.get(binary.left()), constants.get(binary.right())), binary.span());
                    } catch (ArithmeticException zeroDivisor) {
                        // The original instruction must throw at its original source location at runtime.
                    }
                }
                if (replacement instanceof Instruction.Const constant) {
                    constants.put(constant.target(), constant.value());
                } else if (replacement.target() != null) {
                    constants.remove(replacement.target());
                }
                instructions.add(replacement);
            }
            Terminator terminator = block.terminator();
            if (terminator instanceof Terminator.Branch branch) {
                Object value = constants.get(branch.condition());
                if (value instanceof Boolean condition) {
                    terminator = new Terminator.Jump(condition ? branch.ifTrue() : branch.ifFalse(), false, branch.span());
                } else if (branch.ifTrue() == branch.ifFalse()) {
                    terminator = new Terminator.Jump(branch.ifTrue(), false, branch.span());
                }
            }
            blocks.add(new IrBlock(block.index(), instructions, terminator, block.handler()));
        }
        return function.withBlocks(blocks);
    }
}
