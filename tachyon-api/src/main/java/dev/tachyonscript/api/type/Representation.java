package dev.tachyonscript.api.type;

/**
 * How a value is stored at runtime.
 *
 * <p>Every TachyonScript type maps to exactly one representation. The interpreter keeps
 * all primitive representations in {@code long} slots (floats and doubles as raw bits,
 * booleans as 0/1) and references in {@code Object} slots, so values never need boxing
 * unless their static type is a nullable primitive.
 */
public enum Representation {
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOL,
    REF,
    VOID;

    /** Returns {@code true} for representations stored in primitive slots. */
    public boolean isPrimitive() {
        return this != REF && this != VOID;
    }
}
