package dev.tachyonscript.runtime.event;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.SymbolRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Immutable table of event handlers, indexed by the registry's dense event index so that
 * dispatch is an array lookup. A new table is built for every load and swapped in
 * atomically; event threads never see a partially built table.
 */
public final class HandlerTable {

    private static final CompiledHandler[] NONE = new CompiledHandler[0];

    private final CompiledHandler[][] byEvent;
    private final int size;

    private HandlerTable(CompiledHandler[][] byEvent, int size) {
        this.byEvent = byEvent;
        this.size = size;
    }

    /** A table without handlers. */
    public static HandlerTable empty(SymbolRegistry registry) {
        CompiledHandler[][] table = new CompiledHandler[registry.events().size()][];
        java.util.Arrays.fill(table, NONE);
        return new HandlerTable(table, 0);
    }

    /** Builds a table; handlers keep their given order within each event. */
    public static HandlerTable of(SymbolRegistry registry, Collection<CompiledHandler> handlers) {
        List<List<CompiledHandler>> grouped = new ArrayList<>();
        for (int i = 0; i < registry.events().size(); i++) {
            grouped.add(new ArrayList<>());
        }
        for (CompiledHandler handler : handlers) {
            grouped.get(registry.eventIndex(handler.event())).add(handler);
        }
        CompiledHandler[][] table = new CompiledHandler[grouped.size()][];
        for (int i = 0; i < table.length; i++) {
            table[i] = grouped.get(i).isEmpty() ? NONE : grouped.get(i).toArray(CompiledHandler[]::new);
        }
        return new HandlerTable(table, handlers.size());
    }

    /** Handlers of the event with the given registry index. The returned array must not be modified. */
    public CompiledHandler[] handlers(int eventIndex) {
        return byEvent[eventIndex];
    }

    public CompiledHandler[] handlers(SymbolRegistry registry, EventDeclaration event) {
        return byEvent[registry.eventIndex(event)];
    }

    /** Total number of handlers. */
    public int size() {
        return size;
    }
}
