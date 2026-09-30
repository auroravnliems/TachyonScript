package dev.tachyonscript.ir;

import java.util.List;

/**
 * A basic block: straight-line instructions ended by exactly one terminator.
 *
 * @param index       position of the block in its function (block 0 is the entry)
 * @param instructions the instructions, in order
 * @param terminator  the control transfer ending the block
 * @param handler     index of the block that receives errors raised in this block (its first
 *                    instruction is an {@link Instruction.Catch}), or {@code -1} when errors
 *                    leave the function
 */
public record IrBlock(int index, List<Instruction> instructions, Terminator terminator, int handler) {

    public IrBlock {
        instructions = List.copyOf(instructions);
        java.util.Objects.requireNonNull(terminator, "terminator");
    }

    /** A block without an exception handler. */
    public IrBlock(int index, List<Instruction> instructions, Terminator terminator) {
        this(index, instructions, terminator, -1);
    }

    /** Whether errors raised in this block are caught in the function. */
    public boolean hasHandler() {
        return handler >= 0;
    }

    /** Whether this block is an exception handler (starts with {@link Instruction.Catch}). */
    public boolean isHandler() {
        return !instructions.isEmpty() && instructions.getFirst() instanceof Instruction.Catch;
    }
}
