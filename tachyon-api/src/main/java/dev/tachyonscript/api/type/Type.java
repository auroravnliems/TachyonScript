package dev.tachyonscript.api.type;

/**
 * A TachyonScript type as seen by the compiler.
 *
 * <p>Types are immutable. {@link ClassType}s are canonical objects (compared by identity);
 * structural types ({@link NullableType}, {@link ListType}, {@link MapType}) are records
 * compared by value. Types never reference Bukkit or Java classes: the mapping from a type
 * to a runtime class is a platform binding.
 */
public sealed interface Type
        permits PrimitiveType, ClassType, NullableType, ListType, MapType, NullType, ErrorType {

    /** The type as it would be written in TachyonScript source, for example {@code Player?}. */
    String displayName();

    /** How values of this type are stored at runtime. */
    Representation representation();

    /** Whether {@code null} is a valid value of this type. */
    default boolean isNullable() {
        return false;
    }

    /** Whether this is the error type used by the compiler to suppress cascading diagnostics. */
    default boolean isError() {
        return false;
    }

    /** Returns the non-nullable version of this type ({@code T} for {@code T?}). */
    default Type nonNullable() {
        return this;
    }
}
