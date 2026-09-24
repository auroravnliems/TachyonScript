package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

/** A name as written in source. */
public record Identifier(String name, Span span) implements Node {

    @Override
    public String toString() {
        return name;
    }
}
