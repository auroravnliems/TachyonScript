package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

import java.util.List;
import java.util.stream.Collectors;

/** A dotted name such as {@code player.join} or {@code economy.currency}. */
public record QualifiedName(List<Identifier> parts, Span span) implements Node {

    public QualifiedName {
        parts = List.copyOf(parts);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("A qualified name needs at least one part");
        }
    }

    /** The name with dots, e.g. {@code player.join}. */
    public String text() {
        return parts.stream().map(Identifier::name).collect(Collectors.joining("."));
    }

    public Identifier last() {
        return parts.getLast();
    }

    @Override
    public String toString() {
        return text();
    }
}
