package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Representation;
import dev.tachyonscript.api.type.Type;

import java.util.Objects;

/**
 * A virtual register of an IR function. Every register has a fixed static type, so the
 * verifier can check each instruction's operands and the backends know how to store it.
 *
 * @param index unique index within the function
 * @param type  static type; never {@code void}
 * @param name  debug name (the source variable name, or empty for temporaries)
 */
public record Register(int index, Type type, String name) {

    public Register {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        if (type.representation() == Representation.VOID) {
            throw new IllegalArgumentException("A register cannot be void");
        }
    }

    public Representation kind() {
        return type.representation();
    }

    @Override
    public String toString() {
        return "r" + index;
    }
}
