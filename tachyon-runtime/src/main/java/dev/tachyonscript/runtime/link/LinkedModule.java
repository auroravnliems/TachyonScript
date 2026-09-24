package dev.tachyonscript.runtime.link;

import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module linked against a platform: every function executable, every handler resolved.
 */
public final class LinkedModule {

    private final String name;
    private final SourceText source;
    private final Map<String, CompiledFunction> functions;
    private final List<CompiledHandler> handlers;

    LinkedModule(String name, SourceText source, Map<String, CompiledFunction> functions, List<CompiledHandler> handlers) {
        this.name = name;
        this.source = source;
        this.functions = Collections.unmodifiableMap(functions);
        this.handlers = List.copyOf(handlers);
    }

    public String name() {
        return name;
    }

    public SourceText source() {
        return source;
    }

    public Optional<CompiledFunction> function(String key) {
        return Optional.ofNullable(functions.get(key));
    }

    public Map<String, CompiledFunction> functions() {
        return functions;
    }

    public List<CompiledHandler> handlers() {
        return handlers;
    }
}
