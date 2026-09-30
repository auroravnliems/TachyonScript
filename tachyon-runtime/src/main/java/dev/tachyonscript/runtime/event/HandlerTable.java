package dev.tachyonscript.runtime.event;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.SymbolRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable table of event handlers, indexed by the registry's dense event index and by
 * priority so that dispatch is an array lookup. A new table is built for every load and
 * swapped in atomically; event threads never see a partially built table.
 */
public final class HandlerTable {

    private static final CompiledHandler[] NONE = new CompiledHandler[0];

    /** All handlers of each event, ordered by priority (lowest first), then by load order. */
    private final CompiledHandler[][] byEvent;
    /** The handlers of each event and priority. */
    private final CompiledHandler[][][] byPriority;
    private final int size;

    private HandlerTable(CompiledHandler[][] byEvent, CompiledHandler[][][] byPriority, int size) {
        this.byEvent = byEvent;
        this.byPriority = byPriority;
        this.size = size;
    }

    /** A table without handlers. */
    public static HandlerTable empty(SymbolRegistry registry) {
        return of(registry, List.of());
    }

    /** Builds a table; handlers keep their given order within each event and priority. */
    public static HandlerTable of(SymbolRegistry registry, Collection<CompiledHandler> handlers) {
        int events = registry.events().size();
        List<List<CompiledHandler>> grouped = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            grouped.add(new ArrayList<>());
        }
        for (CompiledHandler handler : handlers) {
            grouped.get(registry.eventIndex(handler.event())).add(handler);
        }
        CompiledHandler[][] table = new CompiledHandler[events][];
        CompiledHandler[][][] priorities = new CompiledHandler[events][][];
        for (int i = 0; i < events; i++) {
            List<CompiledHandler> list = grouped.get(i);
            list.sort(Comparator.comparingInt(CompiledHandler::priority));
            table[i] = list.isEmpty() ? NONE : list.toArray(CompiledHandler[]::new);
            CompiledHandler[][] levels = new CompiledHandler[CompiledHandler.PRIORITIES][];
            Arrays.fill(levels, NONE);
            for (int level = 0; level < CompiledHandler.PRIORITIES; level++) {
                int priority = level;
                CompiledHandler[] matching = list.stream().filter(h -> h.priority() == priority)
                        .toArray(CompiledHandler[]::new);
                levels[level] = matching.length == 0 ? NONE : matching;
            }
            priorities[i] = levels;
        }
        return new HandlerTable(table, priorities, handlers.size());
    }

    /** All handlers of the event with the given registry index, lowest priority first. Must not be modified. */
    public CompiledHandler[] handlers(int eventIndex) {
        return byEvent[eventIndex];
    }

    /** The handlers of one event at one priority (0 lowest to 5 monitor). Must not be modified. */
    public CompiledHandler[] handlers(int eventIndex, int priority) {
        return byPriority[eventIndex][priority];
    }

    public CompiledHandler[] handlers(SymbolRegistry registry, EventDeclaration event) {
        return byEvent[registry.eventIndex(event)];
    }

    /** Whether the event has handlers at the given priority. */
    public boolean has(int eventIndex, int priority) {
        return byPriority[eventIndex][priority].length > 0;
    }

    /** Total number of handlers. */
    public int size() {
        return size;
    }
}
