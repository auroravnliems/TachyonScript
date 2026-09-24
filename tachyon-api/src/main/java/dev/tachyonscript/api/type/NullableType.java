package dev.tachyonscript.api.type;

import java.util.Objects;

/**
 * {@code T?}: a value of {@code T} or {@code null}. Nullable primitives are stored boxed.
 *
 * @param inner the non-nullable type; never itself nullable, {@code void} or an error type
 */
public record NullableType(Type inner) implements Type {

    public NullableType {
        Objects.requireNonNull(inner, "inner");
        if (inner.isNullable() || inner == PrimitiveType.VOID || inner.isError()) {
            throw new IllegalArgumentException("Cannot make '" + inner.displayName() + "' nullable");
        }
    }

    @Override
    public String displayName() {
        return inner.displayName() + "?";
    }

    @Override
    public Representation representation() {
        return Representation.REF;
    }

    @Override
    public boolean isNullable() {
        return true;
    }

    @Override
    public Type nonNullable() {
        return inner;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
