package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.Arguments;

import java.util.Objects;

/**
 * Argument view handed to natives and templates: reads the caller's registers directly, so
 * a native call allocates nothing. One instance exists per call depth of each
 * {@link ExecutionStack}, which keeps views valid when a native re-enters the interpreter.
 */
final class CallArguments implements Arguments {

    private final ExecutionStack stack;
    private int[] code;
    private int position;
    private int count;
    private int primitiveBase;
    private int referenceBase;

    CallArguments(ExecutionStack stack) {
        this.stack = stack;
    }

    CallArguments reset(int[] code, int position, int count, int primitiveBase, int referenceBase) {
        this.code = code;
        this.position = position;
        this.count = count;
        this.primitiveBase = primitiveBase;
        this.referenceBase = referenceBase;
        return this;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public Object getRef(int index) {
        return stack.references[referenceBase + code[position + Objects.checkIndex(index, count)]];
    }

    @Override
    public int getInt(int index) {
        return (int) primitive(index);
    }

    @Override
    public long getLong(int index) {
        return primitive(index);
    }

    @Override
    public float getFloat(int index) {
        return Float.intBitsToFloat((int) primitive(index));
    }

    @Override
    public double getDouble(int index) {
        return Double.longBitsToDouble(primitive(index));
    }

    @Override
    public boolean getBool(int index) {
        return primitive(index) != 0;
    }

    private long primitive(int index) {
        return stack.primitives[primitiveBase + code[position + Objects.checkIndex(index, count)]];
    }
}
