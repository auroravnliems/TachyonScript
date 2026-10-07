package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.ScriptFailure;
import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.ir.Spans;
import dev.tachyonscript.runtime.code.Opcodes;
import dev.tachyonscript.runtime.error.ScriptFrame;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.value.RecordType;
import dev.tachyonscript.runtime.value.RecordValue;
import dev.tachyonscript.runtime.value.ScriptList;
import dev.tachyonscript.runtime.value.ScriptMap;

import java.util.List;
import java.util.Objects;

import static dev.tachyonscript.runtime.code.Opcodes.*;

/**
 * Executes {@link CompiledFunction}s.
 *
 * <p>A single {@code switch} over the packed code. Register operands are frame-relative
 * slots in the thread's {@link ExecutionStack}; primitive values stay in {@code long} slots,
 * so arithmetic never boxes. Rare or bulky operations live in helper methods so that the
 * dispatch method stays well below HotSpot's 8000-byte limit for JIT compilation (a test
 * enforces this).
 *
 * <p>Checks are placed where they are cheap: loop back edges and function entries decrement a
 * counter and only read the clock when it runs out; script calls check the depth counter.
 * Revocation ({@link ExecutionGuard}) is checked at function entry, before natives and with
 * the clock, never per instruction. Any exception is converted into a
 * {@link ScriptRuntimeException} carrying a TachyonScript stack trace.
 *
 * <p>An error raised at a pc covered by the function's handler table ({@code try}) is caught
 * in the same frame: frames above it are abandoned, the error value is stored in the
 * handler's register and execution continues at the handler. Timeouts, runaway recursion and
 * internal errors cannot be caught.
 */
public final class Interpreter {

    private Interpreter() {
    }

    // =================================================================== entry points

    /**
     * Runs an event handler (a function taking the event object as its only parameter) on
     * the current thread.
     *
     * @throws ScriptRuntimeException if the script fails
     */
    public static void invokeHandler(CompiledFunction function, Object event) {
        ExecutionStack stack = ExecutionStack.current();
        int savedDepth = stack.depth;
        int primitiveBase = stack.primitiveTop;
        int referenceBase = stack.referenceTop;
        stack.ensure(primitiveBase + function.primitiveSlots, referenceBase + function.referenceSlots);
        stack.references[referenceBase + function.parameterSlots[0]] = event;
        if (savedDepth == 0) {
            stack.startExecution();
            stack.referenceHighWater = referenceBase + function.referenceSlots;
            stack.owner = function.owner();
        }
        stack.depth = savedDepth + 1;
        boolean completed = false;
        try {
            execute(function, stack, primitiveBase, referenceBase);
            completed = true;
        } finally {
            finish(stack, savedDepth, primitiveBase, referenceBase, completed);
        }
    }

    /**
     * Calls any function with boxed arguments and returns its boxed result ({@code null} for
     * void). Intended for tools, tests and lifecycle hooks, not for hot paths.
     */
    public static Object call(CompiledFunction function, Object... arguments) {
        if (arguments.length != function.parameterSlots.length) {
            throw new IllegalArgumentException(function.key() + " takes " + function.parameterSlots.length
                    + " arguments, got " + arguments.length);
        }
        ExecutionStack stack = ExecutionStack.current();
        int savedDepth = stack.depth;
        int primitiveBase = stack.primitiveTop;
        int referenceBase = stack.referenceTop;
        stack.ensure(primitiveBase + function.primitiveSlots, referenceBase + function.referenceSlots);
        for (int i = 0; i < arguments.length; i++) {
            int slot = function.parameterSlots[i];
            if (function.parameterIsReference[i]) {
                stack.references[referenceBase + slot] = arguments[i];
            } else {
                stack.primitives[primitiveBase + slot] = toSlot(arguments[i]);
            }
        }
        return run(function, stack, savedDepth, primitiveBase, referenceBase);
    }

    /**
     * Calls a function value with boxed arguments (after its captured values) and returns its
     * boxed result ({@code null} for void). Used by natives through
     * {@link dev.tachyonscript.api.natives.ScriptFunction}, and by the engine for scheduled blocks.
     */
    public static Object callClosure(Closure closure, Object... arguments) {
        CompiledFunction function = closure.function;
        int captured = closure.captureCount;
        if (captured + arguments.length != function.parameterSlots.length) {
            throw new IllegalArgumentException(function.displayName + " takes " + (function.parameterSlots.length - captured)
                    + " arguments, got " + arguments.length);
        }
        ExecutionStack stack = ExecutionStack.current();
        int savedDepth = stack.depth;
        int primitiveBase = stack.primitiveTop;
        int referenceBase = stack.referenceTop;
        stack.ensure(primitiveBase + function.primitiveSlots, referenceBase + function.referenceSlots);
        int primitiveIndex = 0;
        int referenceIndex = 0;
        for (int i = 0; i < captured; i++) {
            int slot = function.parameterSlots[i];
            if (function.parameterIsReference[i]) {
                stack.references[referenceBase + slot] = closure.references[referenceIndex++];
            } else {
                stack.primitives[primitiveBase + slot] = closure.primitives[primitiveIndex++];
            }
        }
        for (int j = 0; j < arguments.length; j++) {
            int i = captured + j;
            int slot = function.parameterSlots[i];
            if (function.parameterIsReference[i]) {
                stack.references[referenceBase + slot] = arguments[j];
            } else {
                stack.primitives[primitiveBase + slot] = toSlot(arguments[j]);
            }
        }
        return run(function, stack, savedDepth, primitiveBase, referenceBase);
    }

