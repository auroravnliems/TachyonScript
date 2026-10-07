package dev.tachyonscript.runtime.bytecode;

import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.ir.Spans;
import dev.tachyonscript.runtime.code.CodeUnit;
import dev.tachyonscript.runtime.interpreter.BytecodeBody;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.ExecutionGuard;
import dev.tachyonscript.runtime.interpreter.ExecutionStack;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static dev.tachyonscript.runtime.code.Opcodes.*;

/**
 * Emits JVM operations and branches for the assembler's verified, resolved slot layout.
 * There is no instruction dispatch loop in the generated method. Complex language values
 * share the interpreter's semantic helpers; calls still use the same frame and native ABI.
 */
public final class BytecodeCompiler {
    private static final String FN = Type.getInternalName(CompiledFunction.class);
    private static final String STACK = Type.getInternalName(ExecutionStack.class);
    private static final String GUARD = Type.getInternalName(ExecutionGuard.class);
    private static final String SUPPORT = Type.getInternalName(Interpreter.class);
    private static final String BODY = Type.getInternalName(BytecodeBody.class);
    private static final String F = "L" + FN + ";";
    private static final String S = "L" + STACK + ";";
    private static final String O = "Ljava/lang/Object;";
    private static final String R = "[Ljava/lang/Object;";
    private static final String VALUES = "dev/tachyonscript/runtime/value/";
    private static final String FAILURE = "dev/tachyonscript/runtime/error/ScriptRuntimeException";
    // execute(this, function, stack, primitiveBase, referenceBase), then stable scratch locals.
    private static final int P = 5, REFS = 6, G = 7, DEPTH = 8, PC = 9, ERROR = 10, TEMP = 11;
    private final CodeUnit unit;
    private final SourceText source;
    private final int[] code;
    private final Map<Integer, Label> labels = new TreeMap<>();
    private MethodVisitor mv;

    private BytecodeCompiler(CodeUnit unit, SourceText source) {
        this.unit = unit;
        this.source = source;
        this.code = unit.code();
    }

    /**
     * HotSpot never JIT-compiles a method with more bytecode than this ({@code -XX:-DontCompileHugeMethods}
     * is off by default). Such a body would run in the JVM's own interpreter, slower than the
     * script interpreter, which is itself compiled; the linker keeps those functions interpreted.
     */
    public static final int JIT_LIMIT = 8000;

    /** Deterministic class bytes for inspection/tests; class definition happens only when linking bytecode. */
    public static byte[] generate(CodeUnit unit, SourceText source) {
        return new BytecodeCompiler(unit, source).generate();
    }

