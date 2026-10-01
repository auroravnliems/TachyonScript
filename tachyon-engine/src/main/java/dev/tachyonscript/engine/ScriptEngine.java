package dev.tachyonscript.engine;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.CompilationTimings;
import dev.tachyonscript.compiler.CompiledModule;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.database.DatabaseBindings;
import dev.tachyonscript.engine.database.Databases;
import dev.tachyonscript.engine.profile.Profiler;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.engine.spi.Scheduler;
import dev.tachyonscript.engine.storage.DataStore;
import dev.tachyonscript.engine.storage.MemoryBackend;
import dev.tachyonscript.engine.storage.StorageBackend;
import dev.tachyonscript.engine.storage.ValueCodec;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.semantic.BoundFunction;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.semantic.BoundPlaceholder;
import dev.tachyonscript.language.semantic.BoundTask;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.event.HandlerTable;
import dev.tachyonscript.runtime.interpreter.Closure;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.ExecutionStack;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.runtime.link.LinkException;
import dev.tachyonscript.runtime.link.LinkedModule;
import dev.tachyonscript.runtime.link.Linker;
import dev.tachyonscript.runtime.value.GlobalCell;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads scripts and runs them.
 *
 * <p><b>Loading is transactional.</b> A load reads every script, recompiles the ones whose
 * content changed (and the ones importing a changed module), links them in import order, and
 * only when that succeeded (per the {@link LoadMode}) activates them. A script that fails keeps
 * its previous working version active; nothing that worked is ever replaced by something that
 * failed. Loads are serialized; dispatch never blocks.
 *
 * <p><b>Activation</b> runs on the global thread: replaced versions are retired (their
 * {@code on unload} blocks run, everything they started is cancelled and their saved variables
 * are kept), new versions initialize their top-level variables and run {@code on load}, their
 * tasks start, the command tree is rebuilt, and the new {@link Generation} is swapped in with
 * a single volatile write.
 *
 * <p><b>Dispatch</b> reads the current generation once and runs its handlers for the event
 * on the calling thread (which, on Folia, is the thread owning the event's entity or region).
 * A failing handler is reported and the remaining handlers still run.
 */
public final class ScriptEngine {

    private static final long SLOW_REPORT_INTERVAL_NANOS = 30_000_000_000L;
    private static final long ACTIVATION_TIMEOUT_SECONDS = 120;

    private final SymbolRegistry registry;
    private final Platform platform;
    private final EngineOptions options;
    private final Compiler compiler;
    private final ErrorReporter errors;
    private final Profiler profiler = new Profiler();
    private final Bindings bindings;
    private final DataStore data;
    private final Databases databases;
    private final CommandManager commands;
    private final ReentrantLock loadLock = new ReentrantLock();
    private final Map<CompiledFunction, Long> slowReported = new ConcurrentHashMap<>();
    private volatile Generation current;
    private volatile boolean timed;
    private volatile Map<String, Placeholder> placeholders = Map.of();
    private long nextGeneration = 1;
    private boolean closed;

    /** A placeholder declared by a script. */
    private record Placeholder(LoadedScript script, CompiledFunction function) {
    }

    public ScriptEngine(SymbolRegistry registry, Platform platform, EngineOptions options,
                        InternalErrorHandler internalErrors) {
        this.registry = registry;
        this.platform = platform;
        this.options = options;
        this.compiler = new Compiler(registry, options.compiler(), internalErrors);
        this.errors = new ErrorReporter(platform.logger(), options.debug());
        this.databases = new Databases(options.databases(), options.databaseFolder());
        this.bindings = Bindings.builder().include(platform.bindings()).include(EngineIntrinsics.create(this))
                .include(DatabaseBindings.create(this, databases)).build();
        ValueDisplays.install(registry, bindings);
        StorageBackend backend = options.storage() != null ? options.storage() : new MemoryBackend();
        this.data = new DataStore(backend, new ValueCodec(bindings), platform.logger(), options.flushIntervalMillis());
        this.commands = new CommandManager(this, platform, options.messages());
        this.current = new Generation(0, Map.of(), HandlerTable.empty(registry), List.of());
        this.timed = options.slowThresholdNanos() > 0;
        ExecutionStack.configure(options.limits());
    }

    public SymbolRegistry registry() {
        return registry;
    }

    /** The active generation. */
    public Generation generation() {
        return current;
    }

    public Profiler profiler() {
        return profiler;
    }

    public ErrorReporter errors() {
        return errors;
    }

    /** Saved variables of all scripts. */
    /** The databases scripts open with {@code Database(...)}. */
    public Databases databases() {
        return databases;
    }

    public DataStore data() {
        return data;
    }

    /** The platform bindings plus the engine's own operations (scheduling). */
    public Bindings bindings() {
        return bindings;
    }

    public Platform platform() {
        return platform;
    }

    public void startProfiling() {
        profiler.start();
        timed = true;
    }

    public void stopProfiling() {
        profiler.stop();
        timed = options.slowThresholdNanos() > 0;
    }

    /** Replaces the messages sent by script commands (after a configuration reload). */
    public void commandMessages(CommandMessages messages) {
        commands.messages(messages);
    }

    // =================================================================== loading

    /** Loads all scripts from {@code source}, reusing unchanged ones. */
    public LoadReport load(ScriptSource source) {
        return load(source, Set.of());
    }

    /**
     * Loads all scripts from {@code source}; scripts whose path is in {@code forceRecompile}
     * are recompiled even if unchanged.
     */
    public LoadReport load(ScriptSource source, Set<String> forceRecompile) {
        long start = System.nanoTime();
        boolean onGlobal = platform.scheduler().isGlobalThread();
        try {
            // The global thread must never wait for a load that waits for the global thread.
            if (onGlobal ? !loadLock.tryLock() : !loadLock.tryLock(ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return failure(current, start, "Another load is still running; try again in a moment.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(current, start, "The load was interrupted.");
        }
        try {
            return loadLocked(source, forceRecompile, start);
        } finally {
            loadLock.unlock();
        }
    }

    private LoadReport loadLocked(ScriptSource source, Set<String> forceRecompile, long start) {
        Generation previous = current;
        if (closed) {
            return failure(previous, start, "The engine has been shut down.");
        }
        List<SourceFile> files;
        try {
            files = source.read();
        } catch (IOException | RuntimeException e) {
            return failure(previous, start, "Cannot read scripts: " + e.getMessage());
        }
        Map<String, SourceFile> byPath = new LinkedHashMap<>();
        files.stream().sorted(Comparator.comparing(SourceFile::path)).forEach(file -> byPath.put(file.path(), file));
        Map<String, LoadedScript> previousScripts = previous.scripts();

        // 1. What changes: new or edited files, forced ones, and every script importing a module that changes.
        Set<String> changed = new TreeSet<>();
        for (SourceFile file : byPath.values()) {
            LoadedScript old = previousScripts.get(file.path());
            if (old == null || !old.hash().equals(file.hash()) || forceRecompile.contains(file.path())) {
                changed.add(file.path());
            }
        }
        Set<String> changingModules = new HashSet<>();
        for (Map.Entry<String, LoadedScript> entry : previousScripts.entrySet()) {
            if (changed.contains(entry.getKey()) || !byPath.containsKey(entry.getKey())) {
                changingModules.add(entry.getValue().module());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String path : byPath.keySet()) {
                LoadedScript old = previousScripts.get(path);
                if (changed.contains(path) || old == null || old.compiled().bound() == null) {
                    continue;
                }
                for (String imported : old.compiled().bound().imports()) {
                    if (changingModules.contains(imported)) {
                        changed.add(path);
                        changingModules.add(old.module());
                        grew = true;
                        break;
                    }
                }
            }
        }
        Map<String, LoadedScript> reused = new LinkedHashMap<>();
        Map<String, BoundModule> available = new HashMap<>();
        for (String path : byPath.keySet()) {
            if (!changed.contains(path)) {
                LoadedScript old = previousScripts.get(path);
                reused.put(path, old);
                available.put(old.module(), old.compiled().bound());
            }
        }

        // 2. Compile the changed scripts against the unchanged ones.
        List<SourceFile> toCompile = changed.stream().map(byPath::get).toList();
        CompilationResult result = compiler.compile(toCompile, available);
        List<String> failed = new ArrayList<>();
        Map<String, List<String>> linkProblems = new LinkedHashMap<>();
        List<CompiledModule> succeeded = new ArrayList<>();
        for (CompiledModule module : result.modules()) {
            if (module.succeeded()) {
                succeeded.add(module);
            } else {
                failed.add(module.file().path());
            }
        }

        // 3. Link in import order.
        Map<String, LoadedScript> byModule = new HashMap<>();
        reused.values().forEach(script -> byModule.put(script.module(), script));
        List<LoadedScript> prepared = new ArrayList<>();
        for (CompiledModule module : importOrder(succeeded)) {
            String path = module.file().path();
            LoadedScript script = new LoadedScript(path, module.file().hash(), module);
            ModuleEnvironment environment = new ModuleEnvironment(script, byModule::get, data, platform.players());
            try {
                LinkedModule linked = Linker.link(Assembler.assemble(module.ir()), bindings, platform.text(), environment);
                script.link(linked);
                prepared.add(script);
                byModule.put(script.module(), script);
            } catch (LinkException e) {
                failed.add(path);
                linkProblems.put(path, e.problems());
            } catch (RuntimeException e) {
                failed.add(path);
                linkProblems.put(path, List.of("Internal error while loading: " + e));
            }
        }

        List<Diagnostic> diagnostics = result.diagnostics().sorted();
        if (!failed.isEmpty() && options.mode() == LoadMode.STRICT) {
            return new LoadReport(false, previous.id(), previous.scripts().size(), changed.size(), reused.size(), failed,
                    List.of(), previous.handlers().size(), diagnostics, linkProblems, result.timings(),
                    System.nanoTime() - start, null);
        }
        Map<String, LoadedScript> next = new LinkedHashMap<>(reused);
        prepared.forEach(script -> next.put(script.path(), script));
        List<String> keptPrevious = new ArrayList<>();
        for (String path : failed) {
            LoadedScript old = previousScripts.get(path);
            if (old != null && byPath.containsKey(path)) {
                next.put(path, old);
                keptPrevious.add(path);
            }
        }
        List<LoadedScript> retiring = new ArrayList<>();
        for (LoadedScript old : previousScripts.values()) {
            if (next.get(old.path()) != old) {
                retiring.add(old);
            }
        }
        Generation generation = build(next);
        activate(generation, retiring, prepared);
        return new LoadReport(true, generation.id(), generation.scripts().size(), changed.size(), reused.size(), failed,
                keptPrevious, generation.handlers().size(), diagnostics, linkProblems, result.timings(),
                System.nanoTime() - start, null);
    }

    private LoadReport failure(Generation previous, long start, String message) {
        return new LoadReport(false, previous.id(), previous.scripts().size(), 0, 0, List.of(), List.of(),
                previous.handlers().size(), List.of(), Map.of(), emptyTimings(), System.nanoTime() - start, message);
    }

    /** Modules ordered so that each comes after the modules it imports. */
    private static List<CompiledModule> importOrder(List<CompiledModule> modules) {
        Map<String, CompiledModule> byName = new LinkedHashMap<>();
        modules.forEach(module -> byName.put(module.name(), module));
        List<CompiledModule> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (CompiledModule module : modules) {
            visit(module, byName, done, order);
        }
        return order;
    }

    private static void visit(CompiledModule module, Map<String, CompiledModule> byName, Set<String> done,
                              List<CompiledModule> order) {
        if (!done.add(module.name())) {
            return;
        }
        for (String imported : module.bound().imports()) {
            CompiledModule dependency = byName.get(imported);
            if (dependency != null) {
                visit(dependency, byName, done, order);
            }
        }
        order.add(module);
    }

    private Generation build(Map<String, LoadedScript> scripts) {
        List<CompiledHandler> handlers = new ArrayList<>();
        // Deterministic order: by script path, then declaration order.
        scripts.keySet().stream().sorted().forEach(path -> handlers.addAll(scripts.get(path).linked().handlers()));
        return new Generation(nextGeneration++, scripts, HandlerTable.of(registry, handlers), handlers);
    }

    // =================================================================== activation

    private void activate(Generation generation, List<LoadedScript> retiring, List<LoadedScript> starting) {
        onGlobalThread(() -> {
            errors.clear();
            slowReported.clear();
            for (int i = retiring.size() - 1; i >= 0; i--) {
                retire(retiring.get(i));
            }
            for (LoadedScript script : starting) {
                script.activate();
                script.linked().function("$init").ifPresent(init -> runSafely(script, init));
            }
            for (LoadedScript script : starting) {
                for (BoundFunction hook : script.compiled().bound().loadHooks()) {
                    script.linked().function(hook.key()).ifPresent(function -> runSafely(script, function));
                }
            }
            for (LoadedScript script : starting) {
                startTasks(script);
            }
            current = generation;
            for (String warning : commands.update(generation.scripts().values())) {
                platform.logger().warn(warning);
            }
            placeholders = collectPlaceholders(generation);
            platform.events().activeEventsChanged(generation.activePriorities());
            if (!retiring.isEmpty()) {
                data.flushLater();
            }
        });
    }

    /** Runs {@code unload} hooks, stops everything the script started and keeps its saved variables. */
    private void retire(LoadedScript script) {
        if (script.state() == LoadedScript.State.RETIRED) {
            return;
        }
        if (script.isActive()) {
            for (BoundFunction hook : script.compiled().bound().unloadHooks()) {
                script.linked().function(hook.key()).ifPresent(function -> runSafely(script, function));
            }
        }
        script.retire(error -> platform.logger().warn("Cannot release a resource of " + script.path() + ": " + error));
        for (GlobalCell cell : script.linked().globals().values()) {
            if (cell.ref().storage() == GlobalRef.Storage.PERSISTENT) {
                data.release(cell);
            }
        }
    }

    private void startTasks(LoadedScript script) {
        for (BoundTask task : script.compiled().bound().tasks()) {
            CompiledFunction function = script.linked().function(task.function().key()).orElse(null);
            if (function == null) {
                continue;
            }
            Runnable run = () -> {
                if (script.isActive()) {
                    runSafely(script, function);
                }
            };
            Scheduler scheduler = platform.scheduler();
            if (task.intervalMillis() > 0) {
                script.declaredTask(task.async()
                        ? scheduler.runAsyncRepeating(task.intervalMillis(), task.intervalMillis(), run)
                        : scheduler.runRepeating(ticks(task.intervalMillis()), ticks(task.intervalMillis()), run));
            } else {
                script.declaredTask(new DailyTask(scheduler, task.dailyMinute(), task.async(), run).start());
            }
        }
    }

    /** An {@code at "HH:mm"} task: runs every day at that time (server time zone), rescheduling after each run. */
    private static final class DailyTask implements Scheduler.Handle {
        private final Scheduler scheduler;
        private final int minuteOfDay;
        private final boolean async;
        private final Runnable body;
        private volatile Scheduler.Handle next;
        private volatile boolean cancelled;

        DailyTask(Scheduler scheduler, int minuteOfDay, boolean async, Runnable body) {
            this.scheduler = scheduler;
            this.minuteOfDay = minuteOfDay;
            this.async = async;
            this.body = body;
        }

        DailyTask start() {
            schedule();
            return this;
        }

        private void schedule() {
            if (cancelled) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            LocalDateTime target = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay);
            if (!target.isAfter(now)) {
                target = target.plusDays(1);
            }
            long delay = java.time.Duration.between(now, target).toMillis();
            Runnable run = () -> {
                if (!cancelled) {
                    body.run();
                    schedule();
                }
            };
            next = async ? scheduler.runAsyncLater(delay, run) : scheduler.runLater(ticks(delay), run);
        }

        @Override
        public void cancel() {
            cancelled = true;
            Scheduler.Handle current = next;
            if (current != null) {
                current.cancel();
            }
        }
    }

    private Map<String, Placeholder> collectPlaceholders(Generation generation) {
        Map<String, Placeholder> result = new LinkedHashMap<>();
        for (LoadedScript script : generation.scripts().values()) {
            for (BoundPlaceholder placeholder : script.compiled().bound().placeholders()) {
                CompiledFunction function = script.linked().function(placeholder.function().key()).orElse(null);
                if (function == null) {
                    continue;
                }
                Placeholder existing = result.putIfAbsent(placeholder.name(), new Placeholder(script, function));
                if (existing != null) {
                    platform.logger().warn("Placeholder '" + placeholder.name() + "' is declared by both "
                            + existing.script().path() + " and " + script.path() + "; the one in "
                            + existing.script().path() + " is used.");
                }
            }
        }
        return result;
    }

    /** Runs a function of a script, reporting script errors (never throws). */
    Object runSafely(LoadedScript script, CompiledFunction function, Object... arguments) {
        long start = timed ? System.nanoTime() : 0;
        try {
            return Interpreter.call(function, arguments);
        } catch (ScriptRuntimeException error) {
            errors.report(script.path(), error);
        } catch (RuntimeException error) {
            platform.logger().error("Internal error while running " + function.displayName() + " of " + script.path()
                    + ": " + error);
        } finally {
            if (start != 0) {
                measured(function, start);
            }
        }
        return null;
    }

    /** Whether executions are being timed (for the profiler or the slow-execution warning). */
    boolean timed() {
        return timed;
    }

    /**
     * Records one execution that started at {@code start} ({@link System#nanoTime()}): for the
     * profiler, and for the slow-execution warning when it is an outermost execution.
     */
    void measured(CompiledFunction function, long start) {
        long elapsed = System.nanoTime() - start;
        if (profiler.isEnabled()) {
            profiler.record(function, elapsed);
        }
        long threshold = options.slowThresholdNanos();
        // Only outermost executions: nested ones are part of their caller's time.
        if (threshold > 0 && elapsed > threshold && ExecutionStack.current().depth() == 0) {
            reportSlow(function, elapsed);
        }
    }

    private void onGlobalThread(Runnable action) {
        Scheduler scheduler = platform.scheduler();
        if (scheduler.isGlobalThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        RuntimeException[] failure = new RuntimeException[1];
        scheduler.runGlobal(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                failure[0] = e;
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                platform.logger().error("The server thread did not run the script activation within "
                        + ACTIVATION_TIMEOUT_SECONDS + " seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    /** Deactivates every script (plugin shutdown) and writes the saved variables. Later loads are refused. */
    public void shutdown() {
        loadLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            Generation last = current;
            onGlobalThread(() -> {
                List<LoadedScript> scripts = new ArrayList<>(last.scripts().values());
                for (int i = scripts.size() - 1; i >= 0; i--) {
                    retire(scripts.get(i));
                }
                current = new Generation(nextGeneration++, Map.of(), HandlerTable.empty(registry), List.of());
                placeholders = Map.of();
                try {
                    commands.update(List.of());
                } catch (RuntimeException e) {
                    platform.logger().warn("Cannot unregister script commands: " + e.getMessage());
                }
                platform.events().activeEventsChanged(Map.of());
            });
        } finally {
            loadLock.unlock();
            data.close();
            databases.close();
        }
    }

    // =================================================================== dispatch

    /** Runs every handler of the event with registry index {@code eventIndex}, lowest priority first. */
    public void dispatch(int eventIndex, Object event) {
        run(current.handlers().handlers(eventIndex), event);
    }

    /** Runs the handlers of one priority (0 lowest to 5 monitor) of an event. */
    public void dispatch(int eventIndex, int priority, Object event) {
        run(current.handlers().handlers(eventIndex, priority), event);
    }

    public void dispatch(EventDeclaration declaration, Object event) {
        dispatch(registry.eventIndex(declaration), event);
    }

    private void run(CompiledHandler[] handlers, Object event) {
        if (handlers.length == 0) {
            return;
        }
        if (timed) {
            dispatchTimed(handlers, event);
            return;
        }
        for (CompiledHandler handler : handlers) {
            if (handler.ignoreCancelled() && platform.events().isCancelled(event)) {
                continue;
            }
            try {
                Interpreter.invokeHandler(handler.function(), event);
            } catch (ScriptRuntimeException error) {
                errors.report(handler, error);
            }
        }
    }

    private void dispatchTimed(CompiledHandler[] handlers, Object event) {
        for (CompiledHandler handler : handlers) {
            if (handler.ignoreCancelled() && platform.events().isCancelled(event)) {
                continue;
            }
            long start = System.nanoTime();
            try {
                Interpreter.invokeHandler(handler.function(), event);
            } catch (ScriptRuntimeException error) {
                errors.report(handler, error);
            }
            measured(handler.function(), start);
        }
    }

    private void reportSlow(CompiledFunction function, long elapsed) {
        long now = System.nanoTime();
        Long last = slowReported.get(function);
        if (last != null && now - last < SLOW_REPORT_INTERVAL_NANOS) {
            return;
        }
        slowReported.put(function, now);
        var source = function.source();
        int line = source.line(dev.tachyonscript.ir.Spans.start(function.unit().span()));
        platform.logger().warn(String.format(Locale.ROOT, "Slow script execution: %s:%d, %s, %.2f ms",
                source.path(), line, function.displayName(), elapsed / 1e6));
    }

    /** Paths of scripts in the active generation that handle {@code event}. */
    public Set<String> handlersOf(EventDeclaration event) {
        Set<String> scripts = new HashSet<>();
        for (CompiledHandler handler : current.handlers().handlers(registry, event)) {
            scripts.add(handler.function().source().path());
        }
        return scripts;
    }

    // =================================================================== scheduling (engine intrinsics)

    /** The script that created a block, if it is still loaded. */
    private static LoadedScript owner(Closure block) {
        return block.owner() instanceof LoadedScript script && script.state() != LoadedScript.State.RETIRED ? script : null;
    }

    /** Server ticks for a delay: at least one, rounded up. */
    static long ticks(long millis) {
        return Math.max(1, (millis + 49) / 50);
    }

    void after(Object entity, long delayMillis, Closure block) {
        LoadedScript script = owner(block);
        if (script == null) {
            return;
        }
        Scheduler.Handle[] self = new Scheduler.Handle[1];
        Runnable run = () -> {
            Scheduler.Handle handle = self[0];
            if (handle != null) {
                script.untrack(handle);
            }
            if (script.isActive()) {
                runBlock(script, block);
            }
        };
        Scheduler scheduler = platform.scheduler();
        long ticks = ticks(delayMillis);
        Scheduler.Handle handle = entity == null ? scheduler.runLater(ticks, run) : scheduler.runLaterFor(entity, ticks, run);
        self[0] = handle;
        script.track(handle);
    }

    void every(Object entity, long intervalMillis, Closure block) {
        LoadedScript script = owner(block);
        if (script == null) {
            return;
        }
        ScheduledScriptTask task = new ScheduledScriptTask(script, block.describe());
        Runnable run = () -> {
            if (task.beginRun()) {
                runBlock(script, block, task);
            }
        };
        Scheduler scheduler = platform.scheduler();
        long ticks = ticks(intervalMillis);
        task.start(entity == null ? scheduler.runRepeating(ticks, ticks, run)
                : scheduler.runRepeatingFor(entity, ticks, ticks, run));
    }

    void async(Closure block) {
        LoadedScript script = owner(block);
        if (script == null) {
            return;
        }
        platform.scheduler().runAsync(() -> {
            if (script.isActive()) {
                runBlock(script, block);
            }
        });
    }

    void sync(Closure block) {
        LoadedScript script = owner(block);
        if (script == null) {
            return;
        }
        platform.scheduler().runGlobal(() -> {
            if (script.isActive()) {
                runBlock(script, block);
            }
        });
    }

    // =================================================================== resources and callbacks

    /**
     * The loaded script whose code is running on the calling thread (the script of the event
     * handler, command, task or callback being executed), or {@code null} outside scripts.
     */
    public static LoadedScript runningScript() {
        return ExecutionStack.current().owner() instanceof LoadedScript script ? script : null;
    }

    /**
     * Ties a resource created by the running script (a menu, a boss bar, a sidebar) to that
     * script: {@code release} runs when the script is reloaded or unloaded, if the resource is
     * still in use. The resource is held weakly; {@code release} must not reference it.
     *
     * @return whether a script was running (otherwise nothing is tracked)
     */
    public static boolean ownResource(Object resource, java.util.function.Consumer<Object> release) {
        LoadedScript script = runningScript();
        if (script == null) {
            return false;
        }
        script.own(resource, release);
        return true;
    }

    /**
     * Calls a function value from platform code that runs outside any script, such as a menu
     * click or the end of a web request: runs it on the calling thread if its script is still
     * loaded, reporting script errors.
     *
     * @return the function's result, or {@code null} if its script was unloaded or it failed
     */
    public Object callback(ScriptFunction function, Object... arguments) {
        if (!(function instanceof Closure closure)) {
            return function.invokeWithArguments(arguments);
        }
        LoadedScript script = owner(closure);
        if (script == null || !script.isActive()) {
            return null;
        }
        long start = timed ? System.nanoTime() : 0;
        try {
            return Interpreter.callClosure(closure, arguments);
        } catch (ScriptRuntimeException error) {
            errors.report(script.path(), error);
        } catch (RuntimeException error) {
            platform.logger().error("Internal error while running " + closure.describe() + ": " + error);
        } finally {
            if (start != 0) {
                measured(closure.function(), start);
            }
        }
        return null;
    }

    /** Whether the script that created a function value is still loaded. */
    public static boolean isLoaded(ScriptFunction function) {
        return !(function instanceof Closure closure) || owner(closure) != null;
    }

    private void runBlock(LoadedScript script, Closure block, Object... arguments) {
        long start = timed ? System.nanoTime() : 0;
        try {
            Interpreter.callClosure(block, arguments);
        } catch (ScriptRuntimeException error) {
            errors.report(script.path(), error);
        } catch (RuntimeException error) {
            platform.logger().error("Internal error while running " + block.describe() + ": " + error);
        } finally {
            if (start != 0) {
                measured(block.function(), start);
            }
        }
    }

    // =================================================================== placeholders and players

    /** Names of the placeholders declared by the active scripts. */
    public Set<String> placeholderNames() {
        return placeholders.keySet();
    }

    /**
     * The text of placeholder {@code identifier} ({@code name} or {@code name_argument}) for a
     * player (a player or offline player object, or null), or {@code null} if no script
     * declares it.
     */
    public String placeholder(String identifier, Object player) {
        Map<String, Placeholder> active = placeholders;
        Placeholder found = active.get(identifier);
        String argument = "";
        if (found == null) {
            String best = null;
            for (String name : active.keySet()) {
                if (identifier.startsWith(name + "_") && (best == null || name.length() > best.length())) {
                    best = name;
                }
            }
            if (best == null) {
                return null;
            }
            found = active.get(best);
            argument = identifier.substring(best.length() + 1);
        }
        if (!found.script().isActive()) {
            return null;
        }
        Object result = runSafely(found.script(), found.function(), player, argument);
        return result == null ? "" : (String) result;
    }

    /** A player is about to join: their saved data is loaded (call from a background thread). */
    public void playerJoining(UUID player) {
        data.preload(player);
    }

    /** A player left: their saved data is written and then dropped from memory. */
    public void playerQuit(UUID player) {
        data.unload(player);
    }

    /** Command names registered by the active scripts. */
    public List<String> commandNames() {
        return commands.rootNames();
    }

    private static CompilationTimings emptyTimings() {
        return new CompilationTimings(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
