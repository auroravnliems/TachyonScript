package dev.tachyonscript.engine;

import dev.tachyonscript.compiler.CompiledModule;
import dev.tachyonscript.engine.spi.Scheduler;
import dev.tachyonscript.runtime.link.LinkedModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A loaded version of one script: its compiled and linked module, and everything it started
 * while active (scheduled blocks, repeating tasks, menus, boss bars, ...). When the script is
 * reloaded or removed, this version is retired: its unload hooks run, everything it scheduled is
 * cancelled and every resource it still uses is released.
 *
 * <p>Unchanged scripts keep their loaded version across reloads, with their variables and tasks.
 */
public final class LoadedScript {

    /** Lifecycle of a loaded version. */
    public enum State {
        /** Compiled and linked, not started yet. */
        PREPARED,
        /** Initialized and running. */
        ACTIVE,
        /** Replaced or removed; nothing it started runs anymore. */
        RETIRED
    }

    private final String path;
    private final String hash;
    private final CompiledModule compiled;
    private LinkedModule linked;
    private volatile State state = State.PREPARED;
    private final Set<Scheduler.Handle> scheduled = ConcurrentHashMap.newKeySet();
    private final List<Scheduler.Handle> declaredTasks = new ArrayList<>();
    /**
     * Resources created by the script, held weakly (a menu nobody looks at can be collected)
     * with the action that releases them.
     */
    private final Map<Object, Consumer<Object>> resources = Collections.synchronizedMap(new WeakHashMap<>());

    LoadedScript(String path, String hash, CompiledModule compiled) {
        this.path = path;
        this.hash = hash;
        this.compiled = compiled;
    }

    /** Path relative to the scripts directory. */
    public String path() {
        return path;
    }

    /** Content hash of the compiled source. */
    public String hash() {
        return hash;
    }

    public CompiledModule compiled() {
        return compiled;
    }

    /** The executable module. */
    public LinkedModule linked() {
        return linked;
    }

    void link(LinkedModule module) {
        this.linked = module;
    }

    /** Module name (from the {@code module} declaration or the path). */
    public String module() {
        return compiled.name();
    }

    public State state() {
        return state;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    void activate() {
        state = State.ACTIVE;
    }

    // ------------------------------------------------------------------ started work

    /** Remembers work started by the script so it is cancelled when the script is retired. */
    public void track(Scheduler.Handle handle) {
        if (state == State.RETIRED) {
            handle.cancel();
        } else {
            scheduled.add(handle);
        }
    }

    public void untrack(Scheduler.Handle handle) {
        scheduled.remove(handle);
    }

    /** Number of scheduled blocks and tasks currently waiting to run. */
    public int scheduledCount() {
        return scheduled.size() + declaredTasks.size();
    }

    void declaredTask(Scheduler.Handle handle) {
        declaredTasks.add(handle);
    }

    /**
     * Ties a resource created by the script (a menu, a boss bar, a sidebar) to this version:
     * {@code release} runs when the version is retired, if the resource is still in use. The
     * resource is held weakly; {@code release} must not keep a reference to it.
     */
    public void own(Object resource, Consumer<Object> release) {
        if (state == State.RETIRED) {
            release.accept(resource);
            return;
        }
        resources.put(resource, release);
    }

    /** Number of resources the script still owns. */
    public int resourceCount() {
        return resources.size();
    }

    /**
     * Stops everything the script started, releases its resources and marks it retired.
     *
     * @param failures receives errors thrown by release actions (the others still run)
     */
    void retire(Consumer<RuntimeException> failures) {
        state = State.RETIRED;
        for (Scheduler.Handle handle : declaredTasks) {
            handle.cancel();
        }
        declaredTasks.clear();
        for (Scheduler.Handle handle : List.copyOf(scheduled)) {
            handle.cancel();
        }
        scheduled.clear();
        List<Map.Entry<Object, Consumer<Object>>> owned;
        synchronized (resources) {
            owned = new ArrayList<>(resources.entrySet());
            resources.clear();
        }
        for (Map.Entry<Object, Consumer<Object>> entry : owned) {
            try {
                entry.getValue().accept(entry.getKey());
            } catch (RuntimeException e) {
                failures.accept(e);
            }
        }
    }

    @Override
    public String toString() {
        return path + " (" + state + ")";
    }
}
