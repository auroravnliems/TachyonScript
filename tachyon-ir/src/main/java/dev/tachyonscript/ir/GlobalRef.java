package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Type;

import java.util.Objects;

/**
 * A top-level variable of a script module, as referred to by IR: {@code let}/{@code var}
 * (kept in memory), {@code persistent var} (saved, one value) or {@code playerdata var}
 * (saved, one value per player).
 *
 * @param module  name of the declaring module
 * @param name    variable name
 * @param type    static type of the value
 * @param storage where the value is kept
 */
public record GlobalRef(String module, String name, Type type, Storage storage) {

    /** Where a top-level variable keeps its value. */
    public enum Storage {
        /** In memory while the script is loaded. */
        SCRIPT,
        /** In the database, one value for the server. */
        PERSISTENT,
        /** In the database, one value per player. */
        PLAYERDATA
    }

    public GlobalRef {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(storage, "storage");
    }

    /** Unique key, {@code module::name}. */
    public String key() {
        return module + "::" + name;
    }

    @Override
    public String toString() {
        return key();
    }
}
