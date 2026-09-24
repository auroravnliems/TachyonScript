package dev.tachyonscript.runtime.code;

import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.type.Representation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders packed interpreter code as text, for {@code tys dump code} and for debugging the
 * assembler:
 *
 * <pre>
 * handler player.join (script join.tys:1) [p: 0, r: 3]
 *      0  CALL_NATIVE_R   r1 = event player.join:player(r0)
 *      4  TEMPLATE        r2 = template#0(r1)
 *      8  CALL_NATIVE_V   Player.send(Component)(r1, r2)
 *     12  RET_V
 * </pre>
 *
 * <p>{@code pN} is a primitive slot and {@code rN} a reference slot of the frame.
 */
public final class Disassembler {

    private static final String[] SHAPES = new String[Opcodes.COUNT];

    static {
        shape("", Opcodes.NOP, Opcodes.RET_V, Opcodes.UNREACHABLE);
        shape("PI", Opcodes.CONST_I);
        shape("PL", Opcodes.CONST_L);
        shape("RO", Opcodes.CONST_R);
        shape("R", Opcodes.CONST_NULL, Opcodes.RET_R);
        shape("P", Opcodes.RET_P);
        shape("PP", Opcodes.MOV_P, Opcodes.NEG_I, Opcodes.NEG_L, Opcodes.NEG_F, Opcodes.NEG_D, Opcodes.NOT);
        shape("RR", Opcodes.MOV_R, Opcodes.R2S, Opcodes.S2C, Opcodes.LIST_ADD);
        shape("PR", Opcodes.IS_NULL, Opcodes.IS_NOT_NULL, Opcodes.LIST_SIZE);
        for (int op = Opcodes.ADD_I; op <= Opcodes.NE_Z; op++) {
            SHAPES[op] = "PPP";
        }
        shape("PRR", Opcodes.EQ_R, Opcodes.NE_R, Opcodes.LIST_CONTAINS);
        for (int op = Opcodes.I2L; op <= Opcodes.D2F; op++) {
            SHAPES[op] = "PP";
        }
        for (int op = Opcodes.BOX_I; op <= Opcodes.BOX_Z; op++) {
            SHAPES[op] = "RP";
        }
        for (int op = Opcodes.UNBOX_I; op <= Opcodes.UNBOX_Z; op++) {
            SHAPES[op] = "PR";
        }
        for (int op = Opcodes.I2S; op <= Opcodes.DUR2S; op++) {
            SHAPES[op] = "RP";
        }
        shape("PRC", Opcodes.INSTANCEOF);
        shape("RRC", Opcodes.CHECKCAST, Opcodes.SAFECAST);
        shape("RRP", Opcodes.LIST_GET);
        shape("RPR", Opcodes.LIST_SET);
        shape("T", Opcodes.JMP, Opcodes.LOOP);
        shape("PT", Opcodes.BR_T, Opcodes.BR_F);
    }

    private final Map<String, CodeUnit> units;

    private Disassembler(Map<String, CodeUnit> units) {
        this.units = units;
    }

    private static void shape(String shape, int... opcodes) {
        for (int opcode : opcodes) {
            SHAPES[opcode] = shape;
        }
    }

    /** Disassembles every code unit of a module. */
    public static String disassemble(AssembledModule module) {
        return disassemble(module.units());
    }

