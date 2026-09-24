package dev.tachyonscript.language.semantic;

import dev.tachyonscript.language.source.Span;

import java.util.List;

/** A type-checked script function. */
public record BoundFunction(FunctionSymbol symbol, List<LocalSymbol> parameters, BoundStatement.Block body, Span span) {

    public BoundFunction {
        parameters = List.copyOf(parameters);
    }
}
