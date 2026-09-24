package dev.tachyonscript.ir;

import java.util.List;

/**
 * A basic block: straight-line instructions ended by exactly one terminator.
 *
 * @param index position of the block in its function (block 0 is the entry)
 */
public record IrBlock(int index, List<Instruction> instructions, Terminator terminator) {

    public IrBlock {
        instructions = List.copyOf(instructions);
        java.util.Objects.requireNonNull(terminator, "terminator");
    }
}
