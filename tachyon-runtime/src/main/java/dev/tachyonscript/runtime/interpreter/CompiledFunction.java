package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.runtime.code.CodeUnit;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import dev.tachyonscript.runtime.spi.TextService;

/**
 * An executable function: packed code with every symbolic reference resolved to a direct
 * object reference (natives, callees, classes, templates, linked constants).
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
    final TextService text;
    final CodeUnit unit;
    final SourceText source;
    CompiledFunction[] callees;

    public CompiledFunction(CodeUnit unit, SourceText source, Object[] referencePool, NativeFunction[] natives,
                            Class<?>[] classes, MessageTemplate[] templates, TextService text) {
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
        this.text = text;
    }

    /** Sets the functions this one calls (second linking phase, allows recursion). */
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

    public CodeUnit unit() {
        return unit;
    }

    public SourceText source() {
        return source;
    }

    public int parameterCount() {
        return parameterSlots.length;
    }

    @Override
    public String toString() {
        return unit.key();
    }
}
