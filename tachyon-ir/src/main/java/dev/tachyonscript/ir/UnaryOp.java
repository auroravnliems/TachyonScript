package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Representation;

import static dev.tachyonscript.api.type.Representation.BOOL;
import static dev.tachyonscript.api.type.Representation.DOUBLE;
import static dev.tachyonscript.api.type.Representation.FLOAT;
import static dev.tachyonscript.api.type.Representation.INT;
import static dev.tachyonscript.api.type.Representation.LONG;
import static dev.tachyonscript.api.type.Representation.REF;

/** Unary operations, specialised by operand representation. */
public enum UnaryOp {
    NEG_I32(INT, INT),
    NEG_I64(LONG, LONG),
    NEG_F32(FLOAT, FLOAT),
    NEG_F64(DOUBLE, DOUBLE),
    NOT(BOOL, BOOL),
    IS_NULL(REF, BOOL),
    IS_NOT_NULL(REF, BOOL);

    private final Representation operand;
    private final Representation result;

    UnaryOp(Representation operand, Representation result) {
        this.operand = operand;
        this.result = result;
    }

    public Representation operand() {
        return operand;
    }

    public Representation result() {
        return result;
    }
}