    /** Disassembles code units that may call each other, in the given order. */
    public static String disassemble(Collection<CodeUnit> units) {
        Map<String, CodeUnit> byKey = new HashMap<>();
        for (CodeUnit unit : units) {
            byKey.put(unit.key(), unit);
        }
        Disassembler disassembler = new Disassembler(byKey);
        StringBuilder out = new StringBuilder();
        for (CodeUnit unit : units) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            disassembler.unit(unit, out);
        }
        return out.toString();
    }

    /** Disassembles one code unit; calls to other script functions show untyped slots. */
    public static String disassemble(CodeUnit unit) {
        StringBuilder out = new StringBuilder();
        new Disassembler(Map.of(unit.key(), unit)).unit(unit, out);
        return out.toString();
    }

    private void unit(CodeUnit unit, StringBuilder out) {
        out.append(unit.displayName()).append(" [").append(unit.key()).append("] (");
        for (int i = 0; i < unit.parameterSlots().length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(slot(unit.parameterIsReference()[i], unit.parameterSlots()[i]));
        }
        out.append(") -> ").append(unit.returnKind().name().toLowerCase(Locale.ROOT))
                .append("  [p: ").append(unit.primitiveSlots()).append(", r: ").append(unit.referenceSlots()).append("]\n");
        int[] code = unit.code();
        int pc = 0;
        while (pc < code.length) {
            int length = Opcodes.length(code, pc);
            out.append(String.format(Locale.ROOT, "%6d  %-15s ", pc, Opcodes.name(code[pc])));
            instruction(unit, code, pc, out);
            trimEnd(out);
            out.append('\n');
            pc += length;
        }
    }

    private void instruction(CodeUnit unit, int[] code, int pc, StringBuilder out) {
        int op = code[pc];
        String shape = SHAPES[op];
        if (shape != null) {
            for (int i = 0; i < shape.length(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                operand(unit, shape.charAt(i), code[pc + 1 + i], out);
            }
            return;
        }
        switch (op) {
            case Opcodes.CALL_NATIVE_V -> nativeCall(unit, code, pc + 1, null, out);
            case Opcodes.CALL_NATIVE_I, Opcodes.CALL_NATIVE_L, Opcodes.CALL_NATIVE_F, Opcodes.CALL_NATIVE_D,
                 Opcodes.CALL_NATIVE_Z -> nativeCall(unit, code, pc + 2, slot(false, code[pc + 1]), out);
            case Opcodes.CALL_NATIVE_R -> nativeCall(unit, code, pc + 2, slot(true, code[pc + 1]), out);
            case Opcodes.CALL_V -> scriptCall(unit, code, pc + 1, null, out);
            case Opcodes.CALL_P -> scriptCall(unit, code, pc + 2, slot(false, code[pc + 1]), out);
            case Opcodes.CALL_R -> scriptCall(unit, code, pc + 2, slot(true, code[pc + 1]), out);
            case Opcodes.CONCAT -> {
                out.append(slot(true, code[pc + 1])).append(" = concat");
                references(code, pc + 3, code[pc + 2], out);
            }
            case Opcodes.NEW_LIST -> {
                out.append(slot(true, code[pc + 1])).append(" = list");
                references(code, pc + 3, code[pc + 2], out);
            }
            case Opcodes.TEMPLATE -> {
                out.append(slot(true, code[pc + 1])).append(" = template#").append(code[pc + 2])
                        .append(' ').append(quote(String.join("{}", unit.templates().get(code[pc + 2]))));
                references(code, pc + 4, code[pc + 3], out);
            }
            default -> out.append("<unknown opcode ").append(op).append('>');
        }
    }

    private void operand(CodeUnit unit, char kind, int value, StringBuilder out) {
        switch (kind) {
            case 'P' -> out.append('p').append(value);
            case 'R' -> out.append('r').append(value);
            case 'I' -> out.append(value);
            case 'L' -> {
                // The pool holds raw bits; the slot's type decides whether they are a long or a double.
                long bits = unit.primitivePool()[value];
                out.append(bits).append("L / ").append(Double.longBitsToDouble(bits)).append('d');
            }
            case 'O' -> out.append(constant(unit.referencePool()[value]));
            case 'C' -> out.append(unit.classes().get(value).name());
            case 'T' -> out.append("-> ").append(value);
            default -> throw new IllegalStateException("Unknown operand kind " + kind);
        }
    }

    private void nativeCall(CodeUnit unit, int[] code, int at, String target, StringBuilder out) {
        NativeDeclaration declaration = unit.natives().get(code[at]);
        int count = code[at + 1];
        if (target != null) {
            out.append(target).append(" = ");
        }
        out.append(declaration.key()).append('(');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(", ");
            }
            boolean reference = i < declaration.parameters().size()
                    && declaration.parameters().get(i).type().representation() == Representation.REF;
            out.append(slot(reference, code[at + 2 + i]));
        }
        out.append(')');
    }

    private void scriptCall(CodeUnit unit, int[] code, int at, String target, StringBuilder out) {
        String key = unit.functions().get(code[at]);
        CodeUnit callee = units.get(key);
        int count = code[at + 1];
        if (target != null) {
            out.append(target).append(" = ");
        }
        out.append(callee != null ? callee.displayName() : key).append('(');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(", ");
            }
            int slot = code[at + 2 + i];
            if (callee != null && i < callee.parameterIsReference().length) {
                out.append(slot(callee.parameterIsReference()[i], slot));
            } else {
                out.append('s').append(slot);
            }
        }
        out.append(')');
    }

    private static void references(int[] code, int at, int count, StringBuilder out) {
        out.append('(');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('r').append(code[at + i]);
        }
        out.append(')');
    }

    private static String slot(boolean reference, int slot) {
        return (reference ? "r" : "p") + slot;
    }

    private static String constant(Object value) {
        return switch (value) {
            case String text -> quote(text);
            case TemplateConstant template -> "template " + quote(String.join("{}", template.segments()));
            case null -> "null";
            default -> String.valueOf(value);
        };
    }

    /** Quotes a string, escaping quotes, backslashes and control characters. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void trimEnd(StringBuilder out) {
        int length = out.length();
        while (length > 0 && out.charAt(length - 1) == ' ') {
            length--;
        }
        out.setLength(length);
    }

    /** Opcode names of an instruction stream, in order (for tests). */
    public static List<String> opcodes(CodeUnit unit) {
        List<String> names = new ArrayList<>();
        int[] code = unit.code();
        for (int pc = 0; pc < code.length; pc += Opcodes.length(code, pc)) {
            names.add(Opcodes.name(code[pc]));
        }
        return names;
    }
}
