package dev.tachyonscript.engine;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.event.HandlerTable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One immutable, fully linked set of active scripts. Reloading builds a new generation and
 * swaps it in atomically; the previous one is then retired.
 */
public final class Generation {

    private final long id;
    private final Map<String, LoadedScript> scripts;
    private final HandlerTable handlers;
    private final Map<EventDeclaration, Set<Integer>> priorities;
    private final Instant created;

    Generation(long id, Map<String, LoadedScript> scripts, HandlerTable handlers, Iterable<CompiledHandler> all) {
        this.id = id;
        this.scripts = Collections.unmodifiableMap(new TreeMap<>(scripts));
        this.handlers = handlers;
        Map<EventDeclaration, Set<Integer>> used = new LinkedHashMap<>();
        for (CompiledHandler handler : all) {
            used.computeIfAbsent(handler.event(), event -> new TreeSet<>()).add(handler.priority());
        }
        used.replaceAll((event, levels) -> Collections.unmodifiableSet(levels));
        this.priorities = Collections.unmodifiableMap(used);
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

    /** Events with handlers. */
    public Set<EventDeclaration> activeEvents() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(priorities.keySet()));
    }

    /** For each event with handlers, the priorities they use. */
    public Map<EventDeclaration, Set<Integer>> activePriorities() {
        return priorities;
    }

    public Instant created() {
        return created;
    }

    /** Handlers declared by one script. */
    public long handlerCount(String path) {
        LoadedScript script = scripts.get(path);
        return script == null ? 0 : script.linked().handlers().size();
    }
}
