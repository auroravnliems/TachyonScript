package dev.tachyonscript.api.intrinsic;

import dev.tachyonscript.api.declaration.Effect;
import dev.tachyonscript.api.declaration.NativeDeclaration;
import dev.tachyonscript.api.declaration.Parameter;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in operations of the language that are implemented as native calls: most methods of
 * lists and maps, members of caught errors, and the scheduling blocks ({@code after},
 * {@code every}, {@code async}, {@code sync}).
 *
 * <p>Their parameter and result types are erased ({@code any?} for every reference): the
 * compiler type-checks each use against the element types of the actual list or map and
 * inserts the conversions, so the implementations can work on plain objects. The runtime
 * ({@code dev.tachyonscript.runtime.intrinsics}) and the engine (scheduling) bind them; the
 * linker installs them automatically, so platforms and addons never see them.
 */
public final class Intrinsics {

    /** Erased reference type used by intrinsic signatures. */
    public static final Type REF = Types.nullable(Types.ANY);

    private static final List<NativeDeclaration> ALL = new ArrayList<>();

    // ------------------------------------------------------------------ lists
    public static final NativeDeclaration LIST_REMOVE = declare("$list.remove", Types.BOOL, Effect.MODIFIES_WORLD, "list", REF, "element", REF);
    public static final NativeDeclaration LIST_REMOVE_AT = declare("$list.removeAt", REF, Effect.MODIFIES_WORLD, "list", REF, "index", Types.INT);
    public static final NativeDeclaration LIST_INSERT = declare("$list.insert", Types.VOID, Effect.MODIFIES_WORLD, "list", REF, "index", Types.INT, "element", REF);
    public static final NativeDeclaration LIST_INDEX_OF = declare("$list.indexOf", Types.INT, null, "list", REF, "element", REF);
    public static final NativeDeclaration LIST_LAST_INDEX_OF = declare("$list.lastIndexOf", Types.INT, null, "list", REF, "element", REF);
    public static final NativeDeclaration LIST_CLEAR = declare("$list.clear", Types.VOID, Effect.MODIFIES_WORLD, "list", REF);
    public static final NativeDeclaration LIST_ADD_ALL = declare("$list.addAll", Types.VOID, Effect.MODIFIES_WORLD, "list", REF, "other", REF);
    public static final NativeDeclaration LIST_COPY = declare("$list.copy", REF, null, "list", REF);
    /** The elements of a list taken atomically, for {@code for x in list} (changes during the loop do not affect it). */
    public static final NativeDeclaration LIST_SNAPSHOT = declare("$list.snapshot", REF, null, "list", REF);
    public static final NativeDeclaration LIST_REVERSE = declare("$list.reverse", Types.VOID, Effect.MODIFIES_WORLD, "list", REF);
    public static final NativeDeclaration LIST_REVERSED = declare("$list.reversed", REF, null, "list", REF);
    public static final NativeDeclaration LIST_SORT = declare("$list.sort", Types.VOID, Effect.MODIFIES_WORLD, "list", REF);
    public static final NativeDeclaration LIST_SORTED = declare("$list.sorted", REF, null, "list", REF);
    public static final NativeDeclaration LIST_SORT_BY = declare("$list.sortBy", Types.VOID, Effect.MODIFIES_WORLD, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_SORTED_BY = declare("$list.sortedBy", REF, null, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_SORT_BY_DESCENDING = declare("$list.sortByDescending", Types.VOID, Effect.MODIFIES_WORLD, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_SORTED_BY_DESCENDING = declare("$list.sortedByDescending", REF, null, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_SORT_WITH = declare("$list.sortWith", Types.VOID, Effect.MODIFIES_WORLD, "list", REF, "comparator", REF);
    public static final NativeDeclaration LIST_SHUFFLE = declare("$list.shuffle", Types.VOID, Effect.MODIFIES_WORLD, "list", REF);
    public static final NativeDeclaration LIST_RANDOM = declare("$list.random", REF, null, "list", REF);
    public static final NativeDeclaration LIST_FIRST = declare("$list.first", REF, null, "list", REF);
    public static final NativeDeclaration LIST_LAST = declare("$list.last", REF, null, "list", REF);
    public static final NativeDeclaration LIST_FILTER = declare("$list.filter", REF, null, "list", REF, "predicate", REF);
    public static final NativeDeclaration LIST_MAP = declare("$list.map", REF, null, "list", REF, "transform", REF);
    public static final NativeDeclaration LIST_FOR_EACH = declare("$list.forEach", Types.VOID, Effect.MODIFIES_WORLD, "list", REF, "action", REF);
    public static final NativeDeclaration LIST_ANY = declare("$list.any", Types.BOOL, null, "list", REF, "predicate", REF);
    public static final NativeDeclaration LIST_ALL = declare("$list.all", Types.BOOL, null, "list", REF, "predicate", REF);
    public static final NativeDeclaration LIST_NONE = declare("$list.none", Types.BOOL, null, "list", REF, "predicate", REF);
    public static final NativeDeclaration LIST_COUNT = declare("$list.count", Types.INT, null, "list", REF, "predicate", REF);
    public static final NativeDeclaration LIST_FIND = declare("$list.find", REF, null, "list", REF, "predicate", REF);
    public static final NativeDeclaration LIST_FIND_INDEX = declare("$list.findIndex", Types.INT, null, "list", REF, "predicate", REF);
    public static final NativeDeclaration LIST_JOIN = declare("$list.join", Types.STRING, null, "list", REF, "separator", Types.STRING, "text", REF);
    public static final NativeDeclaration LIST_SUM_LONG = declare("$list.sumLong", Types.LONG, null, "list", REF);
    public static final NativeDeclaration LIST_SUM_DOUBLE = declare("$list.sumDouble", Types.DOUBLE, null, "list", REF);
    public static final NativeDeclaration LIST_AVERAGE = declare("$list.average", Types.DOUBLE, null, "list", REF);
    public static final NativeDeclaration LIST_MIN = declare("$list.min", REF, null, "list", REF);
    public static final NativeDeclaration LIST_MAX = declare("$list.max", REF, null, "list", REF);
    public static final NativeDeclaration LIST_MIN_BY = declare("$list.minBy", REF, null, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_MAX_BY = declare("$list.maxBy", REF, null, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_TAKE = declare("$list.take", REF, null, "list", REF, "count", Types.INT);
    public static final NativeDeclaration LIST_DROP = declare("$list.drop", REF, null, "list", REF, "count", Types.INT);
    public static final NativeDeclaration LIST_SUB_LIST = declare("$list.subList", REF, null, "list", REF, "from", Types.INT, "to", Types.INT);
    public static final NativeDeclaration LIST_DISTINCT = declare("$list.distinct", REF, null, "list", REF);
    public static final NativeDeclaration LIST_GROUP_BY = declare("$list.groupBy", REF, null, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_ASSOCIATE_BY = declare("$list.associateBy", REF, null, "list", REF, "key", REF);
    public static final NativeDeclaration LIST_REPEAT = declare("$list.filled", REF, null, "count", Types.INT, "element", REF);

    // ------------------------------------------------------------------ maps
    public static final NativeDeclaration MAP_NEW = declare("$map.new", REF, null);
    public static final NativeDeclaration MAP_GET = declare("$map.get", REF, null, "map", REF, "key", REF);
    public static final NativeDeclaration MAP_GET_OR_DEFAULT = declare("$map.getOrDefault", REF, null, "map", REF, "key", REF, "fallback", REF);
    public static final NativeDeclaration MAP_PUT = declare("$map.put", Types.VOID, Effect.MODIFIES_WORLD, "map", REF, "key", REF, "value", REF);
    public static final NativeDeclaration MAP_PUT_IF_ABSENT = declare("$map.putIfAbsent", REF, Effect.MODIFIES_WORLD, "map", REF, "key", REF, "value", REF);
    public static final NativeDeclaration MAP_REMOVE = declare("$map.remove", REF, Effect.MODIFIES_WORLD, "map", REF, "key", REF);
    public static final NativeDeclaration MAP_CONTAINS_KEY = declare("$map.containsKey", Types.BOOL, null, "map", REF, "key", REF);
    public static final NativeDeclaration MAP_CONTAINS_VALUE = declare("$map.containsValue", Types.BOOL, null, "map", REF, "value", REF);
    public static final NativeDeclaration MAP_SIZE = declare("$map.size", Types.INT, null, "map", REF);
    public static final NativeDeclaration MAP_CLEAR = declare("$map.clear", Types.VOID, Effect.MODIFIES_WORLD, "map", REF);
    public static final NativeDeclaration MAP_KEYS = declare("$map.keys", REF, null, "map", REF);
    public static final NativeDeclaration MAP_VALUES = declare("$map.values", REF, null, "map", REF);
    /** A snapshot {@code [key0, value0, key1, value1, ...]} taken atomically, for {@code for k, v in map}. */
    public static final NativeDeclaration MAP_ENTRIES = declare("$map.entries", REF, null, "map", REF);
    public static final NativeDeclaration MAP_PUT_ALL = declare("$map.putAll", Types.VOID, Effect.MODIFIES_WORLD, "map", REF, "other", REF);
    public static final NativeDeclaration MAP_COPY = declare("$map.copy", REF, null, "map", REF);
    /** A map as text, {@code {key: value, ...}}, converting keys and values with the given functions. */
    public static final NativeDeclaration MAP_TEXT = declare("$map.text", Types.STRING, null, "map", REF, "keyText", REF, "valueText", REF);
    /** Atomically adds to a numeric value (a missing entry counts as 0) and returns the new value. */
    public static final NativeDeclaration MAP_ADD_LONG = declare("$map.addLong", Types.LONG, Effect.MODIFIES_WORLD, "map", REF, "key", REF, "delta", Types.LONG);
    public static final NativeDeclaration MAP_ADD_INT = declare("$map.addInt", Types.INT, Effect.MODIFIES_WORLD, "map", REF, "key", REF, "delta", Types.INT);
    public static final NativeDeclaration MAP_ADD_DOUBLE = declare("$map.addDouble", Types.DOUBLE, Effect.MODIFIES_WORLD, "map", REF, "key", REF, "delta", Types.DOUBLE);
    public static final NativeDeclaration MAP_FILTER = declare("$map.filter", REF, null, "map", REF, "predicate", REF);
    public static final NativeDeclaration MAP_FOR_EACH = declare("$map.forEach", Types.VOID, Effect.MODIFIES_WORLD, "map", REF, "action", REF);
    public static final NativeDeclaration MAP_SORTED_KEYS_BY_VALUE = declare("$map.keysSortedByValue", REF, null, "map", REF, "descending", Types.BOOL);

    // ------------------------------------------------------------------ strings and values
    public static final NativeDeclaration STRING_CONTAINS = declare("$string.contains", Types.BOOL, Effect.PURE, "text", REF, "part", REF);
    public static final NativeDeclaration VALUE_EQUALS = declare("$value.equals", Types.BOOL, Effect.PURE, "a", REF, "b", REF);

    // ------------------------------------------------------------------ errors
    public static final NativeDeclaration ERROR_MESSAGE = declare("$error.message", Types.STRING, Effect.PURE, "error", REF);
    public static final NativeDeclaration ERROR_KIND = declare("$error.kind", Types.STRING, Effect.PURE, "error", REF);
    public static final NativeDeclaration ERROR_LOCATION = declare("$error.location", Types.STRING, Effect.PURE, "error", REF);
    public static final NativeDeclaration ERROR_NEW = declare("$error.new", REF, Effect.PURE, "message", Types.STRING);

    // ------------------------------------------------------------------ scheduling (bound by the engine)
    public static final NativeDeclaration SCHEDULE_AFTER = declare("$sched.after", Types.VOID, Effect.MODIFIES_WORLD, "delay", Types.LONG, "block", REF);
    public static final NativeDeclaration SCHEDULE_AFTER_FOR = declare("$sched.afterFor", Types.VOID, Effect.MODIFIES_WORLD, "owner", REF, "delay", Types.LONG, "block", REF);
    public static final NativeDeclaration SCHEDULE_EVERY = declare("$sched.every", Types.VOID, Effect.MODIFIES_WORLD, "interval", Types.LONG, "block", REF);
    public static final NativeDeclaration SCHEDULE_EVERY_FOR = declare("$sched.everyFor", Types.VOID, Effect.MODIFIES_WORLD, "owner", REF, "interval", Types.LONG, "block", REF);
    public static final NativeDeclaration SCHEDULE_ASYNC = declare("$sched.async", Types.VOID, Effect.MODIFIES_WORLD, "block", REF);
    public static final NativeDeclaration SCHEDULE_SYNC = declare("$sched.sync", Types.VOID, Effect.MODIFIES_WORLD, "block", REF);

    private Intrinsics() {
    }

    /** Every intrinsic, in declaration order. */
    public static List<NativeDeclaration> all() {
        return List.copyOf(ALL);
    }

    /** Whether the intrinsic is implemented by the engine (scheduling) rather than the runtime. */
    public static boolean isEngineIntrinsic(NativeDeclaration declaration) {
        return declaration.key().startsWith("$sched.");
    }

    private static NativeDeclaration declare(String key, Type returnType, Effect effect, Object... parameters) {
        List<Parameter> list = new ArrayList<>();
        for (int i = 0; i < parameters.length; i += 2) {
            list.add(new Parameter((String) parameters[i], (Type) parameters[i + 1]));
        }
        NativeDeclaration declaration = effect == null
                ? NativeDeclaration.intrinsic(key, list, returnType)
                : NativeDeclaration.intrinsic(key, list, returnType, effect);
        ALL.add(declaration);
        return declaration;
    }
}
