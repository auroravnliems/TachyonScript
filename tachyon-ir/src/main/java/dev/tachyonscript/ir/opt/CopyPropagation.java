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

/** Block-local copies only: a join or exception edge never inherits another path's aliases. */
public final class CopyPropagation implements IrPass {
    @Override
    public IrFunction run(IrFunction function) {
        List<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : function.blocks()) {
            Map<Register, Register> copies = new HashMap<>();
            List<Instruction> instructions = new ArrayList<>();
            for (Instruction original : block.instructions()) {
                Instruction instruction = Instruction.mapOperands(original, r -> copies.getOrDefault(r, r));
                Register target = instruction.target();
                if (instruction instanceof Instruction.Move move && move.target().equals(move.source())) {
                    continue;
                }
                if (target != null) {
                    // Copies hold a snapshot, not a reference to a mutable local register.
                    copies.remove(target);
                    copies.values().removeIf(target::equals);
                }
                if (instruction instanceof Instruction.Move move && move.target().type().equals(move.source().type())) {
                    copies.put(target, move.source());
                }
                instructions.add(instruction);
            }
            Terminator terminator = switch (block.terminator()) {
                case Terminator.Branch b -> new Terminator.Branch(copies.getOrDefault(b.condition(), b.condition()),
                        b.ifTrue(), b.ifFalse(), b.span());
                case Terminator.Return r when r.value() != null ->
                        new Terminator.Return(copies.getOrDefault(r.value(), r.value()), r.span());
                case Terminator.Throw t -> new Terminator.Throw(copies.getOrDefault(t.value(), t.value()), t.span());
                default -> block.terminator();
            };
            blocks.add(new IrBlock(block.index(), instructions, terminator, block.handler()));
        }
        return function.withBlocks(blocks);
    }
}
