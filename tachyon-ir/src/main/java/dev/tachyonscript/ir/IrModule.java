package dev.tachyonscript.ir;

import dev.tachyonscript.api.declaration.EventDeclaration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The IR of one script file: its functions, which of them handle which events, and the
 * source text used to map runtime errors back to the script.
 */
public final class IrModule {

    /** Binds an event to the IR function handling it. */
    public record EventHandler(EventDeclaration event, String function) {
    }

    private final String name;
    private final SourceText source;
    private final List<IrFunction> functions;
    private final List<EventHandler> handlers;
    private final Map<String, IrFunction> byKey = new LinkedHashMap<>();

    public IrModule(String name, SourceText source, List<IrFunction> functions, List<EventHandler> handlers) {
        this.name = Objects.requireNonNull(name, "name");
        this.source = Objects.requireNonNull(source, "source");
        this.functions = List.copyOf(functions);
        this.handlers = List.copyOf(handlers);
        for (IrFunction function : this.functions) {
            if (byKey.put(function.key(), function) != null) {
                throw new IllegalArgumentException("Duplicate function key " + function.key() + " in module " + name);
            }
        }
        for (EventHandler handler : this.handlers) {
            if (!byKey.containsKey(handler.function())) {
                throw new IllegalArgumentException("Handler for " + handler.event() + " refers to unknown function "
                        + handler.function());
            }
        }
    }

    public String name() {
        return name;
    }

    public SourceText source() {
        return source;
    }

    public List<IrFunction> functions() {
        return functions;
    }

    public Optional<IrFunction> function(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public List<EventHandler> handlers() {
        return handlers;
    }

    /** A copy with transformed functions (same keys). */
    public IrModule withFunctions(List<IrFunction> newFunctions) {
        return new IrModule(name, source, newFunctions, handlers);
    }
}
