package dev.tachyonscript.api.declaration;

import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;

import java.util.Objects;

/**
 * A declared parameter of a native operation.
 *
 * @param name name used in documentation and diagnostics
 * @param type parameter type; never {@code void}
 */
public record Parameter(String name, Type type) {

    public Parameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!Names.isIdentifier(name)) {
            throw new IllegalArgumentException("Invalid parameter name '" + name + "'");
        }
        if (type == PrimitiveType.VOID || type.isError()) {
            throw new IllegalArgumentException("Parameter '" + name + "' cannot have type " + type.displayName());
        }
    }
}
