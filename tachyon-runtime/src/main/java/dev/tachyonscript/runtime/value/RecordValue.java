package dev.tachyonscript.runtime.value;

import java.util.Arrays;

/**
 * A value of a script record type: immutable, compared by content. Fields are stored boxed,
 * in declaration order.
 */
public final class RecordValue {

    private final RecordType type;
    private final Object[] fields;

    /** @param fields the field values; the array is owned by the new value */
    public RecordValue(RecordType type, Object[] fields) {
        if (fields.length != type.fieldCount()) {
            throw new IllegalArgumentException("Record " + type.name() + " has " + type.fieldCount() + " fields, got "
                    + fields.length);
        }
        this.type = type;
        this.fields = fields;
    }

    public RecordType type() {
        return type;
    }

    /** Field {@code index} (boxed). */
    public Object get(int index) {
        return fields[index];
    }

    /** Field by name, or {@code null} if the record has no such field. */
    public Object get(String name) {
        int index = type.fieldNames().indexOf(name);
        return index < 0 ? null : fields[index];
    }

    public int size() {
        return fields.length;
    }

    /**
     * Equal when of the same record type (same module and name, so values survive reloads of
     * the declaring script) with equal fields.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof RecordValue record && record.type.key().equals(type.key())
                && Arrays.equals(record.fields, fields);
    }

    @Override
    public int hashCode() {
        return type.key().hashCode() * 31 + Arrays.hashCode(fields);
    }

    /** Text form used by templates, e.g. {@code Warp(name=spawn, cost=10)}. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(type.name()).append('(');
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(type.fieldNames().get(i)).append('=').append(ValueText.of(fields[i], type.fieldTypes().get(i)));
        }
        return out.append(')').toString();
    }
}
