package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.Terminator;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Bypasses empty forwarding blocks, never the back-edge jumps that carry watchdog checks. */
public final class JumpThreading implements IrPass {
    @Override
    public IrFunction run(IrFunction function) {
        List<IrBlock> blocks = new ArrayList<>();
        for (IrBlock block : function.blocks()) {
            Terminator terminator = switch (block.terminator()) {
                case Terminator.Jump j -> new Terminator.Jump(target(function, j.target()), j.backEdge(), j.span());
                case Terminator.Branch b -> new Terminator.Branch(b.condition(), target(function, b.ifTrue()),
                        target(function, b.ifFalse()), b.span());
                default -> block.terminator();
            };
            blocks.add(new IrBlock(block.index(), block.instructions(), terminator, block.handler()));
        }
        return function.withBlocks(blocks);
    }

    private static int target(IrFunction function, int start) {
        BitSet visited = new BitSet();
        int current = start;
        while (!visited.get(current)) {
            visited.set(current);
            IrBlock block = function.blocks().get(current);
            if (!block.instructions().isEmpty() || !(block.terminator() instanceof Terminator.Jump jump)
                    || jump.backEdge()) {
                return current;
            }
            current = jump.target();
        }
        return start;
    }
}
