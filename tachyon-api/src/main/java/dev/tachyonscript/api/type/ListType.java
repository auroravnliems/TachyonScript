package dev.tachyonscript.api.type;

import java.util.Objects;

/**
 * {@code List<T>}: an ordered collection.
 *
 * @param element element type; may be nullable, never {@code void}
 */
public record ListType(Type element) implements Type {

    public ListType {
        Objects.requireNonNull(element, "element");
        if (element == PrimitiveType.VOID) {
            throw new IllegalArgumentException("List elements cannot be void");
        }
    }

    @Override
    public String displayName() {
        return "List<" + element.displayName() + ">";
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
