package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.Terminator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Removes blocks that cannot be reached from the entry and renumbers the rest, preserving
 * their relative order (which keeps the layout chosen by lowering).
 */
public final class RemoveUnreachableBlocks implements IrPass {

    @Override
    public IrFunction run(IrFunction function) {
        List<IrBlock> blocks = function.blocks();
        boolean[] reachable = new boolean[blocks.size()];
        Deque<Integer> work = new ArrayDeque<>();
        work.add(0);
        reachable[0] = true;
        while (!work.isEmpty()) {
            IrBlock block = blocks.get(work.poll());
            for (int successor : block.terminator().successors()) {
                if (!reachable[successor]) {
                    reachable[successor] = true;
                    work.add(successor);
                }
            }
            // An error in a reachable block reaches its handler.
            if (block.hasHandler() && !reachable[block.handler()]) {
                reachable[block.handler()] = true;
                work.add(block.handler());
            }
        }
        int[] renumber = new int[blocks.size()];
        Arrays.fill(renumber, -1);
        int next = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (reachable[i]) {
                renumber[i] = next++;
            }
        }
        if (next == blocks.size()) {
            return function;
        }
        List<IrBlock> result = new ArrayList<>(next);
        for (IrBlock block : blocks) {
            if (reachable[block.index()]) {
                result.add(new IrBlock(renumber[block.index()], block.instructions(), remap(block.terminator(), renumber),
                        block.hasHandler() ? renumber[block.handler()] : -1));
            }
        }
        return function.withBlocks(result);
    }

    static Terminator remap(Terminator terminator, int[] renumber) {
        return switch (terminator) {
            case Terminator.Jump jump -> new Terminator.Jump(renumber[jump.target()], jump.backEdge(), jump.span());
            case Terminator.Branch branch -> new Terminator.Branch(branch.condition(), renumber[branch.ifTrue()],
                    renumber[branch.ifFalse()], branch.span());
            default -> terminator;
        };
    }
}
