package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;

import java.util.List;
import java.util.Objects;

/**
 * A function in IR form: typed virtual registers and a control-flow graph of basic blocks
 * (block 0 is the entry). Immutable; optimization passes return new instances.
 */
public final class IrFunction {

    /** What produced the function. */
    public enum Kind {
        FUNCTION,
        EVENT_HANDLER
    }

    private final String key;
    private final String displayName;
    private final Kind kind;
    private final List<Register> parameters;
    private final Type returnType;
    private final List<Register> registers;
    private final List<IrBlock> blocks;
    private final long span;

    /**
     * @param key         unique key within the module (used by {@link Instruction.Call})
     * @param displayName name shown in stack traces, e.g. {@code function reward}
     * @param kind        origin of the function
     * @param parameters  parameter registers, in order
     * @param returnType  return type ({@code void} allowed)
     * @param registers   every register, where {@code registers.get(i).index() == i}
     * @param blocks      basic blocks, where {@code blocks.get(i).index() == i}
     * @param span        packed source span of the declaration
     */
    public IrFunction(String key, String displayName, Kind kind, List<Register> parameters, Type returnType,
                      List<Register> registers, List<IrBlock> blocks, long span) {
        this.key = Objects.requireNonNull(key, "key");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.parameters = List.copyOf(parameters);
        this.returnType = Objects.requireNonNull(returnType, "returnType");
        this.registers = List.copyOf(registers);
        this.blocks = List.copyOf(blocks);
        this.span = span;
        for (int i = 0; i < this.registers.size(); i++) {
            if (this.registers.get(i).index() != i) {
                throw new IllegalArgumentException("Register " + i + " of " + key + " has index " + this.registers.get(i).index());
            }
        }
        for (int i = 0; i < this.blocks.size(); i++) {
            if (this.blocks.get(i).index() != i) {
                throw new IllegalArgumentException("Block " + i + " of " + key + " has index " + this.blocks.get(i).index());
            }
        }
        if (this.blocks.isEmpty()) {
            throw new IllegalArgumentException("Function " + key + " has no blocks");
        }
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public Kind kind() {
        return kind;
    }

    public List<Register> parameters() {
        return parameters;
    }

    public Type returnType() {
        return returnType;
    }

    public Representation returnKind() {
        return returnType.representation();
    }

    public List<Register> registers() {
        return registers;
    }

    public List<IrBlock> blocks() {
        return blocks;
    }

    public long span() {
        return span;
    }

    /** Number of instructions including terminators. */
    public int instructionCount() {
        int count = 0;
        for (IrBlock block : blocks) {
            count += block.instructions().size() + 1;
        }
        return count;
    }

    /** A copy with different blocks (same registers). */
    public IrFunction withBlocks(List<IrBlock> newBlocks) {
        return new IrFunction(key, displayName, kind, parameters, returnType, registers, newBlocks, span);
    }

    @Override
    public String toString() {
        return key;
    }
}
