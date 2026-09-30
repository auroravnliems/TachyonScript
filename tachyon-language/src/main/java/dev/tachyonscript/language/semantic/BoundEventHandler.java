package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.language.source.Span;

import java.util.List;

/**
 * A type-checked event handler.
 *
 * @param event           the handled event
 * @param eventObject     the {@code event} local, which receives the platform event object
 * @param eventVariables  event variables the body actually reads, in declaration order;
 *                        only these are initialized at handler entry
 * @param body            handler body
 * @param priority        listener priority, {@code 0} (lowest) to {@code 5} (monitor); 2 is normal
 * @param ignoreCancelled whether the handler is skipped for events another handler cancelled
 * @param span            the whole {@code event ... { }} declaration
 */
public record BoundEventHandler(EventDeclaration event, LocalSymbol eventObject, List<LocalSymbol> eventVariables,
                                BoundStatement.Block body, int priority, boolean ignoreCancelled, Span span) {

    /** Priority names, in order: index = priority value. */
    public static final List<String> PRIORITIES = List.of("LOWEST", "LOW", "NORMAL", "HIGH", "HIGHEST", "MONITOR");
    public static final int NORMAL_PRIORITY = 2;

    public BoundEventHandler {
        eventVariables = List.copyOf(eventVariables);
    }
}
