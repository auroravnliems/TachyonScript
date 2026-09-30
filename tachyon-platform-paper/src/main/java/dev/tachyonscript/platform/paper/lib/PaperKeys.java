package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.registry.KeyTable;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.storage.KeyedValues;
import dev.tachyonscript.api.type.ClassType;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * How the constants of keyed types ({@code Material.DIAMOND}, {@code Sound.ENTITY_PLAYER_LEVELUP})
 * find their Paper objects. Registries are only looked up when a constant is first resolved,
 * so creating the bindings needs no running server.
 */
public final class PaperKeys {

    /** Keyed values that can also list every key (to build constant tables from a running server). */
    public interface Enumerable extends KeyedValues {
        List<String> keys();
    }

    private PaperKeys() {
    }

    /** An enum whose constants are {@link Keyed} (Material, EntityType, Particle). */
    public static <E extends Enum<E> & Keyed> Enumerable keyedEnum(Class<E> type) {
        return new Table(() -> {
            Map<String, Object> table = new LinkedHashMap<>();
            for (E constant : type.getEnumConstants()) {
                try {
                    table.put(constant.getKey().asString(), constant);
                } catch (IllegalArgumentException | IllegalStateException noKey) {
                    // Constants without a key (EntityType.UNKNOWN, legacy materials) are not scriptable.
                }
            }
            return table;
        });
    }

    /** A plain enum; the key of a constant is {@code minecraft:} and its name in lower case. */
    public static <E extends Enum<E>> Enumerable enumConstants(Class<E> type) {
        return new Table(() -> {
            Map<String, Object> table = new LinkedHashMap<>();
            for (E constant : type.getEnumConstants()) {
                table.put(NamespacedKey.MINECRAFT + ":" + constant.name().toLowerCase(Locale.ROOT), constant);
            }
            return table;
        });
    }

    /** A Paper registry (sounds, enchantments, biomes, ...), including data pack additions. */
    public static <T extends Keyed> Enumerable registry(RegistryKey<T> key) {
        return new Enumerable() {
            private volatile Registry<T> registry;

            private Registry<T> registry() {
                Registry<T> current = registry;
                if (current == null) {
                    current = RegistryAccess.registryAccess().getRegistry(key);
                    registry = current;
                }
                return current;
            }

            @Override
            public Object resolve(String text) {
                NamespacedKey namespaced = NamespacedKey.fromString(text);
                return namespaced == null ? null : registry().get(namespaced);
            }

            @Override
            public String keyOf(Object value) {
                return ((Keyed) value).getKey().asString();
            }

            @Override
            public List<String> keys() {
                List<String> keys = new ArrayList<>();
                for (T value : registry()) {
                    keys.add(value.getKey().asString());
                }
                return keys;
            }
        };
    }

    /** Replaces the built-in constant table of a type with the keys of the running server. */
    public static void live(SymbolRegistry.Builder builder, ClassType type, KeyedValues values) {
        if (values instanceof Enumerable enumerable) {
            List<String> keys = enumerable.keys();
            if (!keys.isEmpty()) {
                builder.keys(type, KeyTable.ofKeys(keys));
            }
        }
    }

    /** Keyed values backed by a table built on first use. */
    private static final class Table implements Enumerable {
        private final Supplier<Map<String, Object>> factory;
        private volatile Map<String, Object> table;

        Table(Supplier<Map<String, Object>> factory) {
            this.factory = factory;
        }

        private Map<String, Object> table() {
            Map<String, Object> current = table;
            if (current == null) {
                current = Map.copyOf(factory.get());
                table = current;
            }
            return current;
        }

        @Override
        public Object resolve(String key) {
            return table().get(key);
        }

        @Override
        public String keyOf(Object value) {
            if (value instanceof Keyed keyed) {
                return keyed.getKey().asString();
            }
            return NamespacedKey.MINECRAFT + ":" + ((Enum<?>) value).name().toLowerCase(Locale.ROOT);
        }

        @Override
        public List<String> keys() {
            return new ArrayList<>(factory.get().keySet());
        }
    }
}
