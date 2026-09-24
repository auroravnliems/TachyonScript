package dev.tachyonscript.runtime.interpreter;

import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.ir.Spans;
import dev.tachyonscript.runtime.code.Opcodes;
import dev.tachyonscript.runtime.error.ScriptFrame;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;

import java.util.ArrayList;
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
 * <p>Checks are placed where they are cheap: loop back edges decrement a counter and only
 * read the clock when it runs out; script calls check the depth counter. Any exception is
 * converted into a {@link ScriptRuntimeException} carrying a TachyonScript stack trace.
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
        if (savedDepth == 0) {
            stack.startExecution();
            stack.referenceHighWater = referenceBase + function.referenceSlots;
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
    }

    private static long toSlot(Object value) {
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
        final int[] code = fn.code;
        stack.primitiveTop = pb + fn.primitiveSlots;
        stack.referenceTop = rb + fn.referenceSlots;
        long[] p = stack.primitives;
        Object[] r = stack.references;
        int pc = 0;
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
                    case I2S, L2S, F2S, D2S, Z2S, DUR2S -> {
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
                                .call(stack.arguments(code, pc + 3, count, pb, rb));
                        p = stack.primitives;
                        r = stack.references;
                        pc += 3 + count;
                    }
                    case CALL_NATIVE_I -> {
                        int count = code[pc + 3];
                        int result = ((NativeFunction.OfInt) fn.natives[code[pc + 2]])
                                .call(stack.arguments(code, pc + 4, count, pb, rb));
                        p = stack.primitives;
                        r = stack.references;
                        p[pb + code[pc + 1]] = result;
                        pc += 4 + count;
                    }
                    case CALL_NATIVE_L -> {
                        int count = code[pc + 3];
                        long result = ((NativeFunction.OfLong) fn.natives[code[pc + 2]])
                                .call(stack.arguments(code, pc + 4, count, pb, rb));
                        p = stack.primitives;
                        r = stack.references;
                        p[pb + code[pc + 1]] = result;
                        pc += 4 + count;
                    }
                    case CALL_NATIVE_F -> {
                        int count = code[pc + 3];
                        float result = ((NativeFunction.OfFloat) fn.natives[code[pc + 2]])
                                .call(stack.arguments(code, pc + 4, count, pb, rb));
                        p = stack.primitives;
                        r = stack.references;
                        p[pb + code[pc + 1]] = fbits(result);
                        pc += 4 + count;
                    }
                    case CALL_NATIVE_D -> {
                        int count = code[pc + 3];
                        double result = ((NativeFunction.OfDouble) fn.natives[code[pc + 2]])
                                .call(stack.arguments(code, pc + 4, count, pb, rb));
                        p = stack.primitives;
                        r = stack.references;
                        p[pb + code[pc + 1]] = dbits(result);
                        pc += 4 + count;
                    }
                    case CALL_NATIVE_Z -> {
                        int count = code[pc + 3];
                        boolean result = ((NativeFunction.OfBool) fn.natives[code[pc + 2]])
                                .call(stack.arguments(code, pc + 4, count, pb, rb));
                        p = stack.primitives;
                        r = stack.references;
                        p[pb + code[pc + 1]] = result ? 1 : 0;
                        pc += 4 + count;
                    }
                    case CALL_NATIVE_R -> {
                        int count = code[pc + 3];
                        Object result = ((NativeFunction.OfRef) fn.natives[code[pc + 2]])
                                .call(stack.arguments(code, pc + 4, count, pb, rb));
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

                    case JMP -> pc = code[pc + 1];
                    case LOOP -> {
                        if (--stack.loopBudget <= 0) {
                            stack.checkDeadline();
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
            throw error.addFrame(frame(fn, pc));
        } catch (RuntimeException | StackOverflowError | LinkageError error) {
            throw translate(error, fn, pc).addFrame(frame(fn, pc));
        }
    }

    // =================================================================== calls and frames

    private static void invoke(CompiledFunction caller, CompiledFunction callee, ExecutionStack stack, int pb, int rb,
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

    /** Leaves a frame normally: clears its reference slots and pops it. */
    private static void leave(ExecutionStack stack, CompiledFunction fn, int pb, int rb) {
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

    private static long numericConversion(int opcode, long value) {
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

    private static Object box(int opcode, long value) {
        return switch (opcode) {
            case BOX_I -> (int) value;
            case BOX_L -> value;
            case BOX_F -> f(value);
            case BOX_D -> d(value);
            default -> value != 0;
        };
    }

    private static long unbox(int opcode, Object value) {
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

    private static String primitiveText(int opcode, long value) {
        return switch (opcode) {
            case I2S -> Values.toString((int) value);
            case L2S -> Values.toString(value);
            case F2S -> Values.toString(f(value));
            case D2S -> Values.toString(d(value));
            case Z2S -> Values.toString(value != 0);
            default -> Values.durationToString(value);
        };
    }

    private static Object cast(CompiledFunction fn, boolean safe, Object value, int classIndex) {
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

    private static String concat(Object[] r, int rb, int[] code, int position, int count) {
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

    private static List<Object> newList(Object[] r, int rb, int[] code, int position, int count) {
        List<Object> list = new ArrayList<>(Math.max(count, 4));
        for (int i = 0; i < count; i++) {
            list.add(r[rb + code[position + i]]);
        }
        return list;
    }

    private static Object listGet(Object target, int index) {
        List<?> list = (List<?>) target;
        if (index < 0 || index >= list.size()) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.INDEX,
                    "List index " + index + " is out of bounds (size " + list.size() + ").", null);
        }
        return list.get(index);
    }

    @SuppressWarnings("unchecked")
    private static void listSet(Object target, int index, Object value) {
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
    private static void listAdd(Object target, Object value) {
        try {
            ((List<Object>) target).add(value);
        } catch (UnsupportedOperationException readOnly) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.SCRIPT, "This list cannot be modified.", readOnly);
        }
    }

    // =================================================================== errors

    private static ScriptRuntimeException divisionByZero() {
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
