package dev.tachyonscript.engine;

import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.engine.spi.PlayerDirectory;
import dev.tachyonscript.engine.storage.DataStore;
import dev.tachyonscript.engine.storage.ValueCodec;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.RecordRef;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.runtime.link.LinkEnvironment;
import dev.tachyonscript.runtime.link.LinkedModule;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;
import dev.tachyonscript.runtime.value.RecordType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Links one loaded script: creates the storage of its own variables (persistent ones through
 * the data store), its record types and player data slots, and resolves what it imports from
 * the other scripts of the same load.
 */
final class ModuleEnvironment implements LinkEnvironment {

    private final LoadedScript script;
    private final Function<String, LoadedScript> modules;
    private final DataStore store;
    private final PlayerDirectory players;
    private final Map<String, GlobalCell> globals = new LinkedHashMap<>();
    private final Map<String, PlayerDataSlot> playerData = new LinkedHashMap<>();
    private final Map<String, RecordType> records = new LinkedHashMap<>();

    /**
     * @param modules the loaded version of another module by name (scripts of this load and
     *                unchanged ones), or null
     */
    ModuleEnvironment(LoadedScript script, Function<String, LoadedScript> modules, DataStore store, PlayerDirectory players) {
        this.script = script;
        this.modules = modules;
        this.store = store;
        this.players = players;
    }

    private boolean own(String module) {
        return module.equals(script.module());
    }

    @Override
    public Object owner() {
        return script;
    }

    @Override
    public synchronized GlobalCell global(GlobalRef ref) {
        if (own(ref.module())) {
            return globals.computeIfAbsent(ref.name(), name -> {
                GlobalCell cell = new GlobalCell(ref);
                if (ref.storage() == GlobalRef.Storage.PERSISTENT) {
                    cell.persistence(store.persistence(this::record));
                }
                return cell;
            });
        }
        LinkedModule other = linked(ref.module());
        GlobalCell cell = other == null ? null : other.globals().get(ref.name());
        return cell != null && sameType(cell.ref(), ref) ? cell : null;
    }

    @Override
    public synchronized PlayerDataSlot playerData(GlobalRef ref) {
        if (own(ref.module())) {
            return playerData.computeIfAbsent(ref.name(),
                    name -> store.playerData(ref, () -> initialValue(name), this::record, players));
        }
        LinkedModule other = linked(ref.module());
        PlayerDataSlot slot = other == null ? null : other.playerData().get(ref.name());
        return slot != null && sameType(slot.ref(), ref) ? slot : null;
    }

    /** The function computing a variable's initial value; resolved lazily, it exists once linking finished. */
    private CompiledFunction initialValue(String name) {
        LinkedModule module = script.linked();
        if (module == null) {
            throw new IllegalStateException("Player data of " + script.path() + " used before the script was linked");
        }
        return module.function("$default:" + name)
                .orElseThrow(() -> new IllegalStateException("No initial value for playerdata " + name + " in " + script.path()));
    }

    @Override
    public synchronized RecordType record(RecordRef ref) {
        if (own(ref.module())) {
            return records.computeIfAbsent(ref.name(), name -> new RecordType(ref, this::fieldDefault));
        }
        LinkedModule other = linked(ref.module());
        return other == null ? null : other.records().get(ref.name());
    }

    /** The declared default value of a field of one of this script's records, computed now. */
    private Object fieldDefault(RecordType type, int field) {
        LinkedModule module = script.linked();
        if (module == null) {
            return RecordType.NO_DEFAULT;
        }
        return module.function("$field:" + type.name() + "." + type.fieldNames().get(field))
                .map(function -> Interpreter.call(function))
                .orElse(RecordType.NO_DEFAULT);
    }

    /** The runtime descriptor of a script record type (for decoding saved values). */
    synchronized RecordType record(ClassType type) {
        if (own(type.origin())) {
            return records.get(type.name());
        }
        LinkedModule other = linked(type.origin());
        return other == null ? null : other.records().get(type.name());
    }

    @Override
    public CompiledFunction function(FunctionRef ref) {
        LinkedModule other = linked(ref.module());
        return other == null ? null : other.function(ref.key()).orElse(null);
    }

    private LinkedModule linked(String module) {
        LoadedScript other = modules.apply(module);
        return other == null ? null : other.linked();
    }

    private static boolean sameType(GlobalRef actual, GlobalRef expected) {
        return actual.storage() == expected.storage()
                && actual.type().displayName().equals(expected.type().displayName());
    }

    /** The resolver used to decode saved records of this module and its imports. */
    ValueCodec.RecordResolver records() {
        return this::record;
    }
}