    /** Runs a function whose parameters are in place and returns its boxed result. */
    private static Object run(CompiledFunction function, ExecutionStack stack, int savedDepth, int primitiveBase,
                              int referenceBase) {
        if (savedDepth == 0) {
            stack.startExecution();
            stack.referenceHighWater = referenceBase + function.referenceSlots;
            stack.owner = function.owner();
        }
        stack.depth = savedDepth + 1;
        boolean completed = false;
        try {
            execute(function, stack, primitiveBase, referenceBase);
            completed = true;
            return switch (function.unit.returnKind()) {
                case VOID -> null;
                case REF -> {
                    Object result = stack.returnReference;
                    stack.returnReference = null;
                    yield result;
                }
                case INT -> (int) stack.returnPrimitive;
                case LONG -> stack.returnPrimitive;
                case FLOAT -> Float.intBitsToFloat((int) stack.returnPrimitive);
                case DOUBLE -> Double.longBitsToDouble(stack.returnPrimitive);
                case BOOL -> stack.returnPrimitive != 0;
            };
        } finally {
            finish(stack, savedDepth, primitiveBase, referenceBase, completed);
        }
    }

    private static void finish(ExecutionStack stack, int savedDepth, int primitiveBase, int referenceBase,
                               boolean completed) {
        if (!completed) {
            // Frames abandoned by an exception did not clear their reference slots.
            stack.clearReferences(referenceBase, stack.referenceHighWater);
            stack.referenceHighWater = referenceBase;
            stack.returnReference = null;
        }
        stack.depth = savedDepth;
        stack.primitiveTop = primitiveBase;
        stack.referenceTop = referenceBase;
        if (savedDepth == 0) {
            stack.owner = null;
            stack.verified = null;
        }
    }

    static long toSlot(Object value) {
        return switch (value) {
            case Integer i -> i;
            case Long l -> l;
            case Boolean b -> b ? 1 : 0;
            case Float f -> Float.floatToRawIntBits(f);
            case Double d -> Double.doubleToRawLongBits(d);
            default -> throw new IllegalArgumentException("Not a primitive value: " + value);
        };
    }

    // =================================================================== dispatch loop

    static void execute(CompiledFunction fn, ExecutionStack stack, int pb, int rb) {
        final ExecutionGuard guard = fn.guard;
        if (--stack.loopBudget <= 0 || guard != stack.verified) {
            try {
                enter(stack, guard);
            } catch (ScriptRuntimeException error) {
                // Neither a timeout nor a revocation is catchable: only record where it happened.
                throw error.addFrame(frame(fn, 0));
            }
        }
        if (fn.bytecode != null) {
            fn.bytecode.execute(fn, stack, pb, rb);
            return;
        }
        final int[] code = fn.code;
        final int depth = stack.depth;
        stack.primitiveTop = pb + fn.primitiveSlots;
        stack.referenceTop = rb + fn.referenceSlots;
        long[] p = stack.primitives;
        Object[] r = stack.references;
        int pc = 0;
        while (true) {
            try {
                while (true) {
                    switch (code[pc]) {
                        case NOP -> pc += 1;
                        case CONST_I -> {
                            p[pb + code[pc + 1]] = code[pc + 2];
                            pc += 3;
                        }
                        case CONST_L -> {
                            p[pb + code[pc + 1]] = fn.primitivePool[code[pc + 2]];
                            pc += 3;
                        }
                        case CONST_R -> {
                            r[rb + code[pc + 1]] = fn.referencePool[code[pc + 2]];
                            pc += 3;
                        }
                        case CONST_NULL -> {
                            r[rb + code[pc + 1]] = null;
                            pc += 2;
                        }
                        case MOV_P -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]];
                            pc += 3;
                        }
                        case MOV_R -> {
                            r[rb + code[pc + 1]] = r[rb + code[pc + 2]];
                            pc += 3;
                        }
                        case NEG_I -> {
                            p[pb + code[pc + 1]] = -(int) p[pb + code[pc + 2]];
                            pc += 3;
                        }
                        case NEG_L -> {
                            p[pb + code[pc + 1]] = -p[pb + code[pc + 2]];
                            pc += 3;
                        }
                        case NEG_F -> {
                            p[pb + code[pc + 1]] = fbits(-f(p[pb + code[pc + 2]]));
                            pc += 3;
                        }
                        case NEG_D -> {
                            p[pb + code[pc + 1]] = dbits(-d(p[pb + code[pc + 2]]));
                            pc += 3;
                        }
                        case NOT -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] ^ 1L;
                            pc += 3;
                        }
                        case IS_NULL -> {
                            p[pb + code[pc + 1]] = r[rb + code[pc + 2]] == null ? 1 : 0;
                            pc += 3;
                        }
                        case IS_NOT_NULL -> {
                            p[pb + code[pc + 1]] = r[rb + code[pc + 2]] != null ? 1 : 0;
                            pc += 3;
                        }

