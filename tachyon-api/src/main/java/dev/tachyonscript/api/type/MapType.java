package dev.tachyonscript.api.type;

import java.util.Objects;

/**
 * {@code Map<K, V>}: a key/value collection.
 *
 * @param key   key type; never nullable or {@code void}
 * @param value value type; may be nullable, never {@code void}
 */
public record MapType(Type key, Type value) implements Type {

    public MapType {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key == PrimitiveType.VOID || value == PrimitiveType.VOID) {
            throw new IllegalArgumentException("Map keys and values cannot be void");
        }
        if (key.isNullable()) {
            throw new IllegalArgumentException("Map keys cannot be nullable");
        }
    }

    @Override
    public String displayName() {
        return "Map<" + key.displayName() + ", " + value.displayName() + ">";
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
