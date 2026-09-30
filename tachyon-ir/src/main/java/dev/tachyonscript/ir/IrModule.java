package dev.tachyonscript.ir;

import dev.tachyonscript.api.declaration.EventDeclaration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The IR of one script file: its functions, which of them handle which events, the
 * top-level variables and records it declares, and the source text used to map runtime
 * errors back to the script.
 */
public final class IrModule {

    /**
     * Binds an event to the IR function handling it.
     *
     * @param priority        listener priority, 0 (lowest) to 5 (monitor); 2 is normal
     * @param ignoreCancelled whether the handler is skipped for events already cancelled
     */
    public record EventHandler(EventDeclaration event, String function, int priority, boolean ignoreCancelled) {

        public static final int NORMAL_PRIORITY = 2;

        public EventHandler(EventDeclaration event, String function) {
            this(event, function, NORMAL_PRIORITY, false);
        }
    }

    private final String name;
    private final SourceText source;
    private final List<IrFunction> functions;
    private final List<EventHandler> handlers;
    private final List<GlobalRef> globals;
    private final List<RecordRef> records;
    private final Map<String, IrFunction> byKey = new LinkedHashMap<>();

    public IrModule(String name, SourceText source, List<IrFunction> functions, List<EventHandler> handlers,
                    List<GlobalRef> globals, List<RecordRef> records) {
        this.name = Objects.requireNonNull(name, "name");
        this.source = Objects.requireNonNull(source, "source");
        this.functions = List.copyOf(functions);
        this.handlers = List.copyOf(handlers);
        this.globals = List.copyOf(globals);
        this.records = List.copyOf(records);
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
        for (GlobalRef global : this.globals) {
            if (!global.module().equals(name)) {
                throw new IllegalArgumentException("Module " + name + " declares global " + global + " of another module");
            }
        }
    }

    public IrModule(String name, SourceText source, List<IrFunction> functions, List<EventHandler> handlers) {
        this(name, source, functions, handlers, List.of(), List.of());
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

    /** Top-level variables declared by this module, in declaration order. */
    public List<GlobalRef> globals() {
        return globals;
    }

    /** Records declared by this module, in declaration order. */
    public List<RecordRef> records() {
        return records;
    }

    /** A copy with transformed functions (same keys). */
    public IrModule withFunctions(List<IrFunction> newFunctions) {
        return new IrModule(name, source, newFunctions, handlers, globals, records);
    }
}
