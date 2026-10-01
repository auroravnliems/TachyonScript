package dev.tachyonscript.engine.storage;

import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.storage.Codec;
import dev.tachyonscript.api.storage.KeyedValues;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.ListType;
import dev.tachyonscript.api.type.MapType;
import dev.tachyonscript.api.type.NullableType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.runtime.intrinsics.RuntimeIntrinsics;
import dev.tachyonscript.runtime.value.RecordType;
import dev.tachyonscript.runtime.value.RecordValue;
import dev.tachyonscript.runtime.value.ScriptList;
import dev.tachyonscript.runtime.value.ScriptMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts script values to the text saved in the database and back, guided by the static
 * type of the variable.
 *
 * <p>The text is JSON: numbers, booleans and text as themselves, lists as arrays, maps as
 * arrays of {@code [key, value]} pairs (keys need not be text), records as objects by field
 * name (so fields can be reordered, and added: a field missing from the saved text takes its
 * declared default value, or null if its type is nullable), Minecraft constants by
 * key ({@code minecraft:diamond}) and other platform values through the codec the platform
 * binds for their type (locations, items, worlds, UUIDs, formatted text).
 */
public final class ValueCodec {

    /** Finds the runtime descriptor of a script record type (for decoding records). */
    @FunctionalInterface
    public interface RecordResolver {
        RecordType record(ClassType type);
    }

    private final Bindings bindings;

    public ValueCodec(Bindings bindings) {
        this.bindings = bindings;
    }

    /** Encodes a value of {@code type}. */
    public String encode(Object value, Type type) {
        return Json.write(toJson(value, type));
    }

    /** Decodes text produced by {@link #encode} for {@code type}. */
    public Object decode(String text, Type type, RecordResolver records) {
        return fromJson(Json.read(text), type, records);
    }

    /** Whether values of {@code type} can be encoded (everything a saved variable may hold). */
    public boolean canEncode(Type type) {
        return switch (type) {
            case PrimitiveType primitive -> primitive != PrimitiveType.VOID;
            case NullableType nullable -> canEncode(nullable.inner());
            case ListType list -> canEncode(list.element());
            case MapType map -> canEncode(map.key()) && canEncode(map.value());
            case ClassType classType -> classType == Types.STRING || classType.isScriptDefined() || classType.isKeyed()
                    || bindings.codec(classType).isPresent();
            default -> false;
        };
    }

    private Object toJson(Object value, Type type) {
        return switch (type) {
            case NullableType nullable -> value == null ? null : toJson(value, nullable.inner());
            case PrimitiveType primitive -> switch (primitive) {
                case INT, LONG, DURATION, INSTANT -> ((Number) value).longValue();
                case FLOAT, DOUBLE -> ((Number) value).doubleValue();
                case BOOL -> (Boolean) value;
                default -> throw new IllegalArgumentException("Cannot save a value of type " + type.displayName());
            };
            case ListType list -> {
                Object[] elements = RuntimeIntrinsics.snapshot((List<?>) value);
                List<Object> json = new ArrayList<>(elements.length);
                for (Object element : elements) {
                    json.add(toJson(element, list.element()));
                }
                yield json;
            }
            case MapType map -> {
                Map<?, ?> entries = value instanceof ScriptMap script ? script.toLinkedHashMap() : (Map<?, ?>) value;
                List<Object> json = new ArrayList<>(entries.size());
                for (Map.Entry<?, ?> entry : entries.entrySet()) {
                    json.add(Arrays.asList(nonNull(toJson(entry.getKey(), map.key())), toJson(entry.getValue(), map.value())));
                }
                yield json;
            }
            case ClassType classType -> classToJson(value, classType);
            default -> throw new IllegalArgumentException("Cannot save a value of type " + type.displayName());
        };
    }

