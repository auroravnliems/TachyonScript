package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;

import java.util.ArrayList;
import java.util.List;

/** Normal edges and exception edges stay distinct: exceptions observe intermediate register values. */
final class ControlFlowGraph {
    private final List<List<Integer>> predecessors;

    ControlFlowGraph(IrFunction function) {
        predecessors = new ArrayList<>();
        for (int i = 0; i < function.blocks().size(); i++) {
            predecessors.add(new ArrayList<>());
        }
        for (IrBlock block : function.blocks()) {
            for (int successor : block.terminator().successors()) {
                predecessors.get(successor).add(block.index());
            }
            if (block.hasHandler()) {
                predecessors.get(block.handler()).add(block.index());
            }
        }
    }

    List<Integer> predecessors(int block) {
        return predecessors.get(block);
    }
}
