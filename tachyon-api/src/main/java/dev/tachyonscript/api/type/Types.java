package dev.tachyonscript.api.type;

import dev.tachyonscript.api.doc.Documentation;

import java.util.List;

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
    public static final PrimitiveType INSTANT = PrimitiveType.INSTANT;

    /** The top type of all non-null values. Primitives convert to it by boxing. */
    public static final ClassType ANY = ClassType.builder("any")
            .documentation(Documentation.of("Any non-null value."))
            .build();

    /** Immutable text. Runtime representation: {@code java.lang.String}. */
    public static final ClassType STRING = ClassType.builder("string")
            .documentation(Documentation.of("Immutable text."))
            .storable()
            .build();

    /**
     * Rich chat text. Runtime representation is chosen by the platform (Adventure's
     * {@code Component} on Paper); the compiler treats it as opaque.
     */
    public static final ClassType COMPONENT = ClassType.builder("Component")
            .documentation(Documentation.of("Formatted chat text (MiniMessage)."))
            .storable()
            .build();

    /**
     * The value of a caught error ({@code catch e { ... }}). Runtime representation:
     * {@link dev.tachyonscript.api.value.ScriptFailure}.
     */
    public static final ClassType EXCEPTION = ClassType.builder("Error")
            .documentation(Documentation.of("An error caught with try/catch: its message, kind and script location."))
            .build();

    /**
     * The runtime check behind {@code value as List<T>}: whether a value is a list. Not visible
     * to scripts by name; element types are not checked (like Java generics).
     */
    public static final ClassType LIST_VALUE = ClassType.builder("List").build();

    /** The runtime check behind {@code value as Map<K, V>}: whether a value is a map. */
    public static final ClassType MAP_VALUE = ClassType.builder("Map").build();

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

    /** {@code function(parameters): returnType}. */
    public static FunctionType function(Type returnType, Type... parameters) {
        return new FunctionType(List.of(parameters), returnType);
    }

    /** {@code function(parameters): returnType}. */
    public static FunctionType function(List<Type> parameters, Type returnType) {
        return new FunctionType(parameters, returnType);
    }
}
