package dev.tachyonscript.api.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The named constants of a keyed type: constant names as written in scripts
 * ({@code DIAMOND_SWORD}) and the keys they stand for ({@code minecraft:diamond_sword}).
 *
 * <p>Immutable. Lookups by name are exact (names are upper case by convention); the compiler
 * suggests close names when a lookup fails.
 */
public final class KeyTable {

    /** A table without constants. */
    public static final KeyTable EMPTY = new KeyTable(Map.of());

    private final Map<String, String> byName;
    private final Map<String, String> byKey;

    private KeyTable(Map<String, String> byName) {
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
        Map<String, String> reverse = new LinkedHashMap<>();
        byName.forEach((name, key) -> reverse.putIfAbsent(key, name));
        this.byKey = Collections.unmodifiableMap(reverse);
    }

    /**
     * Creates a table from {@code name -> key} entries (iteration order is kept).
     *
     * @throws RegistrationException if a name is not {@code [A-Z_][A-Z0-9_]*} or a key is not
     *                               {@code namespace:path}
     */
    public static KeyTable of(Map<String, String> entries) {
        Objects.requireNonNull(entries, "entries");
        entries.forEach((name, key) -> {
            if (!isConstantName(name)) {
                throw new RegistrationException("Invalid constant name '" + name + "'");
            }
            if (key == null || key.indexOf(':') <= 0 || key.endsWith(":")) {
                throw new RegistrationException("Invalid key '" + key + "' for constant " + name);
            }
        });
        return new KeyTable(entries);
    }

    /** A table whose constant names are derived from the keys with {@link #nameOf}. */
    public static KeyTable ofKeys(Iterable<String> keys) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String key : keys) {
            entries.putIfAbsent(nameOf(key), key);
        }
        return of(entries);
    }

    /**
     * The constant name for a key: its path in upper case with {@code .}, {@code /} and
     * {@code -} replaced by {@code _}. For example {@code minecraft:entity.player.levelup}
     * becomes {@code ENTITY_PLAYER_LEVELUP}, which is the naming of the Bukkit constants.
     */
    public static String nameOf(String key) {
        String path = key.substring(key.indexOf(':') + 1);
        StringBuilder name = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '/' || c == '-') {
                name.append('_');
            } else if (c >= 'a' && c <= 'z') {
                name.append((char) (c - 32));
            } else {
                name.append(c);
            }
        }
        return name.toString();
    }

    static boolean isConstantName(String name) {
        if (name == null || name.isEmpty() || (name.charAt(0) >= '0' && name.charAt(0) <= '9')) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** The key of a constant, e.g. {@code minecraft:diamond} for {@code DIAMOND}. */
    public Optional<String> key(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** The constant name of a key. */
    public Optional<String> name(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** Every constant name, in table order. */
    public Set<String> names() {
        return byName.keySet();
    }

    /** Every key, in table order. */
    public Set<String> keys() {
        return byKey.keySet();
    }

    public int size() {
        return byName.size();
    }
}
