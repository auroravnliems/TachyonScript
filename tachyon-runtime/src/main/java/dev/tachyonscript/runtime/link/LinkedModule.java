package dev.tachyonscript.runtime.link;

import dev.tachyonscript.ir.SourceText;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;
import dev.tachyonscript.runtime.value.RecordType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module linked against a platform: every function executable, every handler resolved,
 * its top-level variables, player data and record types in place.
 */
public final class LinkedModule {

    private final String name;
    private final SourceText source;
    private final Map<String, CompiledFunction> functions;
    private final List<CompiledHandler> handlers;
    private final Map<String, GlobalCell> globals;
    private final Map<String, PlayerDataSlot> playerData;
    private final Map<String, RecordType> records;
    private final Object owner;
    private final List<String> notes;

    LinkedModule(String name, SourceText source, Map<String, CompiledFunction> functions, List<CompiledHandler> handlers,
                 Map<String, GlobalCell> globals, Map<String, PlayerDataSlot> playerData, Map<String, RecordType> records,
                 Object owner, List<String> notes) {
        this.name = name;
        this.source = source;
        this.functions = Collections.unmodifiableMap(functions);
        this.handlers = List.copyOf(handlers);
        this.globals = Collections.unmodifiableMap(globals);
        this.playerData = Collections.unmodifiableMap(playerData);
        this.records = Collections.unmodifiableMap(records);
        this.owner = owner;
        this.notes = List.copyOf(notes);
    }

    /**
     * What linking could not do as requested without failing: functions that stay interpreted
     * under the bytecode backend, and why. Hosts report these; they are never silent.
     */
    public List<String> notes() {
        return notes;
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

    /** Cells of the module's {@code let}, {@code var} and {@code persistent var} variables, by name. */
    public Map<String, GlobalCell> globals() {
        return globals;
    }

    /** Slots of the module's {@code playerdata var} variables, by name. */
    public Map<String, PlayerDataSlot> playerData() {
        return playerData;
    }

    /** Record types declared by the module, by name. */
    public Map<String, RecordType> records() {
        return records;
    }

    /** The owner given by the link environment (the engine's script instance), or null. */
    public Object owner() {
        return owner;
    }
}
