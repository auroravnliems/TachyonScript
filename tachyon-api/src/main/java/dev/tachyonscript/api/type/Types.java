package dev.tachyonscript.api.type;

import dev.tachyonscript.api.doc.Documentation;

/**
 * Built-in types known to the language itself, and factories for structural types.
 *
 * <p>Domain types such as {@code Player} are not defined here; they are declared by the
 * standard library or by addons and registered in the symbol registry.
 */
public final class Types {

    public static final PrimitiveType INT = PrimitiveType.INT;
    public static final PrimitiveType LONG = PrimitiveType.LONG;
    public static final PrimitiveType FLOAT = PrimitiveType.FLOAT;
    public static final PrimitiveType DOUBLE = PrimitiveType.DOUBLE;
    public static final PrimitiveType BOOL = PrimitiveType.BOOL;
    public static final PrimitiveType VOID = PrimitiveType.VOID;
    public static final PrimitiveType DURATION = PrimitiveType.DURATION;

    /** The top type of all non-null values. Primitives convert to it by boxing. */
    public static final ClassType ANY = ClassType.builder("any")
            .documentation(Documentation.of("Any non-null value."))
            .build();

    /** Immutable text. Runtime representation: {@code java.lang.String}. */
    public static final ClassType STRING = ClassType.builder("string")
            .documentation(Documentation.of("Immutable text."))
            .build();

    /**
     * Rich chat text. Runtime representation is chosen by the platform (Adventure's
     * {@code Component} on Paper); the compiler treats it as opaque.
     */
    public static final ClassType COMPONENT = ClassType.builder("Component")
            .documentation(Documentation.of("Formatted chat text (MiniMessage)."))
            .build();

    public static final NullType NULL = NullType.INSTANCE;
    public static final ErrorType ERROR = ErrorType.INSTANCE;

    private Types() {
    }

    /** Returns {@code T?}. Nullable types and {@code null} are returned unchanged. */
    public static Type nullable(Type type) {
        if (type.isNullable() || type.isError()) {
            return type;
        }
        return new NullableType(type);
    }

    public static ListType list(Type element) {
        return new ListType(element);
    }

    public static MapType map(Type key, Type value) {
        return new MapType(key, value);
    }
}
