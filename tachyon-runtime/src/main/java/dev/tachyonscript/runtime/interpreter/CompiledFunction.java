package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.runtime.code.CodeUnit;
import dev.tachyonscript.runtime.ExecutionBackend;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import dev.tachyonscript.runtime.spi.TextService;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;
import dev.tachyonscript.runtime.value.RecordType;

/**
 * An executable function: packed code with every symbolic reference resolved to a direct
 * object reference (natives, callees, classes, templates, linked constants, top-level
 * variables, record types).
 *
 * <p>Created by the linker. Immutable after linking and safe to execute concurrently.
 */
public final class CompiledFunction {

    final String displayName;
    final int[] code;
    final int primitiveSlots;
    final int referenceSlots;
    final int[] parameterSlots;
    final boolean[] parameterIsReference;
    final long[] primitivePool;
    final Object[] referencePool;
    final NativeFunction[] natives;
    final Class<?>[] classes;
    final MessageTemplate[] templates;
    final GlobalCell[] globals;
    final PlayerDataSlot[] playerData;
    final RecordType[] records;
    final int[] handlers;
    final TextService text;
    final CodeUnit unit;
    final SourceText source;
    final Object owner;
    /** The owner as a revocation guard, resolved once instead of on every call. */
    final ExecutionGuard guard;
    final BytecodeBody bytecode;
    CompiledFunction[] callees;

    /**
     * @param owner the script instance the function belongs to (used by the engine to attach
     *              scheduled tasks to their script), or {@code null}
     */
    public CompiledFunction(CodeUnit unit, SourceText source, Object[] referencePool, NativeFunction[] natives,
                            Class<?>[] classes, MessageTemplate[] templates, GlobalCell[] globals,
                            PlayerDataSlot[] playerData, RecordType[] records, TextService text, Object owner) {
        this(unit, source, referencePool, natives, classes, templates, globals, playerData, records, text, owner, null);
    }

    public CompiledFunction(CodeUnit unit, SourceText source, Object[] referencePool, NativeFunction[] natives,
                            Class<?>[] classes, MessageTemplate[] templates, GlobalCell[] globals,
                            PlayerDataSlot[] playerData, RecordType[] records, TextService text, Object owner,
                            BytecodeBody bytecode) {
        this.unit = unit;
        this.source = source;
        this.displayName = unit.displayName();
        this.code = unit.code();
        this.primitiveSlots = unit.primitiveSlots();
        this.referenceSlots = unit.referenceSlots();
        this.parameterSlots = unit.parameterSlots();
        this.parameterIsReference = unit.parameterIsReference();
        this.primitivePool = unit.primitivePool();
        this.referencePool = referencePool;
        this.natives = natives;
        this.classes = classes;
        this.templates = templates;
        this.globals = globals;
        this.playerData = playerData;
        this.records = records;
        this.handlers = unit.handlers();
        this.text = text;
        this.owner = owner;
        this.guard = owner instanceof ExecutionGuard executionGuard ? executionGuard : null;
        this.bytecode = bytecode;
    }

    /** Sets the functions this one calls or creates closures of (second linking phase, allows recursion). */
    public void resolveCallees(CompiledFunction[] resolved) {
        if (callees != null) {
            throw new IllegalStateException("Callees of " + unit.key() + " are already resolved");
        }
        callees = resolved;
    }

    public String key() {
        return unit.key();
    }

    public String displayName() {
        return displayName;
    }

    public IrFunction.Kind kind() {
        return unit.kind();
    }

    public CodeUnit unit() {
        return unit;
    }

    public ExecutionBackend backend() {
        return bytecode == null ? ExecutionBackend.INTERPRETER : ExecutionBackend.BYTECODE;
    }

    public SourceText source() {
        return source;
    }

    /** The script instance this function belongs to, or {@code null}. */
    public Object owner() {
        return owner;
    }

    public int parameterCount() {
        return parameterSlots.length;
    }

    @Override
    public String toString() {
        return unit.key();
    }
}
