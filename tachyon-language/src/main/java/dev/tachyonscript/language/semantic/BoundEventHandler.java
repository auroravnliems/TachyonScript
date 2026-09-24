package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.language.source.Span;

import java.util.List;

/**
 * A type-checked event handler.
 *
 * @param event          the handled event
 * @param eventObject    the {@code event} local, which receives the platform event object
 * @param eventVariables event variables the body actually reads, in declaration order;
 *                       only these are initialized at handler entry
 * @param body           handler body
 * @param span           the whole {@code event ... { }} declaration
 */
public record BoundEventHandler(EventDeclaration event, LocalSymbol eventObject, List<LocalSymbol> eventVariables,
                                BoundStatement.Block body, Span span) {

    public BoundEventHandler {
        eventVariables = List.copyOf(eventVariables);
    }
}
