package dev.tachyonscript.api.type;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * {@code function(A, B): R}: a function value, such as a lambda ({@code x => x * 2}), a
 * reference to a script function, or a block scheduled with {@code after}.
 *
 * <p>Function types are structural and compared by value. A value of a function type is
 * called like a function ({@code f(1, 2)}); natives receive it as a
 * {@link dev.tachyonscript.api.natives.ScriptFunction}.
 *
 * @param parameters parameter types, in order; never {@code void}
 * @param returnType result type; {@code void} for functions without a result
 */
public record FunctionType(List<Type> parameters, Type returnType) implements Type {

    public FunctionType {
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(returnType, "returnType");
        for (Type parameter : parameters) {
            if (parameter == PrimitiveType.VOID) {
                throw new IllegalArgumentException("Function parameters cannot be void");
            }
        }
    }

    public int arity() {
        return parameters.size();
    }

    @Override
    public String displayName() {
        StringJoiner joiner = new StringJoiner(", ", "function(", ")");
        for (Type parameter : parameters) {
            joiner.add(parameter.displayName());
        }
        return joiner + ": " + returnType.displayName();
    }

    @Override
    public Representation representation() {
        return Representation.REF;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
