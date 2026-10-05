package dev.tachyonscript.engine;

import dev.tachyonscript.compiler.CompiledModule;
import dev.tachyonscript.engine.spi.Scheduler;
import dev.tachyonscript.runtime.link.LinkedModule;
import dev.tachyonscript.runtime.interpreter.ExecutionGuard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import dev.tachyonscript.security.ScriptRevocation;

/**
 * A loaded version of one script: its compiled and linked module, and everything it started
 * while active (scheduled blocks, repeating tasks, menus, boss bars, ...). When the script is
 * reloaded or removed, this version is retired: its unload hooks run, everything it scheduled is
 * cancelled and every resource it still uses is released.
 *
 * <p>Unchanged scripts keep their loaded version across reloads, with their variables and tasks.
 */
public final class LoadedScript implements ExecutionGuard {

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
    private volatile boolean revoked;
    private final ScriptRevocation securityToken;
    private final long securityEpoch;
    private final java.util.function.IntSupplier taskLimit;
    private final java.util.function.BooleanSupplier enabled;
    private final Set<Scheduler.Handle> scheduled = ConcurrentHashMap.newKeySet();
    private final List<Scheduler.Handle> declaredTasks = new ArrayList<>();
    /**
     * Resources created by the script, held weakly (a menu nobody looks at can be collected)
     * with the action that releases them.
     */
    private final Map<Object, Consumer<Object>> resources = Collections.synchronizedMap(new WeakHashMap<>());

    LoadedScript(String path, String hash, CompiledModule compiled) {
        this(path, hash, compiled, new ScriptRevocation(false));
    }

    LoadedScript(String path, String hash, CompiledModule compiled, ScriptRevocation securityToken) {
        this(path, hash, compiled, securityToken, () -> 1024);
    }

    LoadedScript(String path, String hash, CompiledModule compiled, ScriptRevocation securityToken,
                 java.util.function.IntSupplier taskLimit) {
        this(path, hash, compiled, securityToken, taskLimit, () -> true);
    }

    LoadedScript(String path, String hash, CompiledModule compiled, ScriptRevocation securityToken,
                 java.util.function.IntSupplier taskLimit, java.util.function.BooleanSupplier enabled) {
        this.path = path;
        this.hash = hash;
        this.compiled = compiled;
        this.securityToken = securityToken;
        this.securityEpoch = securityToken.epoch();
        this.taskLimit = taskLimit;
        this.enabled = enabled;
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
        return state == State.ACTIVE && !securityRevoked();
    }

    @Override public boolean securityRevoked() { return revoked || !enabled.getAsBoolean() || securityToken.blocked() || securityToken.epoch() != securityEpoch; }
    void revokeSecurity() { revoked = true; }

    void activate() {
        if (securityRevoked()) throw new IllegalStateException("Cannot activate a security-revoked script");
        state = State.ACTIVE;
    }

    // ------------------------------------------------------------------ started work

    /** Remembers work started by the script so it is cancelled when the script is retired. */
    public synchronized void track(Scheduler.Handle handle) {
        if (state == State.RETIRED || securityRevoked()) {
            handle.cancel();
        } else {
            scheduled.add(handle);
            if (scheduled.size() + declaredTasks.size() > taskLimit.getAsInt()) {
                scheduled.remove(handle); handle.cancel();
                throw new dev.tachyonscript.api.natives.ScriptError("Per-script pending task limit exceeded.");
            }
            if ((state == State.RETIRED || securityRevoked()) && scheduled.remove(handle)) handle.cancel();
        }
    }

    public void untrack(Scheduler.Handle handle) {
        scheduled.remove(handle);
    }

    /** Number of scheduled blocks and tasks currently waiting to run. */
    public synchronized int scheduledCount() {
        return scheduled.size() + declaredTasks.size();
    }

    synchronized void declaredTask(Scheduler.Handle handle) {
        if (state == State.RETIRED || securityRevoked()) handle.cancel();
        else {
            if (scheduled.size() + declaredTasks.size() >= taskLimit.getAsInt()) {
                handle.cancel();
                throw new dev.tachyonscript.api.natives.ScriptError("Per-script pending task limit exceeded.");
            }
            declaredTasks.add(handle);
        }
    }

    /**
     * Ties a resource created by the script (a menu, a boss bar, a sidebar) to this version:
     * {@code release} runs when the version is retired, if the resource is still in use. The
     * resource is held weakly; {@code release} must not keep a reference to it.
     */
    public void own(Object resource, Consumer<Object> release) {
        if (state == State.RETIRED || securityRevoked()) {
            release.accept(resource);
            return;
        }
        synchronized (resources) {
            if (state == State.RETIRED || securityRevoked()) release.accept(resource);
            else resources.put(resource, release);
        }
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
    synchronized void retire(Consumer<RuntimeException> failures) {
        state = State.RETIRED;
        for (Scheduler.Handle handle : declaredTasks) {
            try { handle.cancel(); } catch (RuntimeException e) { failures.accept(e); }
        }
        declaredTasks.clear();
        for (Scheduler.Handle handle : List.copyOf(scheduled)) {
            try { handle.cancel(); } catch (RuntimeException e) { failures.accept(e); }
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
