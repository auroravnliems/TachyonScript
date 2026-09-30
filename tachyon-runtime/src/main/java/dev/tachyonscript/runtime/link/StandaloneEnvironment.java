package dev.tachyonscript.runtime.link;

import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.ir.FunctionRef;
import dev.tachyonscript.ir.GlobalRef;
import dev.tachyonscript.ir.RecordRef;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.Interpreter;
import dev.tachyonscript.runtime.value.GlobalCell;
import dev.tachyonscript.runtime.value.PlayerDataSlot;
import dev.tachyonscript.runtime.value.RecordType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link LinkEnvironment} that keeps everything in memory: top-level variables live as long
 * as the environment, player data is keyed by the player object and nothing is saved. Link
 * modules in import order and {@link #register} each one, so later modules can call it.
 */
public final class StandaloneEnvironment implements LinkEnvironment {

    private final Map<String, GlobalCell> globals = new ConcurrentHashMap<>();
    private final Map<String, PlayerDataSlot> playerData = new ConcurrentHashMap<>();
    private final Map<String, RecordType> records = new ConcurrentHashMap<>();
    private final Map<String, LinkedModule> modules = new ConcurrentHashMap<>();

    /** Makes a linked module available to the modules linked after it. */
    public void register(LinkedModule module) {
        modules.put(module.name(), module);
    }

    @Override
    public Object owner() {
        return null;
    }

    @Override
    public GlobalCell global(GlobalRef ref) {
        return globals.computeIfAbsent(ref.key(), key -> new GlobalCell(ref));
    }

    @Override
    public PlayerDataSlot playerData(GlobalRef ref) {
        return playerData.computeIfAbsent(ref.key(), key -> new MemorySlot(ref, this));
    }

    @Override
    public RecordType record(RecordRef ref) {
        return records.computeIfAbsent(ref.key(), key -> new RecordType(ref));
    }

    @Override
    public CompiledFunction function(FunctionRef ref) {
        LinkedModule module = modules.get(ref.module());
        return module == null ? null : module.function(ref.key()).orElse(null);
    }

    /** Player data in a map; the initial value comes from the declaring module's default function. */
    private static final class MemorySlot implements PlayerDataSlot {
        private final GlobalRef ref;
        private final StandaloneEnvironment environment;
        private final Map<Object, Object> values = new ConcurrentHashMap<>();

        MemorySlot(GlobalRef ref, StandaloneEnvironment environment) {
            this.ref = ref;
            this.environment = environment;
        }

        @Override
        public GlobalRef ref() {
            return ref;
        }

        @Override
        public Object get(Object player) {
            Object value = values.get(player);
            if (value != null || values.containsKey(player)) {
                return value;
            }
            Object initial = initial();
            Object previous = initial == null ? null : values.putIfAbsent(player, initial);
            return previous != null ? previous : initial;
        }

        @Override
        public void set(Object player, Object value) {
            if (value == null) {
                values.remove(player);
            } else {
                values.put(player, value);
            }
        }

        @Override
        public void add(Object player, long deltaBits) {
            Object initial = initial();
            values.compute(player, (key, current) -> {
                Object base = current != null ? current : initial;
                return switch ((PrimitiveType) ref.type()) {
                    case INT -> (Integer) base + (int) deltaBits;
                    case LONG -> (Long) base + deltaBits;
                    default -> (Double) base + Double.longBitsToDouble(deltaBits);
                };
            });
        }

        private Object initial() {
            LinkedModule module = environment.modules.get(ref.module());
            CompiledFunction function = module == null ? null
                    : module.function("$default:" + ref.name()).orElse(null);
            if (function == null) {
                throw new IllegalStateException("No initial value for playerdata " + ref);
            }
            return Interpreter.call(function);
        }
    }
}
