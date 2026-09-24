package dev.tachyonscript.language.syntax;

import dev.tachyonscript.language.source.Span;

import java.util.List;

/** A type as written in source, before resolution. */
public sealed interface TypeRef extends Node {

    /** {@code Player}, {@code List<string>}, {@code Map<string, int>}. */
    record Named(QualifiedName name, List<TypeRef> arguments, Span span) implements TypeRef {
        public Named {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code T?} */
    record Nullable(TypeRef inner, Span span) implements TypeRef {
    }
}
