package dev.tachyonscript.engine;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.CompilationTimings;
import dev.tachyonscript.compiler.CompiledModule;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.compiler.InternalErrorHandler;
import dev.tachyonscript.engine.profile.Profiler;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.runtime.code.Assembler;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.event.HandlerTable;
import dev.tachyonscript.runtime.interpreter.ExecutionStack;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.runtime.link.LinkException;
import dev.tachyonscript.runtime.link.LinkedModule;
import dev.tachyonscript.runtime.link.Linker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads scripts and runs their event handlers.
 *
 * <p><b>Loading is transactional.</b> A load reads every script, recompiles only the ones
 * whose content changed, links them, and only when that succeeded (per the {@link LoadMode})
 * builds a new immutable {@link Generation} and swaps it in with a single volatile write.
 * A script that fails keeps its previous working version active; nothing that worked is
 * ever replaced by something that failed. Loads are serialized; dispatch never blocks.
 *
 * <p><b>Dispatch</b> reads the current generation once and runs its handlers for the event
 * on the calling thread (which, on Folia, is the thread owning the event's entity or
 * region). A failing handler is reported and the remaining handlers still run.
 */
public final class ScriptEngine {

    private static final long SLOW_REPORT_INTERVAL_NANOS = 30_000_000_000L;

    private final SymbolRegistry registry;
    private final Platform platform;
    private final EngineOptions options;
    private final Compiler compiler;
    private final ErrorReporter errors;
    private final Profiler profiler = new Profiler();
    private final Object loadLock = new Object();
    private final Map<CompiledHandler, Long> slowReported = new ConcurrentHashMap<>();
    private volatile Generation current;
    private volatile boolean timed;
    private long nextGeneration = 1;
    private boolean closed;

