package dev.tachyonscript.ir;

import dev.tachyonscript.api.type.Type;

import java.util.List;
import java.util.Objects;

/**
 * A record type declared by a script module ({@code record Warp(name: string, cost: int)}).
 * Record values keep their fields boxed, in declaration order.
 *
 * @param module     name of the declaring module
 * @param name       record name
 * @param fieldNames field names, in order
 * @param fieldTypes field types, in the same order
 */
public record RecordRef(String module, String name, List<String> fieldNames, List<Type> fieldTypes) {

    public RecordRef {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(name, "name");
        fieldNames = List.copyOf(fieldNames);
        fieldTypes = List.copyOf(fieldTypes);
        if (fieldNames.size() != fieldTypes.size()) {
            throw new IllegalArgumentException("Record " + name + " has " + fieldNames.size() + " names for "
                    + fieldTypes.size() + " types");
        }
    }

    /** Unique key, {@code module::Name}. */
    public String key() {
        return module + "::" + name;
    }

    public int fieldCount() {
        return fieldNames.size();
    }

    @Override
    public String toString() {
        return key();
    }
}
