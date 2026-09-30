package dev.tachyonscript.language.semantic;

/** Arithmetic and bitwise operations on numeric operands of one representation. */
public enum ArithmeticOp {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    REMAINDER,
    /** Bitwise operations: int and long only. */
    BIT_AND,
    BIT_OR,
    BIT_XOR,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    UNSIGNED_SHIFT_RIGHT;

    public boolean isBitwise() {
        return ordinal() >= BIT_AND.ordinal();
    }
}
