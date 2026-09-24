package dev.tachyonscript.language.semantic;

/**
 * Value conversions made explicit by the binder. The exact runtime operation follows from
 * the representations of the operand type and the target type.
 */
public enum ConversionKind {
    /** Between primitive numeric representations (widening, or explicit narrowing with {@code as}). */
    NUMERIC,
    /** Primitive to its boxed reference form (for nullable primitives and {@code any}). */
    BOX,
    /** Boxed reference to primitive; the operand is known to be non-null. */
    UNBOX,
    /** Any value to its canonical text form (see {@code dev.tachyonscript.api.value.Values}). */
    TO_STRING,
    /** A MiniMessage string to a component, parsed at runtime (or once at link time when constant). */
    STRING_TO_COMPONENT
}