                        case ADD_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] + (int) p[pb + code[pc + 3]];
                            pc += 4;
                        }
                        case SUB_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] - (int) p[pb + code[pc + 3]];
                            pc += 4;
                        }
                        case MUL_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] * (int) p[pb + code[pc + 3]];
                            pc += 4;
                        }
                        case DIV_I -> {
                            int divisor = (int) p[pb + code[pc + 3]];
                            if (divisor == 0) {
                                throw divisionByZero();
                            }
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] / divisor;
                            pc += 4;
                        }
                        case REM_I -> {
                            int divisor = (int) p[pb + code[pc + 3]];
                            if (divisor == 0) {
                                throw divisionByZero();
                            }
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] % divisor;
                            pc += 4;
                        }
                        case ADD_L -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] + p[pb + code[pc + 3]];
                            pc += 4;
                        }
                        case SUB_L -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] - p[pb + code[pc + 3]];
                            pc += 4;
                        }
                        case MUL_L -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] * p[pb + code[pc + 3]];
                            pc += 4;
                        }
                        case DIV_L -> {
                            long divisor = p[pb + code[pc + 3]];
                            if (divisor == 0) {
                                throw divisionByZero();
                            }
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] / divisor;
                            pc += 4;
                        }
                        case REM_L -> {
                            long divisor = p[pb + code[pc + 3]];
                            if (divisor == 0) {
                                throw divisionByZero();
                            }
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] % divisor;
                            pc += 4;
                        }
                        case ADD_F, SUB_F, MUL_F, DIV_F, REM_F -> {
                            p[pb + code[pc + 1]] = floatArithmetic(code[pc], p[pb + code[pc + 2]], p[pb + code[pc + 3]]);
                            pc += 4;
                        }
                        case ADD_D -> {
                            p[pb + code[pc + 1]] = dbits(d(p[pb + code[pc + 2]]) + d(p[pb + code[pc + 3]]));
                            pc += 4;
                        }
                        case SUB_D -> {
                            p[pb + code[pc + 1]] = dbits(d(p[pb + code[pc + 2]]) - d(p[pb + code[pc + 3]]));
                            pc += 4;
                        }
                        case MUL_D -> {
                            p[pb + code[pc + 1]] = dbits(d(p[pb + code[pc + 2]]) * d(p[pb + code[pc + 3]]));
                            pc += 4;
                        }
                        case DIV_D -> {
                            p[pb + code[pc + 1]] = dbits(d(p[pb + code[pc + 2]]) / d(p[pb + code[pc + 3]]));
                            pc += 4;
                        }
                        case REM_D -> {
                            p[pb + code[pc + 1]] = dbits(d(p[pb + code[pc + 2]]) % d(p[pb + code[pc + 3]]));
                            pc += 4;
                        }

                        case EQ_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] == (int) p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case NE_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] != (int) p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case LT_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] < (int) p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case LE_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] <= (int) p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case GT_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] > (int) p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case GE_I -> {
                            p[pb + code[pc + 1]] = (int) p[pb + code[pc + 2]] >= (int) p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case EQ_L, EQ_Z -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] == p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case NE_L, NE_Z -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] != p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case LT_L -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] < p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case LE_L -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] <= p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case GT_L -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] > p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case GE_L -> {
                            p[pb + code[pc + 1]] = p[pb + code[pc + 2]] >= p[pb + code[pc + 3]] ? 1 : 0;
                            pc += 4;
                        }
                        case EQ_F, NE_F, LT_F, LE_F, GT_F, GE_F -> {
                            p[pb + code[pc + 1]] = floatCompare(code[pc], p[pb + code[pc + 2]], p[pb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case EQ_D -> {
                            p[pb + code[pc + 1]] = d(p[pb + code[pc + 2]]) == d(p[pb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case NE_D -> {
                            p[pb + code[pc + 1]] = d(p[pb + code[pc + 2]]) != d(p[pb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case LT_D -> {
                            p[pb + code[pc + 1]] = d(p[pb + code[pc + 2]]) < d(p[pb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case LE_D -> {
                            p[pb + code[pc + 1]] = d(p[pb + code[pc + 2]]) <= d(p[pb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case GT_D -> {
                            p[pb + code[pc + 1]] = d(p[pb + code[pc + 2]]) > d(p[pb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case GE_D -> {
                            p[pb + code[pc + 1]] = d(p[pb + code[pc + 2]]) >= d(p[pb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case EQ_R -> {
                            p[pb + code[pc + 1]] = Objects.equals(r[rb + code[pc + 2]], r[rb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case NE_R -> {
                            p[pb + code[pc + 1]] = Objects.equals(r[rb + code[pc + 2]], r[rb + code[pc + 3]]) ? 0 : 1;
                            pc += 4;
                        }

                        case I2L, L2I, I2F, I2D, L2F, L2D, F2I, F2L, F2D, D2I, D2L, D2F -> {
                            p[pb + code[pc + 1]] = numericConversion(code[pc], p[pb + code[pc + 2]]);
                            pc += 3;
                        }
                        case BOX_I, BOX_L, BOX_F, BOX_D, BOX_Z -> {
                            r[rb + code[pc + 1]] = box(code[pc], p[pb + code[pc + 2]]);
                            pc += 3;
                        }
                        case UNBOX_I, UNBOX_L, UNBOX_F, UNBOX_D, UNBOX_Z -> {
                            p[pb + code[pc + 1]] = unbox(code[pc], r[rb + code[pc + 2]]);
                            pc += 3;
                        }
                        case I2S, L2S, F2S, D2S, Z2S, DUR2S, INST2S -> {
                            r[rb + code[pc + 1]] = primitiveText(code[pc], p[pb + code[pc + 2]]);
                            pc += 3;
                        }
                        case R2S -> {
                            r[rb + code[pc + 1]] = Values.toString(r[rb + code[pc + 2]]);
                            pc += 3;
                        }
                        case S2C -> {
                            r[rb + code[pc + 1]] = fn.text.parse((String) r[rb + code[pc + 2]]);
                            pc += 3;
                        }
                        case INSTANCEOF -> {
                            p[pb + code[pc + 1]] = fn.classes[code[pc + 3]].isInstance(r[rb + code[pc + 2]]) ? 1 : 0;
                            pc += 4;
                        }
                        case CHECKCAST, SAFECAST -> {
                            r[rb + code[pc + 1]] = cast(fn, code[pc] == SAFECAST, r[rb + code[pc + 2]], code[pc + 3]);
                            pc += 4;
                        }

                        case CALL_NATIVE_V -> {
                            int count = code[pc + 2];
                            ((NativeFunction.OfVoid) fn.natives[code[pc + 1]])
                                    .call(nativeArguments(stack, guard, code, pc + 3, count, pb, rb));
                            p = stack.primitives;
                            r = stack.references;
                            pc += 3 + count;
                        }
                        case CALL_NATIVE_I -> {
                            int count = code[pc + 3];
                            int result = ((NativeFunction.OfInt) fn.natives[code[pc + 2]])
                                    .call(nativeArguments(stack, guard, code, pc + 4, count, pb, rb));
                            p = stack.primitives;
                            r = stack.references;
                            p[pb + code[pc + 1]] = result;
                            pc += 4 + count;
                        }
                        case CALL_NATIVE_L -> {
                            int count = code[pc + 3];
                            long result = ((NativeFunction.OfLong) fn.natives[code[pc + 2]])
                                    .call(nativeArguments(stack, guard, code, pc + 4, count, pb, rb));
                            p = stack.primitives;
                            r = stack.references;
                            p[pb + code[pc + 1]] = result;
                            pc += 4 + count;
                        }
                        case CALL_NATIVE_F -> {
                            int count = code[pc + 3];
                            float result = ((NativeFunction.OfFloat) fn.natives[code[pc + 2]])
                                    .call(nativeArguments(stack, guard, code, pc + 4, count, pb, rb));
                            p = stack.primitives;
                            r = stack.references;
                            p[pb + code[pc + 1]] = fbits(result);
                            pc += 4 + count;
                        }
                        case CALL_NATIVE_D -> {
                            int count = code[pc + 3];
                            double result = ((NativeFunction.OfDouble) fn.natives[code[pc + 2]])
                                    .call(nativeArguments(stack, guard, code, pc + 4, count, pb, rb));
                            p = stack.primitives;
                            r = stack.references;
                            p[pb + code[pc + 1]] = dbits(result);
                            pc += 4 + count;
                        }
                        case CALL_NATIVE_Z -> {
                            int count = code[pc + 3];
                            boolean result = ((NativeFunction.OfBool) fn.natives[code[pc + 2]])
                                    .call(nativeArguments(stack, guard, code, pc + 4, count, pb, rb));
                            p = stack.primitives;
                            r = stack.references;
                            p[pb + code[pc + 1]] = result ? 1 : 0;
                            pc += 4 + count;
                        }
                        case CALL_NATIVE_R -> {
                            int count = code[pc + 3];
                            Object result = ((NativeFunction.OfRef) fn.natives[code[pc + 2]])
                                    .call(nativeArguments(stack, guard, code, pc + 4, count, pb, rb));
                            p = stack.primitives;
                            r = stack.references;
                            r[rb + code[pc + 1]] = result;
                            pc += 4 + count;
                        }
                        case CALL_V -> {
                            int count = code[pc + 2];
                            invoke(fn, fn.callees[code[pc + 1]], stack, pb, rb, code, pc + 3, count);
                            p = stack.primitives;
                            r = stack.references;
                            pc += 3 + count;
                        }
                        case CALL_P -> {
                            int count = code[pc + 3];
                            invoke(fn, fn.callees[code[pc + 2]], stack, pb, rb, code, pc + 4, count);
                            p = stack.primitives;
                            r = stack.references;
                            p[pb + code[pc + 1]] = stack.returnPrimitive;
                            pc += 4 + count;
                        }
                        case CALL_R -> {
                            int count = code[pc + 3];
                            invoke(fn, fn.callees[code[pc + 2]], stack, pb, rb, code, pc + 4, count);
                            p = stack.primitives;
                            r = stack.references;
                            r[rb + code[pc + 1]] = stack.returnReference;
                            stack.returnReference = null;
                            pc += 4 + count;
                        }

                        case CONCAT -> {
                            r[rb + code[pc + 1]] = concat(r, rb, code, pc + 3, code[pc + 2]);
                            pc += 3 + code[pc + 2];
                        }
                        case TEMPLATE -> {
                            int count = code[pc + 3];
                            r[rb + code[pc + 1]] = fn.templates[code[pc + 2]].render(stack.arguments(code, pc + 4, count, pb, rb));
                            pc += 4 + count;
                        }
                        case NEW_LIST -> {
                            r[rb + code[pc + 1]] = newList(r, rb, code, pc + 3, code[pc + 2]);
                            pc += 3 + code[pc + 2];
                        }
                        case NEW_MAP -> {
                            r[rb + code[pc + 1]] = newMap(r, rb, code, pc + 3, code[pc + 2]);
                            pc += 3 + 2 * code[pc + 2];
                        }
                        case LIST_GET -> {
                            r[rb + code[pc + 1]] = listGet(r[rb + code[pc + 2]], (int) p[pb + code[pc + 3]]);
                            pc += 4;
                        }
                        case LIST_SET -> {
                            listSet(r[rb + code[pc + 1]], (int) p[pb + code[pc + 2]], r[rb + code[pc + 3]]);
                            pc += 4;
                        }
                        case LIST_SIZE -> {
                            p[pb + code[pc + 1]] = ((List<?>) r[rb + code[pc + 2]]).size();
                            pc += 3;
                        }
                        case LIST_ADD -> {
                            listAdd(r[rb + code[pc + 1]], r[rb + code[pc + 2]]);
                            pc += 3;
                        }
                        case LIST_CONTAINS -> {
                            p[pb + code[pc + 1]] = ((List<?>) r[rb + code[pc + 2]]).contains(r[rb + code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }

                        case AND_I, OR_I, XOR_I, SHL_I, SHR_I, USHR_I, AND_L, OR_L, XOR_L, SHL_L, SHR_L, USHR_L -> {
                            p[pb + code[pc + 1]] = bitwise(code[pc], p[pb + code[pc + 2]], p[pb + code[pc + 3]]);
                            pc += 4;
                        }
                        case NEW_CLOSURE -> {
                            r[rb + code[pc + 1]] = newClosure(fn, p, r, pb, rb, code, pc);
                            pc += 4 + code[pc + 3];
                        }
                        case CALL_CLOSURE_V -> {
                            int count = code[pc + 2];
                            invokeClosure(fn, r[rb + code[pc + 1]], stack, pb, rb, code, pc + 3, count);
                            p = stack.primitives;
                            r = stack.references;
                            pc += 3 + count;
                        }
                        case CALL_CLOSURE_P -> {
                            int count = code[pc + 3];
                            invokeClosure(fn, r[rb + code[pc + 2]], stack, pb, rb, code, pc + 4, count);
                            p = stack.primitives;
                            r = stack.references;
                            p[pb + code[pc + 1]] = stack.returnPrimitive;
                            pc += 4 + count;
                        }
                        case CALL_CLOSURE_R -> {
                            int count = code[pc + 3];
                            invokeClosure(fn, r[rb + code[pc + 2]], stack, pb, rb, code, pc + 4, count);
                            p = stack.primitives;
                            r = stack.references;
                            r[rb + code[pc + 1]] = stack.returnReference;
                            stack.returnReference = null;
                            pc += 4 + count;
                        }
                        case NEW_RECORD -> {
                            r[rb + code[pc + 1]] = newRecord(fn, r, rb, code, pc);
                            pc += 4 + code[pc + 3];
                        }
                        case RECORD_GET -> {
                            r[rb + code[pc + 1]] = ((RecordValue) r[rb + code[pc + 2]]).get(code[pc + 3]);
                            pc += 4;
                        }
                        case RECORD_TEST -> {
                            p[pb + code[pc + 1]] = isRecord(r[rb + code[pc + 2]], fn.records[code[pc + 3]]) ? 1 : 0;
                            pc += 4;
                        }
                        case RECORD_CAST, SAFE_RECORD_CAST -> {
                            r[rb + code[pc + 1]] = recordCast(fn.records[code[pc + 3]], code[pc] == SAFE_RECORD_CAST,
                                    r[rb + code[pc + 2]]);
                            pc += 4;
                        }
                        case GLOBAL_GET_P -> {
                            p[pb + code[pc + 1]] = fn.globals[code[pc + 2]].getPrimitive();
                            pc += 3;
                        }
                        case GLOBAL_GET_R -> {
                            r[rb + code[pc + 1]] = fn.globals[code[pc + 2]].getReference();
                            pc += 3;
                        }
                        case GLOBAL_SET_P -> {
                            fn.globals[code[pc + 1]].setPrimitive(p[pb + code[pc + 2]]);
                            pc += 3;
                        }
                        case GLOBAL_SET_R -> {
                            fn.globals[code[pc + 1]].setReference(r[rb + code[pc + 2]]);
                            pc += 3;
                        }
                        case GLOBAL_ADD -> {
                            fn.globals[code[pc + 1]].add(p[pb + code[pc + 2]]);
                            pc += 3;
                        }
                        case GLOBAL_RESTORE, PDATA_GET, PDATA_SET, PDATA_ADD -> {
                            storage(fn, stack, pb, rb, code, pc);
                            p = stack.primitives;
                            r = stack.references;
                            pc += Opcodes.length(code, pc);
                        }
                        case THROW -> throw thrown(r[rb + code[pc + 1]]);
                        case JMP -> pc = code[pc + 1];
                        case LOOP -> {
                            if (--stack.loopBudget <= 0) {
                                loopCheck(stack, guard);
                            }
                            pc = code[pc + 1];
                        }
                        case BR_T -> pc = p[pb + code[pc + 1]] != 0 ? code[pc + 2] : pc + 3;
                        case BR_F -> pc = p[pb + code[pc + 1]] == 0 ? code[pc + 2] : pc + 3;
                        case RET_V -> {
                            leave(stack, fn, pb, rb);
                            return;
                        }
                        case RET_P -> {
                            stack.returnPrimitive = p[pb + code[pc + 1]];
                            leave(stack, fn, pb, rb);
                            return;
                        }
                        case RET_R -> {
                            stack.returnReference = r[rb + code[pc + 1]];
                            leave(stack, fn, pb, rb);
                            return;
                        }
                        default -> throw new ScriptRuntimeException(ScriptRuntimeException.Kind.INTERNAL,
                                "Invalid instruction " + Opcodes.name(code[pc]) + " (this is a TachyonScript bug)", null);
                    }
                }
            } catch (ScriptRuntimeException error) {
                pc = recover(fn, stack, depth, pb, rb, pc, error);
            } catch (RuntimeException | StackOverflowError | LinkageError error) {
                pc = recover(fn, stack, depth, pb, rb, pc, translate(error, fn, pc));
            }
            p = stack.primitives;
            r = stack.references;
        }
    }

    /**
     * Function entry, when the call used up the watchdog budget or enters another script. A call
     * counts against the loop budget like a back edge, so recursion that never loops
     * ({@code f(n - 1) + f(n - 1)}) still times out. A revoked script cannot start an execution
     * or be entered from another script; within one script the next native or loop check stops it.
     */
    private static void enter(ExecutionStack stack, ExecutionGuard guard) {
        if (stack.loopBudget <= 0) {
            stack.checkDeadline();
        }
        checkRevoked(guard);
        stack.verified = guard;
    }

    /** A loop back edge whose budget ran out: the watchdog's clock check, then revocation. */
    static void loopCheck(ExecutionStack stack, ExecutionGuard guard) {
        stack.checkDeadline();
        checkRevoked(guard);
    }

    /**
     * The argument view of a native call. Natives are how a script acts on the server, so a
     * revoked script reaches none of them, even in straight-line code after its revocation.
     */
    static CallArguments nativeArguments(ExecutionStack stack, ExecutionGuard guard, int[] code, int position,
                                         int count, int pb, int rb) {
        checkRevoked(guard);
        return stack.arguments(code, position, count, pb, rb);
    }

    /** Shared with generated code: identical guard failures and script error recovery. */
    static void checkRevoked(ExecutionGuard guard) {
        if (guard != null && guard.securityRevoked()) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.SECURITY_REVOKED,
                    "Script execution revoked by the security controller.", null);
        }
    }

    static int bytecodeFailure(CompiledFunction fn, ExecutionStack stack, int depth, int pb, int rb, int pc,
                               Throwable error) {
        return recover(fn, stack, depth, pb, rb, pc,
                error instanceof ScriptRuntimeException script ? script : translate(error, fn, pc));
    }

    /**
     * Handles an error raised at {@code pc}: continues at the covering handler (returning its
     * pc) or adds this frame to the error and rethrows it.
     */
    private static int recover(CompiledFunction fn, ExecutionStack stack, int depth, int pb, int rb, int pc,
                               ScriptRuntimeException error) {
        int[] handlers = fn.handlers;
        if (handlers.length > 0 && error.kind().isCatchable()) {
            for (int i = 0; i < handlers.length; i += 4) {
                if (pc >= handlers[i] && pc < handlers[i + 1]) {
                    // Frames above this one were abandoned by the error: clear their references.
                    int referenceEnd = rb + fn.referenceSlots;
                    stack.clearReferences(referenceEnd, stack.referenceHighWater);
                    stack.referenceHighWater = referenceEnd;
                    stack.depth = depth;
                    stack.primitiveTop = pb + fn.primitiveSlots;
                    stack.referenceTop = referenceEnd;
                    stack.returnReference = null;
                    String location = error.location();
                    if (location.isEmpty()) {
                        ScriptFrame here = frame(fn, pc);
                        location = here.path() + ":" + here.line();
                    }
                    String message = Objects.requireNonNullElse(error.getMessage(), error.kind().scriptName());
                    stack.references[rb + handlers[i + 3]] = new ScriptFailure(message, error.kind().scriptName(),
                            location, error);
                    return handlers[i + 2];
                }
            }
        }
        throw error.addFrame(frame(fn, pc));
    }

    /** The error raised by {@code throw value}: a new one for a message, the original for a caught error. */
    static ScriptRuntimeException thrown(Object value) {
        if (value instanceof ScriptFailure failure && failure.exception() instanceof ScriptRuntimeException original) {
            return original.copy();
        }
        return new ScriptRuntimeException(ScriptRuntimeException.Kind.THROWN, Values.toString(value), null);
    }

    // =================================================================== calls and frames

    static void invoke(CompiledFunction caller, CompiledFunction callee, ExecutionStack stack, int pb, int rb,
                               int[] code, int position, int count) {
        int calleePrimitives = pb + caller.primitiveSlots;
        int calleeReferences = rb + caller.referenceSlots;
        stack.ensure(calleePrimitives + callee.primitiveSlots, calleeReferences + callee.referenceSlots);
        long[] p = stack.primitives;
        Object[] r = stack.references;
        int[] slots = callee.parameterSlots;
        boolean[] isReference = callee.parameterIsReference;
        for (int i = 0; i < count; i++) {
            int from = code[position + i];
            if (isReference[i]) {
                r[calleeReferences + slots[i]] = r[rb + from];
            } else {
                p[calleePrimitives + slots[i]] = p[pb + from];
            }
        }
        if (++stack.depth > stack.maxDepth) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.RECURSION,
                    "Too many nested function calls (limit " + stack.maxDepth + "). Is a function calling itself forever?",
                    null);
        }
        execute(callee, stack, calleePrimitives, calleeReferences);
        stack.depth--;
    }

    /** Calls a function value; its captured values fill the first parameters, the arguments the rest. */
    static void invokeClosure(CompiledFunction caller, Object value, ExecutionStack stack, int pb, int rb,
                                      int[] code, int position, int count) {
        if (value == null) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.NULL, "Cannot call a null function value.", null);
        }
        Closure closure = (Closure) value;
        CompiledFunction callee = closure.function;
        int calleePrimitives = pb + caller.primitiveSlots;
        int calleeReferences = rb + caller.referenceSlots;
        stack.ensure(calleePrimitives + callee.primitiveSlots, calleeReferences + callee.referenceSlots);
        long[] p = stack.primitives;
        Object[] r = stack.references;
        int[] slots = callee.parameterSlots;
        boolean[] isReference = callee.parameterIsReference;
        int captured = closure.captureCount;
        int primitiveIndex = 0;
        int referenceIndex = 0;
        for (int i = 0; i < captured; i++) {
            if (isReference[i]) {
                r[calleeReferences + slots[i]] = closure.references[referenceIndex++];
            } else {
                p[calleePrimitives + slots[i]] = closure.primitives[primitiveIndex++];
            }
        }
        for (int j = 0; j < count; j++) {
            int i = captured + j;
            int from = code[position + j];
            if (isReference[i]) {
                r[calleeReferences + slots[i]] = r[rb + from];
            } else {
                p[calleePrimitives + slots[i]] = p[pb + from];
            }
        }
        if (++stack.depth > stack.maxDepth) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.RECURSION,
                    "Too many nested function calls (limit " + stack.maxDepth + "). Is a function calling itself forever?",
                    null);
        }
        execute(callee, stack, calleePrimitives, calleeReferences);
        stack.depth--;
    }

    /** {@code NEW_CLOSURE Rd kf n captures...}: copies the captured registers into a new function value. */
    static Closure newClosure(CompiledFunction fn, long[] p, Object[] r, int pb, int rb, int[] code, int pc) {
        CompiledFunction callee = fn.callees[code[pc + 2]];
        int count = code[pc + 3];
        if (count == 0) {
            return Closure.of(callee);
        }
        int primitiveCount = 0;
        for (int i = 0; i < count; i++) {
            if (!callee.parameterIsReference[i]) {
                primitiveCount++;
            }
        }
        long[] primitives = new long[primitiveCount];
        Object[] references = new Object[count - primitiveCount];
        int primitiveIndex = 0;
        int referenceIndex = 0;
        for (int i = 0; i < count; i++) {
            int from = code[pc + 4 + i];
            if (callee.parameterIsReference[i]) {
                references[referenceIndex++] = r[rb + from];
            } else {
                primitives[primitiveIndex++] = p[pb + from];
            }
        }
        return new Closure(callee, count, primitives, references);
    }

    /** Top-level variable restores and player data, which may call back into scripts (initial values). */
    static void storage(CompiledFunction fn, ExecutionStack stack, int pb, int rb, int[] code, int pc) {
        switch (code[pc]) {
            case GLOBAL_RESTORE -> {
                boolean restored = fn.globals[code[pc + 2]].restore();
                stack.primitives[pb + code[pc + 1]] = restored ? 1 : 0;
            }
            case PDATA_GET -> {
                Object value = fn.playerData[code[pc + 3]].get(stack.references[rb + code[pc + 2]]);
                stack.references[rb + code[pc + 1]] = value;
            }
            case PDATA_SET -> fn.playerData[code[pc + 3]].set(stack.references[rb + code[pc + 1]],
                    stack.references[rb + code[pc + 2]]);
            default -> {
                Object owner = stack.references[rb + code[pc + 1]];
                long delta = stack.primitives[pb + code[pc + 2]];
                fn.playerData[code[pc + 3]].add(owner, delta);
            }
        }
    }

    /** Leaves a frame normally: clears its reference slots and pops it. */
    static void leave(ExecutionStack stack, CompiledFunction fn, int pb, int rb) {
        Object[] r = stack.references;
        for (int i = rb, end = rb + fn.referenceSlots; i < end; i++) {
            r[i] = null;
        }
        stack.primitiveTop = pb;
        stack.referenceTop = rb;
    }

    // =================================================================== helpers

    private static double d(long bits) {
        return Double.longBitsToDouble(bits);
    }

    private static long dbits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static float f(long bits) {
        return Float.intBitsToFloat((int) bits);
    }

    private static long fbits(float value) {
        return Float.floatToRawIntBits(value);
    }

    private static long bitwise(int opcode, long left, long right) {
        int a = (int) left;
        int b = (int) right;
        return switch (opcode) {
            case AND_I -> a & b;
            case OR_I -> a | b;
            case XOR_I -> a ^ b;
            case SHL_I -> a << b;
            case SHR_I -> a >> b;
            case USHR_I -> a >>> b;
            case AND_L -> left & right;
            case OR_L -> left | right;
            case XOR_L -> left ^ right;
            case SHL_L -> left << right;
            case SHR_L -> left >> right;
            default -> left >>> right;
        };
    }

    private static long floatArithmetic(int opcode, long left, long right) {
        float a = f(left);
        float b = f(right);
        return fbits(switch (opcode) {
            case ADD_F -> a + b;
            case SUB_F -> a - b;
            case MUL_F -> a * b;
            case DIV_F -> a / b;
            default -> a % b;
        });
    }

    private static boolean floatCompare(int opcode, long left, long right) {
        float a = f(left);
        float b = f(right);
        return switch (opcode) {
            case EQ_F -> a == b;
            case NE_F -> a != b;
            case LT_F -> a < b;
            case LE_F -> a <= b;
            case GT_F -> a > b;
            default -> a >= b;
        };
    }

    static long numericConversion(int opcode, long value) {
        return switch (opcode) {
            case I2L -> (int) value;
            case L2I -> (int) value;
            case I2F -> fbits((float) (int) value);
            case I2D -> dbits((int) value);
            case L2F -> fbits((float) value);
            case L2D -> dbits((double) value);
            case F2I -> (int) f(value);
            case F2L -> (long) f(value);
            case F2D -> dbits(f(value));
            case D2I -> (int) d(value);
            case D2L -> (long) d(value);
            default -> fbits((float) d(value));
        };
    }

    static Object box(int opcode, long value) {
        return switch (opcode) {
            case BOX_I -> (int) value;
            case BOX_L -> value;
            case BOX_F -> f(value);
            case BOX_D -> d(value);
            default -> value != 0;
        };
    }

    static long unbox(int opcode, Object value) {
        if (value == null) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.NULL, "Unexpected null value.", null);
        }
        return switch (opcode) {
            case UNBOX_I -> (Integer) value;
            case UNBOX_L -> (Long) value;
            case UNBOX_F -> fbits((Float) value);
            case UNBOX_D -> dbits((Double) value);
            default -> (Boolean) value ? 1 : 0;
        };
    }

    static String primitiveText(int opcode, long value) {
        return switch (opcode) {
            case I2S -> Values.toString((int) value);
            case L2S -> Values.toString(value);
            case F2S -> Values.toString(f(value));
            case D2S -> Values.toString(d(value));
            case Z2S -> Values.toString(value != 0);
            case INST2S -> Values.instantToString(value);
            default -> Values.durationToString(value);
        };
    }

    static Object cast(CompiledFunction fn, boolean safe, Object value, int classIndex) {
        Class<?> type = fn.classes[classIndex];
        if (type.isInstance(value)) {
            return value;
        }
        if (safe) {
            return null;
        }
        String target = fn.unit.classes().get(classIndex).name();
        throw new ScriptRuntimeException(ScriptRuntimeException.Kind.CAST, value == null
                ? "Cannot cast null to " + target + "."
                : "Cannot cast this value to " + target + ".", null);
    }

    static String concat(Object[] r, int rb, int[] code, int position, int count) {
        if (count == 2) {
            return ((String) r[rb + code[position]]).concat((String) r[rb + code[position + 1]]);
        }
        int length = 0;
        for (int i = 0; i < count; i++) {
            length += ((String) r[rb + code[position + i]]).length();
        }
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < count; i++) {
            builder.append((String) r[rb + code[position + i]]);
        }
        return builder.toString();
    }

    static ScriptList newList(Object[] r, int rb, int[] code, int position, int count) {
        ScriptList list = new ScriptList(Math.max(count, 4));
        for (int i = 0; i < count; i++) {
            list.add(r[rb + code[position + i]]);
        }
        return list;
    }

    static ScriptMap newMap(Object[] r, int rb, int[] code, int position, int count) {
        ScriptMap map = new ScriptMap(Math.max(count, 4));
        for (int i = 0; i < count; i++) {
            map.put(r[rb + code[position + 2 * i]], r[rb + code[position + 2 * i + 1]]);
        }
        return map;
    }

    /** {@code NEW_RECORD Rd kr n fields...} */
    static RecordValue newRecord(CompiledFunction fn, Object[] r, int rb, int[] code, int pc) {
        int count = code[pc + 3];
        Object[] fields = new Object[count];
        for (int i = 0; i < count; i++) {
            fields[i] = r[rb + code[pc + 4 + i]];
        }
        return new RecordValue(fn.records[code[pc + 2]], fields);
    }

    /** Record values keep their type across reloads of the declaring script, so types compare by key. */
    static boolean isRecord(Object value, RecordType type) {
        return value instanceof RecordValue record && (record.type() == type || record.type().key().equals(type.key()));
    }

    static Object recordCast(RecordType type, boolean safe, Object value) {
        if (isRecord(value, type)) {
            return value;
        }
        if (safe) {
            return null;
        }
        throw new ScriptRuntimeException(ScriptRuntimeException.Kind.CAST, value == null
                ? "Cannot cast null to " + type.name() + "."
                : "Cannot cast this value to " + type.name() + ".", null);
    }

    static Object listGet(Object target, int index) {
        List<?> list = (List<?>) target;
        if (index < 0 || index >= list.size()) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.INDEX,
                    "List index " + index + " is out of bounds (size " + list.size() + ").", null);
        }
        return list.get(index);
    }

    @SuppressWarnings("unchecked")
    static void listSet(Object target, int index, Object value) {
        List<Object> list = (List<Object>) target;
        if (index < 0 || index >= list.size()) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.INDEX,
                    "List index " + index + " is out of bounds (size " + list.size() + ").", null);
        }
        try {
            list.set(index, value);
        } catch (UnsupportedOperationException readOnly) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.SCRIPT, "This list cannot be modified.", readOnly);
        }
    }

    @SuppressWarnings("unchecked")
    static void listAdd(Object target, Object value) {
        try {
            ((List<Object>) target).add(value);
        } catch (UnsupportedOperationException readOnly) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.SCRIPT, "This list cannot be modified.", readOnly);
        }
    }

    // =================================================================== errors

    static ScriptRuntimeException divisionByZero() {
        return new ScriptRuntimeException(ScriptRuntimeException.Kind.DIVISION_BY_ZERO, "Division by zero.", null);
    }

    static ScriptRuntimeException timeout(long limitNanos) {
        return new ScriptRuntimeException(ScriptRuntimeException.Kind.TIMEOUT,
                "Script ran for more than " + (limitNanos / 1_000_000) + " ms and was stopped. Is a loop running forever?",
                null);
    }

    private static ScriptRuntimeException translate(Throwable error, CompiledFunction fn, int pc) {
        if (error instanceof ScriptError scriptError) {
            return new ScriptRuntimeException(ScriptRuntimeException.Kind.SCRIPT, scriptError.getMessage(), scriptError);
        }
        if (error instanceof StackOverflowError) {
            return new ScriptRuntimeException(ScriptRuntimeException.Kind.RECURSION,
                    "Stack overflow: calls are nested too deeply.", error);
        }
        String nativeKey = nativeAt(fn, pc);
        if (nativeKey != null) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return new ScriptRuntimeException(ScriptRuntimeException.Kind.NATIVE, nativeKey + " failed: " + message, error);
        }
        if (error instanceof ArithmeticException) {
            return new ScriptRuntimeException(ScriptRuntimeException.Kind.DIVISION_BY_ZERO, "Division by zero.", error);
        }
        return new ScriptRuntimeException(ScriptRuntimeException.Kind.INTERNAL,
                "Internal runtime error (" + error + "). This is a TachyonScript bug.", error);
    }

    private static String nativeAt(CompiledFunction fn, int pc) {
        int op = pc >= 0 && pc < fn.code.length ? fn.code[pc] : -1;
        if (op == CALL_NATIVE_V) {
            return fn.unit.natives().get(fn.code[pc + 1]).key();
        }
        if (op >= CALL_NATIVE_I && op <= CALL_NATIVE_R) {
            return fn.unit.natives().get(fn.code[pc + 2]).key();
        }
        return null;
    }

    private static ScriptFrame frame(CompiledFunction fn, int pc) {
        long span = fn.unit.spanAt(pc);
        SourceText source = fn.source;
        if (Spans.isNone(span)) {
            return new ScriptFrame(fn.displayName, source.path(), 0, 0, "", 0);
        }
        int start = Spans.start(span);
        int line = source.line(start);
        int column = source.column(start);
        int length = Math.max(1, Spans.end(span) - start);
        return new ScriptFrame(fn.displayName, source.path(), line, column, source.lineText(line), length);
    }
}
