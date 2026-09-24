package dev.tachyonscript.runtime.code;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.ir.BinaryOp;
import dev.tachyonscript.ir.ConvertOp;
import dev.tachyonscript.ir.Instruction;
import dev.tachyonscript.ir.IrBlock;
import dev.tachyonscript.ir.IrFunction;
import dev.tachyonscript.ir.IrModule;
import dev.tachyonscript.ir.Register;
import dev.tachyonscript.ir.Spans;
import dev.tachyonscript.ir.Terminator;
import dev.tachyonscript.ir.UnaryOp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates verified IR into the interpreter's packed code.
 *
 * <p>Each IR register gets a slot in the primitive or reference space of its frame. Blocks
 * are laid out in IR order; jumps to the next block become fall-throughs and two-way
 * branches pick the conditional form that falls through. Loop back edges become
 * {@link Opcodes#LOOP}, the only place the runtime checks its execution budget. A pc →
 * source span table is recorded for error reporting.
 */
public final class Assembler {

    private final IrModule module;

    private Assembler(IrModule module) {
        this.module = module;
    }

    public static AssembledModule assemble(IrModule module) {
        Assembler assembler = new Assembler(module);
        List<CodeUnit> units = new ArrayList<>();
        for (IrFunction function : module.functions()) {
            units.add(assembler.new FunctionAssembler(function).assemble());
        }
        List<AssembledModule.Handler> handlers = new ArrayList<>();
        for (IrModule.EventHandler handler : module.handlers()) {
            handlers.add(new AssembledModule.Handler(handler.event(), handler.function()));
        }
        return new AssembledModule(module.name(), module.source(), units, handlers);
    }

    private final class FunctionAssembler {
        private final IrFunction function;
        private final int[] slots;
        private final boolean[] isReference;
        private int primitiveSlots;
        private int referenceSlots;
        private int scratchPrimitive = -1;
        private int scratchReference = -1;
        private int[] code = new int[64];
        private int size;
        private final List<long[]> fixups = new ArrayList<>();
        private final Map<Long, Integer> primitivePool = new LinkedHashMap<>();
        private final Map<Object, Integer> referencePool = new LinkedHashMap<>();
        private final Map<NativeDeclaration, Integer> natives = new LinkedHashMap<>();
        private final Map<String, Integer> functions = new LinkedHashMap<>();
        private final Map<ClassType, Integer> classes = new LinkedHashMap<>();
        private final Map<List<String>, Integer> templates = new LinkedHashMap<>();
        private final List<Integer> linePcs = new ArrayList<>();
        private final List<Long> lineSpans = new ArrayList<>();
        private long currentSpan = Long.MIN_VALUE;

        FunctionAssembler(IrFunction function) {
            this.function = function;
            List<Register> registers = function.registers();
            this.slots = new int[registers.size()];
            this.isReference = new boolean[registers.size()];
            for (Register register : registers) {
                boolean reference = register.kind() == Representation.REF;
                isReference[register.index()] = reference;
                slots[register.index()] = reference ? referenceSlots++ : primitiveSlots++;
            }
        }

        CodeUnit assemble() {
            List<IrBlock> blocks = function.blocks();
            int[] blockStart = new int[blocks.size()];
            for (int b = 0; b < blocks.size(); b++) {
                IrBlock block = blocks.get(b);
                blockStart[b] = size;
                for (Instruction instruction : block.instructions()) {
                    instruction(instruction);
                }
                terminator(block.terminator(), b + 1 < blocks.size() ? b + 1 : -1);
            }
            for (long[] fixup : fixups) {
                code[(int) fixup[0]] = blockStart[(int) fixup[1]];
            }
            int[] parameterSlots = new int[function.parameters().size()];
            boolean[] parameterIsReference = new boolean[parameterSlots.length];
            for (int i = 0; i < parameterSlots.length; i++) {
                Register parameter = function.parameters().get(i);
                parameterSlots[i] = slots[parameter.index()];
                parameterIsReference[i] = isReference[parameter.index()];
            }
            long[] primitives = new long[primitivePool.size()];
            primitivePool.forEach((value, index) -> primitives[index] = value);
            Object[] references = new Object[referencePool.size()];
            referencePool.forEach((value, index) -> references[index] = value);
            return new CodeUnit(function.key(), function.displayName(), Arrays.copyOf(code, size),
                    primitiveSlots, referenceSlots, parameterSlots, parameterIsReference, function.returnKind(),
                    primitives, references, List.copyOf(natives.keySet()), List.copyOf(functions.keySet()),
                    List.copyOf(classes.keySet()), List.copyOf(templates.keySet()),
                    linePcs.stream().mapToInt(Integer::intValue).toArray(),
                    lineSpans.stream().mapToLong(Long::longValue).toArray(), function.span());
        }

        // ------------------------------------------------------------ instructions

        private void instruction(Instruction instruction) {
            mark(instruction.span());
            switch (instruction) {
                case Instruction.Const constant -> constant(constant);
                case Instruction.Move move -> emit(move.target().kind() == Representation.REF ? Opcodes.MOV_R : Opcodes.MOV_P,
                        slot(move.target()), slot(move.source()));
                case Instruction.Unary unary -> emit(unaryOpcode(unary.op()), slot(unary.target()), slot(unary.operand()));
                case Instruction.Binary binary -> emit(binaryOpcode(binary.op()), slot(binary.target()),
                        slot(binary.left()), slot(binary.right()));
                case Instruction.Convert convert -> emit(convertOpcode(convert.op()), slot(convert.target()),
                        slot(convert.operand()));
                case Instruction.InstanceOf test -> emit(Opcodes.INSTANCEOF, slot(test.target()), slot(test.operand()),
                        index(classes, test.type()));
                case Instruction.CheckCast cast -> emit(cast.safe() ? Opcodes.SAFECAST : Opcodes.CHECKCAST,
                        slot(cast.target()), slot(cast.operand()), index(classes, cast.type()));
                case Instruction.CallNative call -> nativeCall(call);
                case Instruction.Call call -> scriptCall(call);
                case Instruction.Concat concat -> variadic(Opcodes.CONCAT, slot(concat.target()), -1, concat.parts());
                case Instruction.RenderTemplate template -> template(template);
                case Instruction.NewList list -> variadic(Opcodes.NEW_LIST, slot(list.target()), -1, list.elements());
                case Instruction.ListGet get -> emit(Opcodes.LIST_GET, slot(get.target()), slot(get.list()), slot(get.index()));
                case Instruction.ListSet set -> emit(Opcodes.LIST_SET, slot(set.list()), slot(set.index()), slot(set.value()));
                case Instruction.ListSize listSize -> emit(Opcodes.LIST_SIZE, slot(listSize.target()), slot(listSize.list()));
                case Instruction.ListAdd add -> emit(Opcodes.LIST_ADD, slot(add.list()), slot(add.value()));
                case Instruction.ListContains contains -> emit(Opcodes.LIST_CONTAINS, slot(contains.target()),
                        slot(contains.list()), slot(contains.value()));
            }
        }

        private void constant(Instruction.Const constant) {
            int target = slot(constant.target());
            Object value = constant.value();
            switch (value) {
                case null -> emit(Opcodes.CONST_NULL, target);
                case Integer i -> emit(Opcodes.CONST_I, target, i);
                case Boolean b -> emit(Opcodes.CONST_I, target, b ? 1 : 0);
                case Float f -> emit(Opcodes.CONST_I, target, Float.floatToRawIntBits(f));
                case Long l -> emit(Opcodes.CONST_L, target, primitive(l));
                case Double d -> emit(Opcodes.CONST_L, target, primitive(Double.doubleToRawLongBits(d)));
                case String s -> emit(Opcodes.CONST_R, target, reference(s));
                default -> throw new IllegalStateException("Unsupported constant " + value.getClass());
            }
        }

        private void nativeCall(Instruction.CallNative call) {
            NativeDeclaration declaration = call.function();
            Representation result = declaration.returnRepresentation();
            int nativeIndex = index(natives, declaration);
            if (result == Representation.VOID) {
                variadic(Opcodes.CALL_NATIVE_V, -1, nativeIndex, call.arguments());
                return;
            }
            int target = call.target() != null ? slot(call.target()) : scratch(result == Representation.REF);
            int opcode = switch (result) {
                case INT -> Opcodes.CALL_NATIVE_I;
                case LONG -> Opcodes.CALL_NATIVE_L;
                case FLOAT -> Opcodes.CALL_NATIVE_F;
                case DOUBLE -> Opcodes.CALL_NATIVE_D;
                case BOOL -> Opcodes.CALL_NATIVE_Z;
                default -> Opcodes.CALL_NATIVE_R;
            };
            variadic(opcode, target, nativeIndex, call.arguments());
        }

        private void scriptCall(Instruction.Call call) {
            IrFunction callee = module.function(call.function())
                    .orElseThrow(() -> new IllegalStateException("Unknown function " + call.function()));
            Representation result = callee.returnKind();
            int functionIndex = index(functions, call.function());
            if (result == Representation.VOID) {
                variadic(Opcodes.CALL_V, -1, functionIndex, call.arguments());
                return;
            }
            boolean reference = result == Representation.REF;
            int target = call.target() != null ? slot(call.target()) : scratch(reference);
            variadic(reference ? Opcodes.CALL_R : Opcodes.CALL_P, target, functionIndex, call.arguments());
        }

        private void template(Instruction.RenderTemplate template) {
            if (template.arguments().isEmpty()) {
                emit(Opcodes.CONST_R, slot(template.target()), reference(new TemplateConstant(template.segments())));
                return;
            }
            variadic(Opcodes.TEMPLATE, slot(template.target()), index(templates, template.segments()), template.arguments());
        }

        /** Emits {@code opcode [target] [index] count operands...}; -1 omits target or index. */
        private void variadic(int opcode, int target, int tableIndex, List<Register> operands) {
            append(opcode);
            if (target >= 0) {
                append(target);
            }
            if (tableIndex >= 0) {
                append(tableIndex);
            }
            append(operands.size());
            for (Register operand : operands) {
                append(slot(operand));
            }
        }

        private void terminator(Terminator terminator, int next) {
            mark(terminator.span());
            switch (terminator) {
                case Terminator.Jump jump -> {
                    if (jump.backEdge()) {
                        append(Opcodes.LOOP);
                        jumpTarget(jump.target());
                    } else if (jump.target() != next) {
                        append(Opcodes.JMP);
                        jumpTarget(jump.target());
                    }
                }
                case Terminator.Branch branch -> {
                    int condition = slot(branch.condition());
                    if (branch.ifFalse() == next) {
                        append(Opcodes.BR_T);
                        append(condition);
                        jumpTarget(branch.ifTrue());
                    } else if (branch.ifTrue() == next) {
                        append(Opcodes.BR_F);
                        append(condition);
                        jumpTarget(branch.ifFalse());
                    } else {
                        append(Opcodes.BR_T);
                        append(condition);
                        jumpTarget(branch.ifTrue());
                        append(Opcodes.JMP);
                        jumpTarget(branch.ifFalse());
                    }
                }
                case Terminator.Return ret -> {
                    if (ret.value() == null) {
                        append(Opcodes.RET_V);
                    } else {
                        emit(ret.value().kind() == Representation.REF ? Opcodes.RET_R : Opcodes.RET_P, slot(ret.value()));
                    }
                }
                case Terminator.Unreachable ignored -> append(Opcodes.UNREACHABLE);
            }
        }

        private void jumpTarget(int block) {
            fixups.add(new long[] {size, block});
            append(-1);
        }

        // ------------------------------------------------------------ helpers

        private void mark(long span) {
            if (span != currentSpan && !Spans.isNone(span)) {
                linePcs.add(size);
                lineSpans.add(span);
                currentSpan = span;
            }
        }

        private int slot(Register register) {
            return slots[register.index()];
        }

        private int scratch(boolean reference) {
            if (reference) {
                if (scratchReference < 0) {
                    scratchReference = referenceSlots++;
                }
                return scratchReference;
            }
            if (scratchPrimitive < 0) {
                scratchPrimitive = primitiveSlots++;
            }
            return scratchPrimitive;
        }

        private int primitive(long value) {
            return primitivePool.computeIfAbsent(value, k -> primitivePool.size());
        }

        private int reference(Object value) {
            return referencePool.computeIfAbsent(value, k -> referencePool.size());
        }

        private <T> int index(Map<T, Integer> table, T value) {
            return table.computeIfAbsent(value, k -> table.size());
        }

        private void emit(int... words) {
            for (int word : words) {
                append(word);
            }
        }

        private void append(int word) {
            if (size == code.length) {
                code = Arrays.copyOf(code, size * 2);
            }
            code[size++] = word;
        }
    }

    // ---------------------------------------------------------------- opcode tables

    static int unaryOpcode(UnaryOp op) {
        return switch (op) {
            case NEG_I32 -> Opcodes.NEG_I;
            case NEG_I64 -> Opcodes.NEG_L;
            case NEG_F32 -> Opcodes.NEG_F;
            case NEG_F64 -> Opcodes.NEG_D;
            case NOT -> Opcodes.NOT;
            case IS_NULL -> Opcodes.IS_NULL;
            case IS_NOT_NULL -> Opcodes.IS_NOT_NULL;
        };
    }

    static int binaryOpcode(BinaryOp op) {
        return switch (op) {
            case ADD_I32 -> Opcodes.ADD_I;
            case SUB_I32 -> Opcodes.SUB_I;
            case MUL_I32 -> Opcodes.MUL_I;
            case DIV_I32 -> Opcodes.DIV_I;
            case REM_I32 -> Opcodes.REM_I;
            case ADD_I64 -> Opcodes.ADD_L;
            case SUB_I64 -> Opcodes.SUB_L;
            case MUL_I64 -> Opcodes.MUL_L;
            case DIV_I64 -> Opcodes.DIV_L;
            case REM_I64 -> Opcodes.REM_L;
            case ADD_F32 -> Opcodes.ADD_F;
            case SUB_F32 -> Opcodes.SUB_F;
            case MUL_F32 -> Opcodes.MUL_F;
            case DIV_F32 -> Opcodes.DIV_F;
            case REM_F32 -> Opcodes.REM_F;
            case ADD_F64 -> Opcodes.ADD_D;
            case SUB_F64 -> Opcodes.SUB_D;
            case MUL_F64 -> Opcodes.MUL_D;
            case DIV_F64 -> Opcodes.DIV_D;
            case REM_F64 -> Opcodes.REM_D;
            case EQ_I32 -> Opcodes.EQ_I;
            case NE_I32 -> Opcodes.NE_I;
            case LT_I32 -> Opcodes.LT_I;
            case LE_I32 -> Opcodes.LE_I;
            case GT_I32 -> Opcodes.GT_I;
            case GE_I32 -> Opcodes.GE_I;
            case EQ_I64 -> Opcodes.EQ_L;
            case NE_I64 -> Opcodes.NE_L;
            case LT_I64 -> Opcodes.LT_L;
            case LE_I64 -> Opcodes.LE_L;
            case GT_I64 -> Opcodes.GT_L;
            case GE_I64 -> Opcodes.GE_L;
            case EQ_F32 -> Opcodes.EQ_F;
            case NE_F32 -> Opcodes.NE_F;
            case LT_F32 -> Opcodes.LT_F;
            case LE_F32 -> Opcodes.LE_F;
            case GT_F32 -> Opcodes.GT_F;
            case GE_F32 -> Opcodes.GE_F;
            case EQ_F64 -> Opcodes.EQ_D;
            case NE_F64 -> Opcodes.NE_D;
            case LT_F64 -> Opcodes.LT_D;
            case LE_F64 -> Opcodes.LE_D;
            case GT_F64 -> Opcodes.GT_D;
            case GE_F64 -> Opcodes.GE_D;
            case EQ_BOOL -> Opcodes.EQ_Z;
            case NE_BOOL -> Opcodes.NE_Z;
            case EQ_REF -> Opcodes.EQ_R;
            case NE_REF -> Opcodes.NE_R;
        };
    }

    static int convertOpcode(ConvertOp op) {
        return switch (op) {
            case I32_TO_I64 -> Opcodes.I2L;
            case I32_TO_F32 -> Opcodes.I2F;
            case I32_TO_F64 -> Opcodes.I2D;
            case I64_TO_I32 -> Opcodes.L2I;
            case I64_TO_F32 -> Opcodes.L2F;
            case I64_TO_F64 -> Opcodes.L2D;
            case F32_TO_I32 -> Opcodes.F2I;
            case F32_TO_I64 -> Opcodes.F2L;
            case F32_TO_F64 -> Opcodes.F2D;
            case F64_TO_I32 -> Opcodes.D2I;
            case F64_TO_I64 -> Opcodes.D2L;
            case F64_TO_F32 -> Opcodes.D2F;
            case BOX_I32 -> Opcodes.BOX_I;
            case BOX_I64 -> Opcodes.BOX_L;
            case BOX_F32 -> Opcodes.BOX_F;
            case BOX_F64 -> Opcodes.BOX_D;
            case BOX_BOOL -> Opcodes.BOX_Z;
            case UNBOX_I32 -> Opcodes.UNBOX_I;
            case UNBOX_I64 -> Opcodes.UNBOX_L;
            case UNBOX_F32 -> Opcodes.UNBOX_F;
            case UNBOX_F64 -> Opcodes.UNBOX_D;
            case UNBOX_BOOL -> Opcodes.UNBOX_Z;
            case I32_TO_STRING -> Opcodes.I2S;
            case I64_TO_STRING -> Opcodes.L2S;
            case F32_TO_STRING -> Opcodes.F2S;
            case F64_TO_STRING -> Opcodes.D2S;
            case BOOL_TO_STRING -> Opcodes.Z2S;
            case DURATION_TO_STRING -> Opcodes.DUR2S;
            case REF_TO_STRING -> Opcodes.R2S;
            case STRING_TO_COMPONENT -> Opcodes.S2C;
        };
    }
}
