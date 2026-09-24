package dev.tachyonscript.engine;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.event.HandlerTable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One immutable, fully linked set of active scripts. Reloading builds a new generation and
 * swaps it in atomically; the previous one is then retired.
 */
public final class Generation {

    private final long id;
    private final Map<String, LoadedScript> scripts;
    private final HandlerTable handlers;
    private final Set<EventDeclaration> activeEvents;
    private final Instant created;

    Generation(long id, Map<String, LoadedScript> scripts, HandlerTable handlers, Set<EventDeclaration> activeEvents) {
        this.id = id;
        this.scripts = Collections.unmodifiableMap(new TreeMap<>(scripts));
        this.handlers = handlers;
        this.activeEvents = Collections.unmodifiableSet(new LinkedHashSet<>(activeEvents));
        this.created = Instant.now();
    }

    public long id() {
        return id;
    }

    /** Active scripts by path, sorted. */
    public Map<String, LoadedScript> scripts() {
        return scripts;
    }

    public HandlerTable handlers() {
        return handlers;
    }

    public Set<EventDeclaration> activeEvents() {
        return activeEvents;
    }

    public Instant created() {
        return created;
    }

    /** Handlers declared by one script. */
    public long handlerCount(String path) {
        LoadedScript script = scripts.get(path);
        return script == null ? 0 : script.linked().handlers().size();
    }

    static Set<EventDeclaration> eventsOf(Iterable<CompiledHandler> handlers) {
        Set<EventDeclaration> events = new LinkedHashSet<>();
        for (CompiledHandler handler : handlers) {
            events.add(handler.event());
        }
        return events;
    }
}