    /** Bytecode length of the generated {@code execute} method, read from the class file. */
    public static int methodSize(byte[] classBytes) {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(classBytes);
        in.position(8);
        int count = in.getShort() & 0xFFFF;
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = in.get() & 0xFF;
            switch (tag) {
                case 1 -> {
                    byte[] text = new byte[in.getShort() & 0xFFFF];
                    in.get(text);
                    utf8[i] = new String(text, StandardCharsets.UTF_8);
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.getInt();
                case 5, 6 -> {
                    in.getLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> in.getShort();
                case 15 -> {
                    in.get();
                    in.getShort();
                }
                default -> throw new IllegalArgumentException("Unknown constant pool tag " + tag);
            }
        }
        in.position(in.position() + 6);
        int interfaces = in.getShort() & 0xFFFF;
        in.position(in.position() + 2 * interfaces);
        int fields = in.getShort() & 0xFFFF;
        for (int f = 0; f < fields; f++) {
            in.position(in.position() + 6);
            int attributes = in.getShort() & 0xFFFF;
            for (int a = 0; a < attributes; a++) {
                in.getShort();
                int length = in.getInt();
                in.position(in.position() + length);
            }
        }
        int methods = in.getShort() & 0xFFFF;
        for (int m = 0; m < methods; m++) {
            in.getShort();
            String name = utf8[in.getShort() & 0xFFFF];
            in.getShort();
            int attributes = in.getShort() & 0xFFFF;
            for (int a = 0; a < attributes; a++) {
                String attribute = utf8[in.getShort() & 0xFFFF];
                int length = in.getInt();
                if (attribute.equals("Code") && name.equals("execute")) {
                    in.getShort();
                    in.getShort();
                    return in.getInt();
                }
                in.position(in.position() + length);
            }
        }
        throw new IllegalArgumentException("No execute method in generated class");
    }

    private byte[] generate() {
        for (int pc = 0; pc < code.length; pc += dev.tachyonscript.runtime.code.Opcodes.length(code, pc)) {
            labels.put(pc, new Label());
        }
        if (labels.isEmpty()) throw new IllegalArgumentException("Empty function " + unit.key());
        // Hidden classes are named after their script and function for profilers and heap dumps.
        String name = FN.substring(0, FN.lastIndexOf('/') + 1) + "Tys_" + readable(source.path()) + "_"
                + readable(unit.key()) + "_" + identity(source.path() + "|" + unit.key());
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", new String[] {BODY});
        writer.visitSource(source.path(), null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "execute", "(" + F + S + "II)V", null, null);
        mv.visitCode();
        Label start = new Label(), end = new Label(), failed = new Label();
        // Match the reference runtime's catch boundary exactly: VM errors such as OOM propagate.
        for (String caught : new String[] {"java/lang/RuntimeException", "java/lang/StackOverflowError", "java/lang/LinkageError"}) {
            mv.visitTryCatchBlock(start, end, failed, caught);
        }
        load(2); integer(3); push(unit.primitiveSlots()); op(Opcodes.IADD); put(STACK, "primitiveTop", "I");
        load(2); integer(4); push(unit.referenceSlots()); op(Opcodes.IADD); put(STACK, "referenceTop", "I");
        refreshArrays();
        load(2); get(STACK, "depth", "I"); mv.visitVarInsn(Opcodes.ISTORE, DEPTH);
        // Entry (watchdog + revocation) was checked by Interpreter.execute before entering this body.
        load(1); get(FN, "guard", "L" + GUARD + ";"); mv.visitVarInsn(Opcodes.ASTORE, G);
        push(0); mv.visitVarInsn(Opcodes.ISTORE, PC);
        mv.visitLabel(start);
        for (var entry : labels.entrySet()) {
            int pc = entry.getKey();
            mv.visitLabel(entry.getValue());
            long span = unit.spanAt(pc);
            if (!Spans.isNone(span)) mv.visitLineNumber(Math.min(65535, source.line(Spans.start(span))), entry.getValue());
            push(pc); mv.visitVarInsn(Opcodes.ISTORE, PC);
            instruction(pc);
        }
        invalid("Fell through JVM body for " + unit.key());
        mv.visitLabel(end);
        mv.visitLabel(failed);
        mv.visitVarInsn(Opcodes.ASTORE, ERROR);
        load(1); load(2); integer(DEPTH); integer(3); integer(4); integer(PC); load(ERROR);
        support("bytecodeFailure", "(" + F + S + "IIIILjava/lang/Throwable;)I");
        mv.visitVarInsn(Opcodes.ISTORE, PC);
        refreshArrays();
        int[] targets = new int[unit.handlers().length / 4];
        for (int i = 0; i < targets.length; i++) targets[i] = unit.handlers()[i * 4 + 2];
        targets = Arrays.stream(targets).distinct().sorted().toArray();
        Label invalidHandler = new Label();
        integer(PC);
        mv.visitLookupSwitchInsn(invalidHandler, targets, Arrays.stream(targets).mapToObj(this::label).toArray(Label[]::new));
        mv.visitLabel(invalidHandler);
        invalid("Invalid JVM exception handler for " + unit.key());
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void instruction(int pc) {
        int op = code[pc];
        if (op >= ADD_I && op <= REM_D) { arithmetic(pc); return; }
        if (op >= EQ_I && op <= NE_R) { compare(pc); return; }
        if (op >= I2L && op <= D2F) {
            pStore(code[pc + 1], () -> { push(op); pLoad(code[pc + 2]); support("numericConversion", "(IJ)J"); });
            return;
        }
        if (op >= BOX_I && op <= BOX_Z) {
            rStore(code[pc + 1], () -> { push(op); pLoad(code[pc + 2]); support("box", "(IJ)" + O); });
            return;
        }
        if (op >= UNBOX_I && op <= UNBOX_Z) {
            pStore(code[pc + 1], () -> { push(op); rLoad(code[pc + 2]); support("unbox", "(I" + O + ")J"); });
            return;
        }
        if (op >= I2S && op <= DUR2S || op == INST2S) {
            rStore(code[pc + 1], () -> { push(op); pLoad(code[pc + 2]); support("primitiveText", "(IJ)Ljava/lang/String;"); });
            return;
        }
        if (op >= CALL_NATIVE_V && op <= CALL_NATIVE_R) { nativeCall(pc); return; }
        if (op >= AND_I && op <= USHR_L) { bitwise(pc); return; }
        switch (op) {
            case NOP -> { }
            case CONST_I -> pStore(code[pc + 1], () -> pushLong(code[pc + 2]));
            case CONST_L -> pStore(code[pc + 1], () -> pushLong(unit.primitivePool()[code[pc + 2]]));
            case CONST_R -> rStore(code[pc + 1], () -> pool("referencePool", R, code[pc + 2]));
            case CONST_NULL -> rStore(code[pc + 1], () -> op(Opcodes.ACONST_NULL));
            case MOV_P -> pStore(code[pc + 1], () -> pLoad(code[pc + 2]));
            case MOV_R -> rStore(code[pc + 1], () -> rLoad(code[pc + 2]));
            case NEG_I, NEG_L, NEG_F, NEG_D -> pStore(code[pc + 1], () -> {
                int kind = op - NEG_I;
                number(code[pc + 2], kind); op(Opcodes.INEG + kind); bits(kind);
            });
            case NOT -> pStore(code[pc + 1], () -> { pLoad(code[pc + 2]); op(Opcodes.LCONST_1); op(Opcodes.LXOR); });
            case IS_NULL, IS_NOT_NULL -> pStore(code[pc + 1], () -> {
                rLoad(code[pc + 2]); bool(op == IS_NULL ? Opcodes.IFNULL : Opcodes.IFNONNULL); op(Opcodes.I2L);
            });
            case R2S -> rStore(code[pc + 1], () -> {
                rLoad(code[pc + 2]); callStatic("dev/tachyonscript/api/value/Values", "toString", "(" + O + ")Ljava/lang/String;");
            });
            case S2C -> rStore(code[pc + 1], () -> {
                load(1); get(FN, "text", "Ldev/tachyonscript/runtime/spi/TextService;"); rLoad(code[pc + 2]);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                callInterface("dev/tachyonscript/runtime/spi/TextService", "parse", "(Ljava/lang/String;)" + O);
            });
            case INSTANCEOF -> pStore(code[pc + 1], () -> {
                pool("classes", "[Ljava/lang/Class;", code[pc + 3]); rLoad(code[pc + 2]);
                callVirtual("java/lang/Class", "isInstance", "(" + O + ")Z"); op(Opcodes.I2L);
            });
            case CHECKCAST, SAFECAST -> rStore(code[pc + 1], () -> {
                load(1); push(op == SAFECAST ? 1 : 0); rLoad(code[pc + 2]); push(code[pc + 3]);
                support("cast", "(" + F + "Z" + O + "I)" + O);
            });
            case CALL_V, CALL_P, CALL_R, CALL_CLOSURE_V, CALL_CLOSURE_P, CALL_CLOSURE_R -> scriptCall(pc);
            case CONCAT, NEW_LIST, NEW_MAP -> rStore(code[pc + 1], () -> {
                load(REFS); integer(4); codeArray(); push(pc + 3); push(code[pc + 2]);
                String method = op == CONCAT ? "concat" : op == NEW_LIST ? "newList" : "newMap";
                String result = op == CONCAT ? "Ljava/lang/String;" : "L" + VALUES + (op == NEW_LIST ? "ScriptList" : "ScriptMap") + ";";
                support(method, "(" + R + "I[III)" + result);
            });
            case TEMPLATE -> rStore(code[pc + 1], () -> {
                pool("templates", "[Ldev/tachyonscript/runtime/spi/MessageTemplate;", code[pc + 2]);
                arguments(pc + 4, code[pc + 3]);
                callInterface("dev/tachyonscript/runtime/spi/MessageTemplate", "render", "(Ldev/tachyonscript/api/natives/Arguments;)" + O);
            });
            case LIST_GET -> rStore(code[pc + 1], () -> {
                rLoad(code[pc + 2]); number(code[pc + 3], 0); support("listGet", "(" + O + "I)" + O);
            });
            case LIST_SET -> {
                rLoad(code[pc + 1]); number(code[pc + 2], 0); rLoad(code[pc + 3]); support("listSet", "(" + O + "I" + O + ")V");
            }
            case LIST_ADD -> { rLoad(code[pc + 1]); rLoad(code[pc + 2]); support("listAdd", "(" + O + O + ")V"); }
            case LIST_SIZE -> pStore(code[pc + 1], () -> {
                list(code[pc + 2]); callInterface("java/util/List", "size", "()I"); op(Opcodes.I2L);
            });
            case LIST_CONTAINS -> pStore(code[pc + 1], () -> {
                list(code[pc + 2]); rLoad(code[pc + 3]); callInterface("java/util/List", "contains", "(" + O + ")Z"); op(Opcodes.I2L);
            });
            case NEW_CLOSURE -> rStore(code[pc + 1], () -> {
                load(1); load(P); load(REFS); integer(3); integer(4); codeArray(); push(pc);
                support("newClosure", "(" + F + "[J" + R + "II[II)Ldev/tachyonscript/runtime/interpreter/Closure;");
            });
            case NEW_RECORD -> rStore(code[pc + 1], () -> {
                load(1); load(REFS); integer(4); codeArray(); push(pc);
                support("newRecord", "(" + F + R + "I[II)L" + VALUES + "RecordValue;");
            });
            case RECORD_GET -> rStore(code[pc + 1], () -> {
                rLoad(code[pc + 2]); mv.visitTypeInsn(Opcodes.CHECKCAST, VALUES + "RecordValue"); push(code[pc + 3]);
                callVirtual(VALUES + "RecordValue", "get", "(I)" + O);
            });
            case RECORD_TEST -> pStore(code[pc + 1], () -> {
                rLoad(code[pc + 2]); pool("records", "[L" + VALUES + "RecordType;", code[pc + 3]);
                support("isRecord", "(" + O + "L" + VALUES + "RecordType;)Z"); op(Opcodes.I2L);
            });
            case RECORD_CAST, SAFE_RECORD_CAST -> rStore(code[pc + 1], () -> {
                pool("records", "[L" + VALUES + "RecordType;", code[pc + 3]); push(op == SAFE_RECORD_CAST ? 1 : 0); rLoad(code[pc + 2]);
                support("recordCast", "(L" + VALUES + "RecordType;Z" + O + ")" + O);
            });
            case GLOBAL_GET_P -> pStore(code[pc + 1], () -> {
                global(code[pc + 2]); callVirtual(VALUES + "GlobalCell", "getPrimitive", "()J");
            });
            case GLOBAL_GET_R -> rStore(code[pc + 1], () -> {
                global(code[pc + 2]); callVirtual(VALUES + "GlobalCell", "getReference", "()" + O);
            });
            case GLOBAL_SET_P, GLOBAL_ADD -> {
                global(code[pc + 1]); pLoad(code[pc + 2]); callVirtual(VALUES + "GlobalCell", op == GLOBAL_ADD ? "add" : "setPrimitive", "(J)V");
            }
            case GLOBAL_SET_R -> {
                global(code[pc + 1]); rLoad(code[pc + 2]); callVirtual(VALUES + "GlobalCell", "setReference", "(" + O + ")V");
            }
            case GLOBAL_RESTORE, PDATA_GET, PDATA_SET, PDATA_ADD -> {
                load(1); load(2); integer(3); integer(4); codeArray(); push(pc);
                support("storage", "(" + F + S + "II[II)V"); refreshArrays();
            }
            case THROW -> { rLoad(code[pc + 1]); support("thrown", "(" + O + ")L" + FAILURE + ";"); op(Opcodes.ATHROW); }
            case JMP -> mv.visitJumpInsn(Opcodes.GOTO, label(code[pc + 1]));
            case LOOP -> {
                load(2); op(Opcodes.DUP); get(STACK, "loopBudget", "I"); push(1); op(Opcodes.ISUB);
                op(Opcodes.DUP_X1); put(STACK, "loopBudget", "I");
                mv.visitJumpInsn(Opcodes.IFGT, label(code[pc + 1]));
                load(2); load(G); support("loopCheck", "(" + S + "L" + GUARD + ";)V");
                mv.visitJumpInsn(Opcodes.GOTO, label(code[pc + 1]));
            }
            case BR_T, BR_F -> {
                pLoad(code[pc + 1]); op(Opcodes.LCONST_0); op(Opcodes.LCMP);
                mv.visitJumpInsn(op == BR_T ? Opcodes.IFNE : Opcodes.IFEQ, label(code[pc + 2]));
            }
            case RET_V, RET_P, RET_R -> {
                if (op == RET_P) { load(2); pLoad(code[pc + 1]); put(STACK, "returnPrimitive", "J"); }
                if (op == RET_R) { load(2); rLoad(code[pc + 1]); put(STACK, "returnReference", O); }
                load(2); load(1); integer(3); integer(4); support("leave", "(" + S + F + "II)V"); op(Opcodes.RETURN);
            }
            case UNREACHABLE -> invalid("Invalid instruction UNREACHABLE (this is a TachyonScript bug)");
            default -> throw new IllegalArgumentException("Unsupported bytecode instruction " + op + " in " + unit.key());
        }
    }

    private void arithmetic(int pc) {
        int relative = code[pc] - ADD_I, kind = relative / 5, operation = relative % 5;
        pStore(code[pc + 1], () -> {
            number(code[pc + 2], kind); number(code[pc + 3], kind);
            if (kind <= 1 && operation >= 3) {
                Label nonzero = new Label();
                op(kind == 0 ? Opcodes.DUP : Opcodes.DUP2);
                if (kind == 1) { op(Opcodes.LCONST_0); op(Opcodes.LCMP); }
                mv.visitJumpInsn(Opcodes.IFNE, nonzero);
                support("divisionByZero", "()L" + FAILURE + ";"); op(Opcodes.ATHROW);
                mv.visitLabel(nonzero);
            }
            op(Opcodes.IADD + operation * 4 + kind); bits(kind);
        });
    }

    private void compare(int pc) {
        int opcode = code[pc];
        pStore(code[pc + 1], () -> {
            if (opcode == EQ_R || opcode == NE_R) {
                rLoad(code[pc + 2]); rLoad(code[pc + 3]);
                callStatic("java/util/Objects", "equals", "(" + O + O + ")Z");
                if (opcode == NE_R) { push(1); op(Opcodes.IXOR); }
            } else {
                int kind = opcode >= EQ_Z ? 1 : (opcode - EQ_I) / 6;
                int comparison = opcode >= EQ_Z ? opcode - EQ_Z : (opcode - EQ_I) % 6;
                number(code[pc + 2], kind); number(code[pc + 3], kind);
                int[] conditions = {Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFLE, Opcodes.IFGT, Opcodes.IFGE};
                if (kind == 0) {
                    int[] ints = {Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPLE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPGE};
                    bool(ints[comparison]);
                } else {
                    if (kind == 1) op(Opcodes.LCMP);
                    else {
                        // NaN must fail both ordered comparisons: choose the opposing sentinel.
                        boolean greaterSentinel = comparison == 2 || comparison == 3;
                        op(kind == 2 ? (greaterSentinel ? Opcodes.FCMPG : Opcodes.FCMPL)
                                : (greaterSentinel ? Opcodes.DCMPG : Opcodes.DCMPL));
                    }
                    bool(conditions[comparison]);
                }
            }
            op(Opcodes.I2L);
        });
    }

    private void bitwise(int pc) {
        int relative = code[pc] - AND_I, kind = relative >= 6 ? 1 : 0, operation = relative % 6;
        pStore(code[pc + 1], () -> {
            number(code[pc + 2], kind);
            number(code[pc + 3], operation >= 3 ? 0 : kind);
            int[] operations = {Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR};
            op(operations[operation] + kind); bits(kind);
        });
    }

    private void nativeCall(int pc) {
        int opcode = code[pc], result = opcode - CALL_NATIVE_V;
        int offset = result == 0 ? 1 : 2;
        String suffix = switch (result) { case 0 -> "Void"; case 1 -> "Int"; case 2 -> "Long"; case 3 -> "Float";
            case 4 -> "Double"; case 5 -> "Bool"; default -> "Ref"; };
        String shape = "dev/tachyonscript/api/natives/NativeFunction$Of" + suffix;
        pool("natives", "[Ldev/tachyonscript/api/natives/NativeFunction;", code[pc + offset]);
        mv.visitTypeInsn(Opcodes.CHECKCAST, shape);
        load(2); load(G); codeArray(); push(pc + offset + 2); push(code[pc + offset + 1]); integer(3); integer(4);
        support("nativeArguments", "(" + S + "L" + GUARD + ";[IIIII)Ldev/tachyonscript/runtime/interpreter/CallArguments;");
        String returns = switch (result) { case 0 -> "V"; case 1 -> "I"; case 2 -> "J"; case 3 -> "F";
            case 4 -> "D"; case 5 -> "Z"; default -> O; };
        callInterface(shape, "call", "(Ldev/tachyonscript/api/natives/Arguments;)" + returns);
        if (result == 6) mv.visitVarInsn(Opcodes.ASTORE, TEMP);
        else if (result != 0) {
            bits(result == 5 ? 0 : result - 1); mv.visitVarInsn(Opcodes.LSTORE, TEMP);
        }
        // A native may re-enter the runtime and grow both arrays: never store into stale arrays.
        refreshArrays();
        if (result == 6) rStore(code[pc + 1], () -> load(TEMP));
        else if (result != 0) pStore(code[pc + 1], () -> mv.visitVarInsn(Opcodes.LLOAD, TEMP));
    }

    private void scriptCall(int pc) {
        int opcode = code[pc];
        boolean closure = opcode >= CALL_CLOSURE_V;
        int kind = opcode - (closure ? CALL_CLOSURE_V : CALL_V);
        int offset = kind == 0 ? 1 : 2;
        load(1);
        if (closure) rLoad(code[pc + offset]);
        else pool("callees", "[" + F, code[pc + offset]);
        load(2); integer(3); integer(4); codeArray(); push(pc + offset + 2); push(code[pc + offset + 1]);
        support(closure ? "invokeClosure" : "invoke", "(" + F + (closure ? O : F) + S + "II[III)V");
        refreshArrays();
        if (kind == 1) pStore(code[pc + 1], () -> { load(2); get(STACK, "returnPrimitive", "J"); });
        if (kind == 2) {
            rStore(code[pc + 1], () -> { load(2); get(STACK, "returnReference", O); });
            load(2); op(Opcodes.ACONST_NULL); put(STACK, "returnReference", O);
        }
    }

    private void arguments(int position, int count) {
        load(2); codeArray(); push(position); push(count); integer(3); integer(4);
        callVirtual(STACK, "arguments", "([IIIII)Ldev/tachyonscript/runtime/interpreter/CallArguments;");
    }

    private void number(int slot, int kind) {
        pLoad(slot);
        switch (kind) {
            case 0 -> op(Opcodes.L2I);
            case 1 -> { }
            case 2 -> { op(Opcodes.L2I); callStatic("java/lang/Float", "intBitsToFloat", "(I)F"); }
            case 3 -> callStatic("java/lang/Double", "longBitsToDouble", "(J)D");
            default -> throw new IllegalArgumentException("Numeric kind " + kind);
        }
    }

    private void bits(int kind) {
        switch (kind) {
            case 0 -> op(Opcodes.I2L);
            case 1 -> { }
            case 2 -> { callStatic("java/lang/Float", "floatToRawIntBits", "(F)I"); op(Opcodes.I2L); }
            case 3 -> callStatic("java/lang/Double", "doubleToRawLongBits", "(D)J");
            default -> throw new IllegalArgumentException("Numeric kind " + kind);
        }
    }

    private void bool(int condition) {
        Label yes = new Label(), done = new Label();
        mv.visitJumpInsn(condition, yes); push(0); mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(yes); push(1); mv.visitLabel(done);
    }

    private void refreshArrays() {
        load(2); get(STACK, "primitives", "[J"); mv.visitVarInsn(Opcodes.ASTORE, P);
        load(2); get(STACK, "references", R); mv.visitVarInsn(Opcodes.ASTORE, REFS);
    }

    private void address(int slot, boolean reference) {
        load(reference ? REFS : P); integer(reference ? 4 : 3);
        if (slot != 0) { push(slot); op(Opcodes.IADD); }
    }

    private void pLoad(int slot) { address(slot, false); op(Opcodes.LALOAD); }
    private void rLoad(int slot) { address(slot, true); op(Opcodes.AALOAD); }
    private void pStore(int slot, Runnable value) { address(slot, false); value.run(); op(Opcodes.LASTORE); }
    private void rStore(int slot, Runnable value) { address(slot, true); value.run(); op(Opcodes.AASTORE); }
    private void list(int slot) { rLoad(slot); mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List"); }
    private void global(int index) { pool("globals", "[L" + VALUES + "GlobalCell;", index); }
    private void pool(String field, String descriptor, int index) { load(1); get(FN, field, descriptor); push(index); op(Opcodes.AALOAD); }
    private void codeArray() { load(1); get(FN, "code", "[I"); }
    private void load(int local) { mv.visitVarInsn(Opcodes.ALOAD, local); }
    private void integer(int local) { mv.visitVarInsn(Opcodes.ILOAD, local); }
    /** The shortest JVM form of an int constant: method size decides whether HotSpot compiles a body. */
    private void push(int value) {
        if (value >= -1 && value <= 5) op(Opcodes.ICONST_0 + value);
        else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, value);
        else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, value);
        else mv.visitLdcInsn(value);
    }
    private void pushLong(long value) {
        if (value == 0L || value == 1L) op(Opcodes.LCONST_0 + (int) value);
        else mv.visitLdcInsn(value);
    }
    private void op(int opcode) { mv.visitInsn(opcode); }
    private void get(String owner, String name, String type) { mv.visitFieldInsn(Opcodes.GETFIELD, owner, name, type); }
    private void put(String owner, String name, String type) { mv.visitFieldInsn(Opcodes.PUTFIELD, owner, name, type); }
    private void support(String name, String type) { callStatic(SUPPORT, name, type); }
    private void callStatic(String owner, String name, String type) { mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, type, false); }
    private void callVirtual(String owner, String name, String type) { mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, type, false); }
    private void callInterface(String owner, String name, String type) { mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, name, type, true); }
    private Label label(int pc) {
        Label found = labels.get(pc);
        if (found == null) throw new IllegalArgumentException("Jump into an operand at " + pc + " in " + unit.key());
        return found;
    }

    private void invalid(String message) {
        mv.visitTypeInsn(Opcodes.NEW, FAILURE); op(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, FAILURE + "$Kind", "INTERNAL", "L" + FAILURE + "$Kind;");
        mv.visitLdcInsn(message); op(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, FAILURE, "<init>", "(L" + FAILURE + "$Kind;Ljava/lang/String;Ljava/lang/Throwable;)V", false);
        op(Opcodes.ATHROW);
    }

    private static String readable(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 40));
        for (int i = 0; i < text.length() && out.length() < 40; i++) {
            char c = text.charAt(i);
            out.append(c < 128 && Character.isLetterOrDigit(c) ? c : '_');
        }
        return out.toString();
    }

    private static String identity(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
