package dev.tachyonscript.engine.database;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One row of a query result, the value of type {@code Row} in scripts. Column names are
 * matched ignoring case. Values are text, {@code Long}, {@code Double}, {@code Boolean} or null.
 */
public final class DatabaseRow {

    private final String[] columns;
    private final Object[] values;

    DatabaseRow(String[] columns, Object[] values) {
        this.columns = columns;
        this.values = values;
    }

    public List<Object> columns() {
        return new ArrayList<>(List.of((Object[]) columns));
    }

    private int index(String column) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(column)) {
                return i;
            }
        }
        throw new ScriptError("The row has no column '" + column + "' (columns: " + String.join(", ", columns) + ").");
    }

    public Object get(String column) {
        return values[index(column)];
    }

    public String string(String column) {
        Object value = get(column);
        return value == null ? null : Values.toString(value);
    }

    public Long longValue(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> null;
            case Number number -> number.longValue();
            case Boolean bool -> bool ? 1L : 0L;
            case String text -> {
                try {
                    yield Long.parseLong(text.strip());
                } catch (NumberFormatException e) {
                    throw new ScriptError("The column '" + column + "' holds '" + text + "', not a whole number.");
                }
            }
            default -> throw new ScriptError("The column '" + column + "' is not a number.");
        };
    }

    public Integer intValue(String column) {
        Long value = longValue(column);
        return value == null ? null : value.intValue();
    }

    public Double doubleValue(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> null;
            case Number number -> number.doubleValue();
            case String text -> {
                try {
                    yield Double.parseDouble(text.strip());
                } catch (NumberFormatException e) {
                    throw new ScriptError("The column '" + column + "' holds '" + text + "', not a number.");
                }
            }
            default -> throw new ScriptError("The column '" + column + "' is not a number.");
        };
    }

    public Boolean boolValue(String column) {
        Object value = get(column);
        return switch (value) {
            case null -> null;
            case Boolean bool -> bool;
            case Number number -> number.doubleValue() != 0;
            case String text -> text.equalsIgnoreCase("true") || text.equals("1");
            default -> throw new ScriptError("The column '" + column + "' is not a bool.");
        };
    }

    public UUID uuid(String column) {
        String text = string(column);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            throw new ScriptError("The column '" + column + "' holds '" + text + "', not a UUID.");
        }
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(columns[i].toLowerCase(Locale.ROOT)).append(": ").append(Values.toString(values[i]));
        }
        return out.append('}').toString();
    }
}