    private static Object nonNull(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("A map key cannot be null");
        }
        return value;
    }

    private Object classToJson(Object value, ClassType type) {
        if (type == Types.STRING) {
            return value;
        }
        if (type.isScriptDefined()) {
            RecordValue record = (RecordValue) value;
            Map<String, Object> json = new LinkedHashMap<>();
            RecordType recordType = record.type();
            for (int i = 0; i < recordType.fieldCount(); i++) {
                json.put(recordType.fieldNames().get(i), toJson(record.get(i), recordType.fieldTypes().get(i)));
            }
            return json;
        }
        if (type.isKeyed()) {
            KeyedValues values = bindings.keyedValues(type)
                    .orElseThrow(() -> new IllegalArgumentException("The server cannot save " + type.name() + " values"));
            return values.keyOf(value);
        }
        Codec codec = bindings.codec(type)
                .orElseThrow(() -> new IllegalArgumentException("Values of type " + type.name() + " cannot be saved"));
        return codec.encode(value);
    }

    private Object fromJson(Object json, Type type, RecordResolver records) {
        return switch (type) {
            case NullableType nullable -> json == null ? null : fromJson(json, nullable.inner(), records);
            case PrimitiveType primitive -> switch (primitive) {
                case INT -> Math.toIntExact(number(json).longValue());
                case LONG, DURATION, INSTANT -> number(json).longValue();
                case FLOAT -> (float) floating(json);
                case DOUBLE -> floating(json);
                case BOOL -> {
                    if (!(json instanceof Boolean flag)) {
                        throw new IllegalArgumentException("Expected true or false, found " + json);
                    }
                    yield flag;
                }
                default -> throw new IllegalArgumentException("Cannot load a value of type " + type.displayName());
            };
            case ListType list -> {
                List<?> elements = (List<?>) json;
                ScriptList result = new ScriptList(elements.size());
                for (Object element : elements) {
                    result.add(fromJson(element, list.element(), records));
                }
                yield result;
            }
            case MapType map -> {
                List<?> pairs = (List<?>) json;
                ScriptMap result = new ScriptMap(pairs.size());
                for (Object pair : pairs) {
                    List<?> entry = (List<?>) pair;
                    result.put(fromJson(entry.get(0), map.key(), records), fromJson(entry.get(1), map.value(), records));
                }
                yield result;
            }
            case ClassType classType -> classFromJson(json, classType, records);
            default -> throw new IllegalArgumentException("Cannot load a value of type " + type.displayName());
        };
    }

    private Object classFromJson(Object json, ClassType type, RecordResolver records) {
        if (json == null) {
            throw new IllegalArgumentException("Missing value of type " + type.name());
        }
        if (type == Types.STRING) {
            return (String) json;
        }
        if (type.isScriptDefined()) {
            RecordType recordType = records.record(type);
            if (recordType == null) {
                throw new IllegalArgumentException("Record type " + type.name() + " is not loaded");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) json;
            Object[] values = new Object[recordType.fieldCount()];
            for (int i = 0; i < values.length; i++) {
                String name = recordType.fieldNames().get(i);
                Type fieldType = recordType.fieldTypes().get(i);
                if (!fields.containsKey(name)) {
                    // Saved before the field was added: its default value, or null.
                    Object fallback = recordType.defaultValue(i);
                    if (fallback == RecordType.NO_DEFAULT && !fieldType.isNullable()) {
                        throw new IllegalArgumentException("Saved " + type.name() + " has no field '" + name
                                + "' (give the field a default value to load older data)");
                    }
                    values[i] = fallback == RecordType.NO_DEFAULT ? null : fallback;
                    continue;
                }
                values[i] = fromJson(fields.get(name), fieldType, records);
            }
            return new RecordValue(recordType, values);
        }
        if (type.isKeyed()) {
            KeyedValues values = bindings.keyedValues(type)
                    .orElseThrow(() -> new IllegalArgumentException("The server cannot load " + type.name() + " values"));
            Object value = values.resolve((String) json);
            if (value == null) {
                throw new IllegalArgumentException("Unknown " + type.name() + " '" + json + "'");
            }
            return value;
        }
        Codec codec = bindings.codec(type)
                .orElseThrow(() -> new IllegalArgumentException("Values of type " + type.name() + " cannot be loaded"));
        return codec.decode((String) json);
    }

    private static Number number(Object json) {
        if (json instanceof Number number) {
            return number;
        }
        throw new IllegalArgumentException("Expected a number, found " + json);
    }

    private static double floating(Object json) {
        if (json instanceof String text) {
            return Double.parseDouble(text); // NaN and infinities are stored as text
        }
        return number(json).doubleValue();
    }
}
