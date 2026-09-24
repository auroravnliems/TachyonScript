package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Representation;

import static dev.tachyonscript.api.type.Representation.BOOL;
import static dev.tachyonscript.api.type.Representation.DOUBLE;
import static dev.tachyonscript.api.type.Representation.FLOAT;
import static dev.tachyonscript.api.type.Representation.INT;
import static dev.tachyonscript.api.type.Representation.LONG;
import static dev.tachyonscript.api.type.Representation.REF;

/**
 * Binary operations, specialised by operand representation so that no instruction needs to
 * inspect value types at runtime. Integer arithmetic wraps (Java semantics); integer
 * division and remainder by zero raise a runtime error. {@code EQ_REF}/{@code NE_REF} use
 * value equality ({@code Objects.equals}).
 */
public enum BinaryOp {
    ADD_I32(INT, INT), SUB_I32(INT, INT), MUL_I32(INT, INT), DIV_I32(INT, INT), REM_I32(INT, INT),
    ADD_I64(LONG, LONG), SUB_I64(LONG, LONG), MUL_I64(LONG, LONG), DIV_I64(LONG, LONG), REM_I64(LONG, LONG),
    ADD_F32(FLOAT, FLOAT), SUB_F32(FLOAT, FLOAT), MUL_F32(FLOAT, FLOAT), DIV_F32(FLOAT, FLOAT), REM_F32(FLOAT, FLOAT),
    ADD_F64(DOUBLE, DOUBLE), SUB_F64(DOUBLE, DOUBLE), MUL_F64(DOUBLE, DOUBLE), DIV_F64(DOUBLE, DOUBLE),
    REM_F64(DOUBLE, DOUBLE),
    EQ_I32(INT, BOOL), NE_I32(INT, BOOL), LT_I32(INT, BOOL), LE_I32(INT, BOOL), GT_I32(INT, BOOL), GE_I32(INT, BOOL),
    EQ_I64(LONG, BOOL), NE_I64(LONG, BOOL), LT_I64(LONG, BOOL), LE_I64(LONG, BOOL), GT_I64(LONG, BOOL), GE_I64(LONG, BOOL),
    EQ_F32(FLOAT, BOOL), NE_F32(FLOAT, BOOL), LT_F32(FLOAT, BOOL), LE_F32(FLOAT, BOOL), GT_F32(FLOAT, BOOL),
    GE_F32(FLOAT, BOOL),
    EQ_F64(DOUBLE, BOOL), NE_F64(DOUBLE, BOOL), LT_F64(DOUBLE, BOOL), LE_F64(DOUBLE, BOOL), GT_F64(DOUBLE, BOOL),
    GE_F64(DOUBLE, BOOL),
    EQ_BOOL(BOOL, BOOL), NE_BOOL(BOOL, BOOL),
    EQ_REF(REF, BOOL), NE_REF(REF, BOOL);

    private final Representation operand;
    private final Representation result;

    BinaryOp(Representation operand, Representation result) {
        this.operand = operand;
        this.result = result;
    }

    /** Representation of both operands. */
    public Representation operand() {
        return operand;
    }

    public Representation result() {
        return result;
    }

    public boolean isComparison() {
        return result == BOOL;
    }

    /** Whether the operation can fail at runtime (integer division or remainder by zero). */
    public boolean canFail() {
        return this == DIV_I32 || this == REM_I32 || this == DIV_I64 || this == REM_I64;
    }
}