    public ScriptEngine(SymbolRegistry registry, Platform platform, EngineOptions options,
                        InternalErrorHandler internalErrors) {
        this.registry = registry;
        this.platform = platform;
        this.options = options;
        this.compiler = new Compiler(registry, options.compiler(), internalErrors);
        this.errors = new ErrorReporter(platform.logger(), options.debug());
        this.current = new Generation(0, Map.of(), HandlerTable.empty(registry), Set.of());
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

    public void startProfiling() {
        profiler.start();
        timed = true;
    }

    public void stopProfiling() {
        profiler.stop();
        timed = options.slowThresholdNanos() > 0;
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
        synchronized (loadLock) {
            long start = System.nanoTime();
            Generation previous = current;
            if (closed) {
                return new LoadReport(false, previous.id(), previous.scripts().size(), 0, 0, List.of(), List.of(),
                        previous.handlers().size(), List.of(), Map.of(), emptyTimings(), System.nanoTime() - start,
                        "The engine has been shut down.");
            }
            List<SourceFile> files;
            try {
                files = source.read();
            } catch (IOException | RuntimeException e) {
                return new LoadReport(false, previous.id(), previous.scripts().size(), 0, 0, List.of(), List.of(),
                        previous.handlers().size(), List.of(), Map.of(), emptyTimings(), System.nanoTime() - start,
                        "Cannot read scripts: " + e.getMessage());
            }

            Map<String, LoadedScript> next = new LinkedHashMap<>();
            List<SourceFile> changed = new ArrayList<>();
            for (SourceFile file : files) {
                LoadedScript old = previous.scripts().get(file.path());
                if (old != null && old.hash().equals(file.hash()) && !forceRecompile.contains(file.path())) {
                    next.put(file.path(), old);
                } else {
                    changed.add(file);
                }
            }
            int reused = next.size();

            CompilationResult result = compiler.compile(changed);
            List<String> failed = new ArrayList<>();
            Map<String, List<String>> linkProblems = new LinkedHashMap<>();
            for (CompiledModule module : result.modules()) {
                String path = module.file().path();
                if (!module.succeeded()) {
                    failed.add(path);
                    continue;
                }
                try {
                    LinkedModule linked = Linker.link(Assembler.assemble(module.ir()), platform.bindings(), platform.text());
                    next.put(path, new LoadedScript(path, module.file().hash(), module, linked));
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
                return new LoadReport(false, previous.id(), previous.scripts().size(), changed.size(), reused, failed,
                        List.of(), previous.handlers().size(), diagnostics, linkProblems, result.timings(),
                        System.nanoTime() - start, null);
            }
            List<String> keptPrevious = new ArrayList<>();
            for (String path : failed) {
                LoadedScript old = previous.scripts().get(path);
                if (old != null) {
                    next.put(path, old);
                    keptPrevious.add(path);
                }
            }
            Generation generation = build(next);
            activate(generation);
            return new LoadReport(true, generation.id(), generation.scripts().size(), changed.size(), reused, failed,
                    keptPrevious, generation.handlers().size(), diagnostics, linkProblems, result.timings(),
                    System.nanoTime() - start, null);
        }
    }

    private Generation build(Map<String, LoadedScript> scripts) {
        List<CompiledHandler> handlers = new ArrayList<>();
        // Deterministic order: by script path, then declaration order.
        scripts.keySet().stream().sorted().forEach(path -> handlers.addAll(scripts.get(path).linked().handlers()));
        return new Generation(nextGeneration++, scripts, HandlerTable.of(registry, handlers), Generation.eventsOf(handlers));
    }

    private void activate(Generation generation) {
        current = generation;
        errors.clear();
        slowReported.clear();
        platform.events().activeEventsChanged(generation.activeEvents());
    }

    /** Deactivates every script (plugin shutdown). Later loads are refused. */
    public void shutdown() {
        synchronized (loadLock) {
            closed = true;
            activate(new Generation(nextGeneration++, Map.of(), HandlerTable.empty(registry), Set.of()));
        }
    }

    // =================================================================== dispatch

    /** Runs the handlers of the event with registry index {@code eventIndex}. */
    public void dispatch(int eventIndex, Object event) {
        CompiledHandler[] handlers = current.handlers().handlers(eventIndex);
        if (handlers.length == 0) {
            return;
        }
        if (timed) {
            dispatchTimed(handlers, event);
            return;
        }
        for (CompiledHandler handler : handlers) {
            try {
                Interpreter.invokeHandler(handler.function(), event);
            } catch (ScriptRuntimeException error) {
                errors.report(handler, error);
            }
        }
    }

    public void dispatch(EventDeclaration declaration, Object event) {
        dispatch(registry.eventIndex(declaration), event);
    }

    private void dispatchTimed(CompiledHandler[] handlers, Object event) {
        boolean profiling = profiler.isEnabled();
        long threshold = options.slowThresholdNanos();
        for (CompiledHandler handler : handlers) {
            long start = System.nanoTime();
            try {
                Interpreter.invokeHandler(handler.function(), event);
            } catch (ScriptRuntimeException error) {
                errors.report(handler, error);
            }
            long elapsed = System.nanoTime() - start;
            if (profiling) {
                profiler.record(handler, elapsed);
            }
            // Only outermost executions: nested ones are part of their caller's time.
            if (threshold > 0 && elapsed > threshold && ExecutionStack.current().depth() == 0) {
                reportSlow(handler, elapsed);
            }
        }
    }

    private void reportSlow(CompiledHandler handler, long elapsed) {
        long now = System.nanoTime();
        Long last = slowReported.get(handler);
        if (last != null && now - last < SLOW_REPORT_INTERVAL_NANOS) {
            return;
        }
        slowReported.put(handler, now);
        var source = handler.function().source();
        long span = handler.function().unit().span();
        int line = source.line(dev.tachyonscript.ir.Spans.start(span));
        platform.logger().warn(String.format(Locale.ROOT, "Slow script execution: %s:%d, event %s, %.2f ms",
                source.path(), line, handler.event().name(), elapsed / 1e6));
    }

    /** Paths of scripts in the active generation that handle {@code event}. */
    public Set<String> handlersOf(EventDeclaration event) {
        Set<String> scripts = new HashSet<>();
        for (CompiledHandler handler : current.handlers().handlers(registry, event)) {
            scripts.add(handler.function().source().path());
        }
        return scripts;
    }

    private static CompilationTimings emptyTimings() {
        return new CompilationTimings(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
