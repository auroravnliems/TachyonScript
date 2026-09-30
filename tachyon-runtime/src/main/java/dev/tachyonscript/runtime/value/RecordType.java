package dev.tachyonscript.runtime.value;

import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.ir.RecordRef;

import java.util.List;
import java.util.Objects;

/**
 * The runtime descriptor of a record type declared by a script ({@code record Warp(...)}).
 * One descriptor exists per loaded version of the declaring module.
 */
public final class RecordType {

    private final String module;
    private final String name;
    private final List<String> fieldNames;
    private final List<Type> fieldTypes;

    public RecordType(RecordRef ref) {
        this.module = ref.module();
        this.name = ref.name();
        this.fieldNames = ref.fieldNames();
        this.fieldTypes = ref.fieldTypes();
    }

    public String module() {
        return module;
    }

    public String name() {
        return name;
    }

    /** Unique key, {@code module::Name}. */
    public String key() {
        return module + "::" + name;
    }

    public List<String> fieldNames() {
        return fieldNames;
    }

    public List<Type> fieldTypes() {
        return fieldTypes;
    }

    public int fieldCount() {
        return fieldNames.size();
    }

    /** Whether {@code ref} describes this type (same key and the same fields). */
    public boolean matches(RecordRef ref) {
        return ref.key().equals(key()) && ref.fieldNames().equals(fieldNames) && sameKinds(ref.fieldTypes());
    }

    private boolean sameKinds(List<Type> types) {
        if (types.size() != fieldTypes.size()) {
            return false;
        }
        for (int i = 0; i < types.size(); i++) {
            if (!Objects.equals(types.get(i).displayName(), fieldTypes.get(i).displayName())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return name;
    }
}
