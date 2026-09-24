package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Incrementally builds an {@link IrFunction}: allocates registers and blocks, appends
 * instructions at an insertion point and seals blocks with terminators.
 *
 * <p>Instructions emitted after the current block was terminated go into a fresh block
 * with no predecessors; such dead blocks are removed by {@link
 * dev.tachyonscript.ir.opt.RemoveUnreachableBlocks}.
 */
public final class FunctionBuilder {

    private final String key;
    private final String displayName;
    private final IrFunction.Kind kind;
    private final Type returnType;
    private final long span;
    private final List<Register> registers = new ArrayList<>();
    private final List<Register> parameters = new ArrayList<>();
    private final List<List<Instruction>> blockInstructions = new ArrayList<>();
    private final List<Terminator> terminators = new ArrayList<>();
    private int current;

    public FunctionBuilder(String key, String displayName, IrFunction.Kind kind, Type returnType, long span) {
        this.key = key;
        this.displayName = displayName;
        this.kind = kind;
        this.returnType = returnType;
        this.span = span;
        this.current = newBlock();
    }

    /** Declares the next parameter. Parameters must be declared before other registers. */
    public Register parameter(Type type, String name) {
        if (registers.size() != parameters.size()) {
            throw new IllegalStateException("Parameters must be declared first");
        }
        Register register = register(type, name);
        parameters.add(register);
        return register;
    }

    public Register register(Type type, String name) {
        Register register = new Register(registers.size(), type, name);
        registers.add(register);
        return register;
    }

    public Register temp(Type type) {
        return register(type, "");
    }

    /** Creates an empty block and returns its index (does not move the insertion point). */
    public int newBlock() {
        blockInstructions.add(new ArrayList<>());
        terminators.add(null);
        return blockInstructions.size() - 1;
    }

    /** Moves the insertion point to the end of {@code block}. */
    public void switchTo(int block) {
        current = block;
    }

    public int currentBlock() {
        return current;
    }

    /** Whether the current block already has a terminator. */
    public boolean isTerminated() {
        return terminators.get(current) != null;
    }

    public void emit(Instruction instruction) {
        if (isTerminated()) {
            current = newBlock();
        }
        blockInstructions.get(current).add(instruction);
    }

    public void terminate(Terminator terminator) {
        if (isTerminated()) {
            current = newBlock();
        }
        terminators.set(current, terminator);
    }

    /** Terminates the current block with a jump unless it is already terminated. */
    public void jumpIfOpen(int target, long jumpSpan) {
        if (!isTerminated()) {
            terminate(new Terminator.Jump(target, false, jumpSpan));
        }
    }

    public IrFunction build() {
        List<IrBlock> blocks = new ArrayList<>();
        for (int i = 0; i < blockInstructions.size(); i++) {
            Terminator terminator = terminators.get(i);
            if (terminator == null) {
                throw new IllegalStateException("Block " + i + " of " + key + " has no terminator");
            }
            blocks.add(new IrBlock(i, blockInstructions.get(i), terminator));
        }
        return new IrFunction(key, displayName, kind, parameters, returnType, registers, blocks, span);
    }
}
