package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.ScriptFunction;

/**
 * A function value: a compiled function with the values it captured when it was created (a
 * lambda or scheduled block), or with none (a reference to a script function).
 *
 * <p>The callee's first {@code captureCount} parameters receive the captured values, split by
 * representation in parameter order; the remaining parameters are the call's arguments.
 * Immutable, so a closure can be called from any thread, also concurrently.
 */
public final class Closure implements ScriptFunction {

    private static final long[] NO_PRIMITIVES = new long[0];
    private static final Object[] NO_REFERENCES = new Object[0];

    final CompiledFunction function;
    final int captureCount;
    final long[] primitives;
    final Object[] references;

    Closure(CompiledFunction function, int captureCount, long[] primitives, Object[] references) {
        this.function = function;
        this.captureCount = captureCount;
        this.primitives = primitives;
        this.references = references;
    }

    /** A closure without captured values. */
    public static Closure of(CompiledFunction function) {
        return new Closure(function, 0, NO_PRIMITIVES, NO_REFERENCES);
    }

    /** Creates a closure capturing boxed values (for tools and tests). */
    public static Closure of(CompiledFunction function, Object... captured) {
        int primitiveCount = 0;
        for (int i = 0; i < captured.length; i++) {
            if (!function.parameterIsReference[i]) {
                primitiveCount++;
            }
        }
        long[] primitiveValues = new long[primitiveCount];
        Object[] referenceValues = new Object[captured.length - primitiveCount];
        int p = 0;
        int r = 0;
        for (int i = 0; i < captured.length; i++) {
            if (function.parameterIsReference[i]) {
                referenceValues[r++] = captured[i];
            } else {
                primitiveValues[p++] = Interpreter.toSlot(captured[i]);
            }
        }
        return new Closure(function, captured.length, primitiveValues, referenceValues);
    }

    /** The compiled function this value calls. */
    public CompiledFunction function() {
        return function;
    }

    /** The owner of the function (the script instance that created it), or null. */
    public Object owner() {
        return function.owner;
    }

    @Override
    public int arity() {
        return function.parameterSlots.length - captureCount;
    }

    @Override
    public Object invoke() {
        return Interpreter.callClosure(this);
    }

    @Override
    public Object invoke(Object argument) {
        return Interpreter.callClosure(this, argument);
    }

    @Override
    public Object invoke(Object first, Object second) {
        return Interpreter.callClosure(this, first, second);
    }

    @Override
    public Object invokeWithArguments(Object... arguments) {
        return Interpreter.callClosure(this, arguments);
    }

    @Override
    public String describe() {
        return function.displayName;
    }

    @Override
    public String toString() {
        return "function (" + function.displayName + ")";
    }
}
