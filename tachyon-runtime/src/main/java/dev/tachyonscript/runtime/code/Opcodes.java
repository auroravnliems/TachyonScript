package dev.tachyonscript.runtime.code;

/**
 * Opcodes of the interpreter's packed instruction format.
 *
 * <p>Code is an {@code int[]}: an opcode followed by its operands. Register operands are
 * frame-relative slot numbers in one of two spaces: {@code P} slots ({@code long}: ints,
 * longs, booleans, float and double bits) and {@code R} slots ({@code Object}). Constant
 * operands index per-function pools. Variadic instructions carry an explicit count.
 *
 * <pre>
 * CONST_I   Pd imm            CONST_L Pd kp          CONST_R Rd kr        CONST_NULL Rd
 * MOV_P     Pd Ps             MOV_R   Rd Rs
 * NEG_x, NOT Pd Pa            IS_NULL, IS_NOT_NULL Pd Ra
 * ADD_*...  Pd Pa Pb          EQ_R/NE_R Pd Ra Rb
 * I2L...    Pd Pa             BOX_*  Rd Pa           UNBOX_* Pd Ra
 * *2S       Rd Pa             R2S/S2C Rd Ra
 * INSTANCEOF Pd Ra kc         CHECKCAST/SAFECAST Rd Ra kc
 * CALL_NATIVE_V kn n args     CALL_NATIVE_* d kn n args
 * CALL_V    kf n args         CALL_P Pd kf n args    CALL_R Rd kf n args
 * CONCAT    Rd n Rparts       TEMPLATE Rd kt n Rargs  NEW_LIST Rd n Relems
 * LIST_GET  Rd Rl Pi          LIST_SET Rl Pi Rv      LIST_SIZE Pd Rl
 * LIST_ADD  Rl Rv             LIST_CONTAINS Pd Rl Rv
 * JMP t     LOOP t (back edge, checks the execution budget)
 * BR_T Pc t BR_F Pc t         RET_V   RET_P Ps   RET_R Rs   UNREACHABLE
 * </pre>
 */
public final class Opcodes {

    public static final int NOP = 0;
    public static final int CONST_I = 1;
    public static final int CONST_L = 2;
    public static final int CONST_R = 3;
    public static final int CONST_NULL = 4;
    public static final int MOV_P = 5;
    public static final int MOV_R = 6;
    public static final int NEG_I = 7;
    public static final int NEG_L = 8;
    public static final int NEG_F = 9;
    public static final int NEG_D = 10;
    public static final int NOT = 11;
    public static final int IS_NULL = 12;
    public static final int IS_NOT_NULL = 13;

    public static final int ADD_I = 14;
    public static final int SUB_I = 15;
    public static final int MUL_I = 16;
    public static final int DIV_I = 17;
    public static final int REM_I = 18;
    public static final int ADD_L = 19;
    public static final int SUB_L = 20;
    public static final int MUL_L = 21;
    public static final int DIV_L = 22;
    public static final int REM_L = 23;
    public static final int ADD_F = 24;
    public static final int SUB_F = 25;
    public static final int MUL_F = 26;
    public static final int DIV_F = 27;
    public static final int REM_F = 28;
    public static final int ADD_D = 29;
    public static final int SUB_D = 30;
    public static final int MUL_D = 31;
    public static final int DIV_D = 32;
    public static final int REM_D = 33;

    public static final int EQ_I = 34;
    public static final int NE_I = 35;
    public static final int LT_I = 36;
    public static final int LE_I = 37;
    public static final int GT_I = 38;
    public static final int GE_I = 39;
    public static final int EQ_L = 40;
    public static final int NE_L = 41;
    public static final int LT_L = 42;
    public static final int LE_L = 43;
    public static final int GT_L = 44;
    public static final int GE_L = 45;
    public static final int EQ_F = 46;
    public static final int NE_F = 47;
    public static final int LT_F = 48;
    public static final int LE_F = 49;
    public static final int GT_F = 50;
    public static final int GE_F = 51;
    public static final int EQ_D = 52;
    public static final int NE_D = 53;
    public static final int LT_D = 54;
    public static final int LE_D = 55;
    public static final int GT_D = 56;
    public static final int GE_D = 57;
    public static final int EQ_Z = 58;
    public static final int NE_Z = 59;
    public static final int EQ_R = 60;
    public static final int NE_R = 61;

    public static final int I2L = 62;
    public static final int I2F = 63;
    public static final int I2D = 64;
    public static final int L2I = 65;
    public static final int L2F = 66;
    public static final int L2D = 67;
    public static final int F2I = 68;
    public static final int F2L = 69;
    public static final int F2D = 70;
    public static final int D2I = 71;
    public static final int D2L = 72;
    public static final int D2F = 73;
    public static final int BOX_I = 74;
    public static final int BOX_L = 75;
    public static final int BOX_F = 76;
    public static final int BOX_D = 77;
    public static final int BOX_Z = 78;
    public static final int UNBOX_I = 79;
    public static final int UNBOX_L = 80;
    public static final int UNBOX_F = 81;
    public static final int UNBOX_D = 82;
    public static final int UNBOX_Z = 83;
    public static final int I2S = 84;
    public static final int L2S = 85;
    public static final int F2S = 86;
    public static final int D2S = 87;
    public static final int Z2S = 88;
    public static final int DUR2S = 89;
    public static final int R2S = 90;
    public static final int S2C = 91;

    public static final int INSTANCEOF = 92;
    public static final int CHECKCAST = 93;
    public static final int SAFECAST = 94;

