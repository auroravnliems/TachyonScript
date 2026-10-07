package dev.tachyonscript.ir.opt;

import dev.tachyonscript.ir.BinaryOp;
import dev.tachyonscript.ir.UnaryOp;

import java.util.Objects;

/** Uses the same Java primitive operations as the interpreter, including overflow, NaN and signed zero. */
final class ConstantEvaluation {
    private ConstantEvaluation() { }

    static Object unary(UnaryOp op, Object value) {
        return switch (op) {
            case NEG_I32 -> -(Integer) value;
            case NEG_I64 -> -(Long) value;
            case NEG_F32 -> -(Float) value;
            case NEG_F64 -> -(Double) value;
            case NOT -> !(Boolean) value;
            case IS_NULL -> value == null;
            case IS_NOT_NULL -> value != null;
        };
    }

    static Object binary(BinaryOp op, Object left, Object right) {
        return switch (op) {
            case ADD_I32 -> ((Integer) left).intValue() + ((Integer) right).intValue();
            case SUB_I32 -> ((Integer) left).intValue() - ((Integer) right).intValue();
            case MUL_I32 -> ((Integer) left).intValue() * ((Integer) right).intValue();
            case DIV_I32 -> ((Integer) left).intValue() / ((Integer) right).intValue();
            case REM_I32 -> ((Integer) left).intValue() % ((Integer) right).intValue();
            case EQ_I32 -> ((Integer) left).intValue() == ((Integer) right).intValue();
            case NE_I32 -> ((Integer) left).intValue() != ((Integer) right).intValue();
            case LT_I32 -> ((Integer) left).intValue() < ((Integer) right).intValue();
            case LE_I32 -> ((Integer) left).intValue() <= ((Integer) right).intValue();
            case GT_I32 -> ((Integer) left).intValue() > ((Integer) right).intValue();
            case GE_I32 -> ((Integer) left).intValue() >= ((Integer) right).intValue();
            case ADD_I64 -> ((Long) left).longValue() + ((Long) right).longValue();
            case SUB_I64 -> ((Long) left).longValue() - ((Long) right).longValue();
            case MUL_I64 -> ((Long) left).longValue() * ((Long) right).longValue();
            case DIV_I64 -> ((Long) left).longValue() / ((Long) right).longValue();
            case REM_I64 -> ((Long) left).longValue() % ((Long) right).longValue();
            case EQ_I64 -> ((Long) left).longValue() == ((Long) right).longValue();
            case NE_I64 -> ((Long) left).longValue() != ((Long) right).longValue();
            case LT_I64 -> ((Long) left).longValue() < ((Long) right).longValue();
            case LE_I64 -> ((Long) left).longValue() <= ((Long) right).longValue();
            case GT_I64 -> ((Long) left).longValue() > ((Long) right).longValue();
            case GE_I64 -> ((Long) left).longValue() >= ((Long) right).longValue();
            case ADD_F32 -> ((Float) left).floatValue() + ((Float) right).floatValue();
            case SUB_F32 -> ((Float) left).floatValue() - ((Float) right).floatValue();
            case MUL_F32 -> ((Float) left).floatValue() * ((Float) right).floatValue();
            case DIV_F32 -> ((Float) left).floatValue() / ((Float) right).floatValue();
            case REM_F32 -> ((Float) left).floatValue() % ((Float) right).floatValue();
            case EQ_F32 -> ((Float) left).floatValue() == ((Float) right).floatValue();
            case NE_F32 -> ((Float) left).floatValue() != ((Float) right).floatValue();
            case LT_F32 -> ((Float) left).floatValue() < ((Float) right).floatValue();
            case LE_F32 -> ((Float) left).floatValue() <= ((Float) right).floatValue();
            case GT_F32 -> ((Float) left).floatValue() > ((Float) right).floatValue();
            case GE_F32 -> ((Float) left).floatValue() >= ((Float) right).floatValue();
            case ADD_F64 -> ((Double) left).doubleValue() + ((Double) right).doubleValue();
            case SUB_F64 -> ((Double) left).doubleValue() - ((Double) right).doubleValue();
            case MUL_F64 -> ((Double) left).doubleValue() * ((Double) right).doubleValue();
            case DIV_F64 -> ((Double) left).doubleValue() / ((Double) right).doubleValue();
            case REM_F64 -> ((Double) left).doubleValue() % ((Double) right).doubleValue();
            case EQ_F64 -> ((Double) left).doubleValue() == ((Double) right).doubleValue();
            case NE_F64 -> ((Double) left).doubleValue() != ((Double) right).doubleValue();
            case LT_F64 -> ((Double) left).doubleValue() < ((Double) right).doubleValue();
            case LE_F64 -> ((Double) left).doubleValue() <= ((Double) right).doubleValue();
            case GT_F64 -> ((Double) left).doubleValue() > ((Double) right).doubleValue();
            case GE_F64 -> ((Double) left).doubleValue() >= ((Double) right).doubleValue();
            case AND_I32 -> ((Integer) left).intValue() & ((Integer) right).intValue();
            case OR_I32 -> ((Integer) left).intValue() | ((Integer) right).intValue();
            case XOR_I32 -> ((Integer) left).intValue() ^ ((Integer) right).intValue();
            case SHL_I32 -> ((Integer) left).intValue() << ((Integer) right).intValue();
            case SHR_I32 -> ((Integer) left).intValue() >> ((Integer) right).intValue();
            case USHR_I32 -> ((Integer) left).intValue() >>> ((Integer) right).intValue();
            case AND_I64 -> ((Long) left).longValue() & ((Long) right).longValue();
            case OR_I64 -> ((Long) left).longValue() | ((Long) right).longValue();
            case XOR_I64 -> ((Long) left).longValue() ^ ((Long) right).longValue();
            case SHL_I64 -> ((Long) left).longValue() << ((Long) right).longValue();
            case SHR_I64 -> ((Long) left).longValue() >> ((Long) right).longValue();
            case USHR_I64 -> ((Long) left).longValue() >>> ((Long) right).longValue();
            case EQ_BOOL -> ((Boolean) left).booleanValue() == ((Boolean) right).booleanValue();
            case NE_BOOL -> ((Boolean) left).booleanValue() != ((Boolean) right).booleanValue();
            case EQ_REF -> Objects.equals(left, right);
            case NE_REF -> !Objects.equals(left, right);
        };
    }
}

