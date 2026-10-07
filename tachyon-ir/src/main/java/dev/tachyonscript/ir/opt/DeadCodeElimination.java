package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/** Removes unused primitive calculations and overwritten local stores, preserving all observable calls/errors. */
public final class DeadCodeElimination implements IrPass {
    @Override
    public IrFunction run(IrFunction function) {
        Liveness liveness = new Liveness(function);
        List<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : function.blocks()) {
            BitSet live = liveness.exit(block);
            BitSet exceptional = liveness.exceptional(block);
            List<Instruction> kept = new ArrayList<>();
            for (int i = block.instructions().size() - 1; i >= 0; i--) {
                Instruction instruction = block.instructions().get(i);
                if (Liveness.transfer(instruction, live, exceptional)) {
                    kept.add(instruction);
                }
            }
            Collections.reverse(kept);
            blocks.add(new IrBlock(block.index(), kept, block.terminator(), block.handler()));
        }
        return function.withBlocks(blocks);
    }
}