    public static final int CALL_NATIVE_V = 95;
    public static final int CALL_NATIVE_I = 96;
    public static final int CALL_NATIVE_L = 97;
    public static final int CALL_NATIVE_F = 98;
    public static final int CALL_NATIVE_D = 99;
    public static final int CALL_NATIVE_Z = 100;
    public static final int CALL_NATIVE_R = 101;
    public static final int CALL_V = 102;
    public static final int CALL_P = 103;
    public static final int CALL_R = 104;

    public static final int CONCAT = 105;
    public static final int TEMPLATE = 106;
    public static final int NEW_LIST = 107;
    public static final int LIST_GET = 108;
    public static final int LIST_SET = 109;
    public static final int LIST_SIZE = 110;
    public static final int LIST_ADD = 111;
    public static final int LIST_CONTAINS = 112;

    public static final int JMP = 113;
    public static final int LOOP = 114;
    public static final int BR_T = 115;
    public static final int BR_F = 116;
    public static final int RET_V = 117;
    public static final int RET_P = 118;
    public static final int RET_R = 119;
    public static final int UNREACHABLE = 120;

    /** Number of opcodes. */
    public static final int COUNT = 121;

    /** Opcode names, indexed by opcode (for disassembly and diagnostics). */
    private static final String[] NAMES = {
            "NOP", "CONST_I", "CONST_L", "CONST_R", "CONST_NULL", "MOV_P", "MOV_R", "NEG_I", "NEG_L", "NEG_F",
            "NEG_D", "NOT", "IS_NULL", "IS_NOT_NULL", "ADD_I", "SUB_I", "MUL_I", "DIV_I", "REM_I", "ADD_L", "SUB_L",
            "MUL_L", "DIV_L", "REM_L", "ADD_F", "SUB_F", "MUL_F", "DIV_F", "REM_F", "ADD_D", "SUB_D", "MUL_D",
            "DIV_D", "REM_D", "EQ_I", "NE_I", "LT_I", "LE_I", "GT_I", "GE_I", "EQ_L", "NE_L", "LT_L", "LE_L", "GT_L",
            "GE_L", "EQ_F", "NE_F", "LT_F", "LE_F", "GT_F", "GE_F", "EQ_D", "NE_D", "LT_D", "LE_D", "GT_D", "GE_D",
            "EQ_Z", "NE_Z", "EQ_R", "NE_R", "I2L", "I2F", "I2D", "L2I", "L2F", "L2D", "F2I", "F2L", "F2D", "D2I",
            "D2L", "D2F", "BOX_I", "BOX_L", "BOX_F", "BOX_D", "BOX_Z", "UNBOX_I", "UNBOX_L", "UNBOX_F", "UNBOX_D",
            "UNBOX_Z", "I2S", "L2S", "F2S", "D2S", "Z2S", "DUR2S", "R2S", "S2C", "INSTANCEOF", "CHECKCAST",
            "SAFECAST", "CALL_NATIVE_V", "CALL_NATIVE_I", "CALL_NATIVE_L", "CALL_NATIVE_F", "CALL_NATIVE_D",
            "CALL_NATIVE_Z", "CALL_NATIVE_R", "CALL_V", "CALL_P", "CALL_R", "CONCAT", "TEMPLATE", "NEW_LIST",
            "LIST_GET", "LIST_SET", "LIST_SIZE", "LIST_ADD", "LIST_CONTAINS", "JMP", "LOOP", "BR_T", "BR_F", "RET_V",
            "RET_P", "RET_R", "UNREACHABLE"
    };
    /** Fixed instruction lengths (opcode included), or 0 for variadic instructions. */
    private static final int[] LENGTHS = new int[COUNT];

    static {
        if (NAMES.length != COUNT) {
            throw new ExceptionInInitializerError("Opcode name table is out of date");
        }
        setLength(1, NOP, RET_V, UNREACHABLE);
        setLength(2, CONST_NULL, JMP, LOOP, RET_P, RET_R);
        setLength(3, CONST_I, CONST_L, CONST_R, MOV_P, MOV_R, NEG_I, NEG_L, NEG_F, NEG_D, NOT, IS_NULL, IS_NOT_NULL,
                BR_T, BR_F, LIST_SIZE, LIST_ADD);
        for (int op = I2L; op <= S2C; op++) {
            LENGTHS[op] = 3;
        }
        for (int op = ADD_I; op <= NE_R; op++) {
            LENGTHS[op] = 4;
        }
        setLength(4, INSTANCEOF, CHECKCAST, SAFECAST, LIST_GET, LIST_SET, LIST_CONTAINS);
    }

    private Opcodes() {
    }

    private static void setLength(int length, int... ops) {
        for (int op : ops) {
            LENGTHS[op] = length;
        }
    }

    public static String name(int opcode) {
        return opcode >= 0 && opcode < COUNT && NAMES[opcode] != null ? NAMES[opcode] : "OP_" + opcode;
    }

    /** Length of the instruction at {@code pc}, operands included. */
    public static int length(int[] code, int pc) {
        int op = code[pc];
        int fixed = LENGTHS[op];
        if (fixed > 0) {
            return fixed;
        }
        return switch (op) {
            case CALL_NATIVE_V, CALL_V -> 3 + code[pc + 2];
            case CALL_NATIVE_I, CALL_NATIVE_L, CALL_NATIVE_F, CALL_NATIVE_D, CALL_NATIVE_Z, CALL_NATIVE_R,
                 CALL_P, CALL_R, TEMPLATE -> 4 + code[pc + 3];
            case CONCAT, NEW_LIST -> 3 + code[pc + 2];
            default -> throw new IllegalArgumentException("Unknown opcode " + op + " at " + pc);
        };
    }
}
