package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Representation;

import static dev.tachyonscript.api.type.Representation.BOOL;
import static dev.tachyonscript.api.type.Representation.DOUBLE;
import static dev.tachyonscript.api.type.Representation.FLOAT;
import static dev.tachyonscript.api.type.Representation.INT;
import static dev.tachyonscript.api.type.Representation.LONG;
import static dev.tachyonscript.api.type.Representation.REF;

/**
 * Value conversions. Numeric conversions follow Java semantics; {@code *_TO_STRING} produce
 * the canonical text defined by {@code dev.tachyonscript.api.value.Values};
 * {@code STRING_TO_COMPONENT} parses MiniMessage through the platform text service.
 */
public enum ConvertOp {
    I32_TO_I64(INT, LONG), I32_TO_F32(INT, FLOAT), I32_TO_F64(INT, DOUBLE),
    I64_TO_I32(LONG, INT), I64_TO_F32(LONG, FLOAT), I64_TO_F64(LONG, DOUBLE),
    F32_TO_I32(FLOAT, INT), F32_TO_I64(FLOAT, LONG), F32_TO_F64(FLOAT, DOUBLE),
    F64_TO_I32(DOUBLE, INT), F64_TO_I64(DOUBLE, LONG), F64_TO_F32(DOUBLE, FLOAT),
    BOX_I32(INT, REF), BOX_I64(LONG, REF), BOX_F32(FLOAT, REF), BOX_F64(DOUBLE, REF), BOX_BOOL(BOOL, REF),
    UNBOX_I32(REF, INT), UNBOX_I64(REF, LONG), UNBOX_F32(REF, FLOAT), UNBOX_F64(REF, DOUBLE), UNBOX_BOOL(REF, BOOL),
    I32_TO_STRING(INT, REF), I64_TO_STRING(LONG, REF), F32_TO_STRING(FLOAT, REF), F64_TO_STRING(DOUBLE, REF),
    BOOL_TO_STRING(BOOL, REF), DURATION_TO_STRING(LONG, REF), REF_TO_STRING(REF, REF),
    STRING_TO_COMPONENT(REF, REF);

    private final Representation operand;
    private final Representation result;

    ConvertOp(Representation operand, Representation result) {
        this.operand = operand;
        this.result = result;
    }

    public Representation operand() {
        return operand;
    }

    public Representation result() {
        return result;
    }

    /** The numeric conversion between two primitive representations, or {@code null} if none is needed/possible. */
    public static ConvertOp numeric(Representation from, Representation to) {
        for (ConvertOp op : values()) {
            if (op.ordinal() <= F64_TO_F32.ordinal() && op.operand == from && op.result == to) {
                return op;
            }
        }
        return null;
    }

    public static ConvertOp box(Representation from) {
        return switch (from) {
            case INT -> BOX_I32;
            case LONG -> BOX_I64;
            case FLOAT -> BOX_F32;
            case DOUBLE -> BOX_F64;
            case BOOL -> BOX_BOOL;
            default -> throw new IllegalArgumentException("Cannot box " + from);
        };
    }

    public static ConvertOp unbox(Representation to) {
        return switch (to) {
            case INT -> UNBOX_I32;
            case LONG -> UNBOX_I64;
            case FLOAT -> UNBOX_F32;
            case DOUBLE -> UNBOX_F64;
            case BOOL -> UNBOX_BOOL;
            default -> throw new IllegalArgumentException("Cannot unbox to " + to);
        };
    }
}
