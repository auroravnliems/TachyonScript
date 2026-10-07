package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;

/** Backward def/use analysis, including values visible at every possible exceptional exit. */
final class Liveness {
    private final BitSet[] entry;

    Liveness(IrFunction function) {
        ControlFlowGraph graph = new ControlFlowGraph(function);
        int count = function.blocks().size();
        entry = new BitSet[count];
        Deque<Integer> work = new ArrayDeque<>();
        BitSet queued = new BitSet(count);
        for (int b = count - 1; b >= 0; b--) {
            entry[b] = new BitSet();
            work.add(b);
            queued.set(b);
        }
        while (!work.isEmpty()) {
            int index = work.removeFirst();
            queued.clear(index);
            IrBlock block = function.blocks().get(index);
            BitSet live = exit(block);
            BitSet exceptional = exceptional(block);
            for (int i = block.instructions().size() - 1; i >= 0; i--) {
                transfer(block.instructions().get(i), live, exceptional);
            }
            if (!live.equals(entry[index])) {
                entry[index] = live;
                for (int predecessor : graph.predecessors(index)) {
                    if (!queued.get(predecessor)) {
                        queued.set(predecessor);
                        work.addLast(predecessor);
                    }
                }
            }
        }
    }

    BitSet exceptional(IrBlock block) {
        return block.hasHandler() ? entry[block.handler()] : new BitSet();
    }

    BitSet exit(IrBlock block) {
        BitSet live = new BitSet();
        for (int successor : block.terminator().successors()) {
            live.or(entry[successor]);
        }
        block.terminator().operands().forEach(r -> live.set(r.index()));
        live.or(exceptional(block));
        return live;
    }

    /** Returns whether the instruction must remain and computes liveness immediately before it. */
    static boolean transfer(Instruction instruction, BitSet live, BitSet exceptional) {
        boolean needed = instruction.target() == null || live.get(instruction.target().index())
                || !Discardable.test(instruction);
        if (needed) {
            if (instruction.target() != null) {
                live.clear(instruction.target().index());
            }
            instruction.operands().forEach(r -> live.set(r.index()));
        }
        // A handler can see the old value even if a later instruction overwrites it.
        // Keeping entry values also preserves the verifier's conservative handler contract.
        live.or(exceptional);
        return needed;
    }
}
