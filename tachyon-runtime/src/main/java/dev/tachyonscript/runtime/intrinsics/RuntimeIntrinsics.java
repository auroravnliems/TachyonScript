package dev.tachyonscript.runtime.intrinsics;

import dev.tachyonscript.api.intrinsic.Intrinsics;
import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.api.natives.NativeFunction;
import dev.tachyonscript.api.natives.ScriptFunction;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.value.ScriptFailure;
import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.value.ScriptList;
import dev.tachyonscript.runtime.value.ScriptMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implementations of the built-in operations of lists, maps, text and caught errors
 * ({@link Intrinsics}). The compiler type-checks every use, so the implementations work on
 * plain objects: lists are {@link List}s (script lists or snapshots returned by natives), maps
 * are {@link Map}s, and function arguments are {@link ScriptFunction}s.
 *
 * <p>Operations that walk a list work on a snapshot, so a function called for each element may
 * change the list without breaking the walk. In-place sorts replace the list's contents with
 * the sorted snapshot.
 */
public final class RuntimeIntrinsics {

    /** Natural order of script values: numbers, text, booleans; nulls last. */
    public static final Comparator<Object> NATURAL = RuntimeIntrinsics::compare;

    private static volatile Bindings bindings;

    private RuntimeIntrinsics() {
    }

    /** Bindings of every intrinsic implemented by the runtime (all but scheduling). */
    public static Bindings bindings() {
        Bindings current = bindings;
        if (current == null) {
            synchronized (RuntimeIntrinsics.class) {
                current = bindings;
                if (current == null) {
                    current = create();
                    bindings = current;
                }
            }
        }
        return current;
    }

    private static Bindings create() {
        Bindings.Builder b = Bindings.builder();
        lists(b);
        maps(b);
        b.bind(Intrinsics.STRING_CONTAINS, (NativeFunction.OfBool) a -> a.getString(0).contains(a.getString(1)));
        b.bind(Intrinsics.VALUE_EQUALS, (NativeFunction.OfBool) a -> Objects.equals(a.getRef(0), a.getRef(1)));
        b.bind(Intrinsics.ERROR_MESSAGE, (NativeFunction.OfRef) a -> ((ScriptFailure) a.getRef(0)).message());
        b.bind(Intrinsics.ERROR_KIND, (NativeFunction.OfRef) a -> ((ScriptFailure) a.getRef(0)).kind());
        b.bind(Intrinsics.ERROR_LOCATION, (NativeFunction.OfRef) a -> ((ScriptFailure) a.getRef(0)).location());
        b.bind(Intrinsics.ERROR_NEW, (NativeFunction.OfRef) a -> {
            String message = a.getString(0);
            return new ScriptFailure(message, ScriptRuntimeException.Kind.THROWN.scriptName(), "",
                    new ScriptRuntimeException(ScriptRuntimeException.Kind.THROWN, message, null));
        });
        return b.build();
    }

    // ================================================================= lists

    private static void lists(Bindings.Builder b) {
        b.bind(Intrinsics.LIST_REMOVE, (NativeFunction.OfBool) a -> mutate(list(a, 0), l -> l.remove(a.getRef(1))));
        b.bind(Intrinsics.LIST_REMOVE_AT, (NativeFunction.OfRef) a -> {
            List<Object> list = list(a, 0);
            int index = a.getInt(1);
            return mutate(list, l -> {
                synchronized (l) {
                    checkIndex(index, l.size());
                    return l.remove(index);
                }
            });
        });
        b.bind(Intrinsics.LIST_INSERT, (NativeFunction.OfVoid) a -> {
            List<Object> list = list(a, 0);
            int index = a.getInt(1);
            Object element = a.getRef(2);
            mutate(list, l -> {
                synchronized (l) {
                    if (index < 0 || index > l.size()) {
                        throw indexError("Cannot insert at position " + index + " of a list of size " + l.size() + ".");
                    }
                    l.add(index, element);
                }
                return null;
            });
        });
        b.bind(Intrinsics.LIST_INDEX_OF, (NativeFunction.OfInt) a -> list(a, 0).indexOf(a.getRef(1)));
        b.bind(Intrinsics.LIST_LAST_INDEX_OF, (NativeFunction.OfInt) a -> list(a, 0).lastIndexOf(a.getRef(1)));
        b.bind(Intrinsics.LIST_CLEAR, (NativeFunction.OfVoid) a -> mutate(list(a, 0), l -> {
            l.clear();
            return null;
        }));
        b.bind(Intrinsics.LIST_ADD_ALL, (NativeFunction.OfVoid) a -> {
            Object[] other = snapshot(list(a, 1));
            mutate(list(a, 0), l -> l.addAll(Arrays.asList(other)));
        });
        b.bind(Intrinsics.LIST_COPY, (NativeFunction.OfRef) a -> copy(list(a, 0)));
        // A fixed-size view of a fresh array: one allocation, read only by the loop that asked for it.
        b.bind(Intrinsics.LIST_SNAPSHOT, (NativeFunction.OfRef) a -> Arrays.asList(snapshot(list(a, 0))));
        b.bind(Intrinsics.LIST_REVERSE, (NativeFunction.OfVoid) a -> {
            List<Object> list = list(a, 0);
            Object[] items = snapshot(list);
            Collections.reverse(Arrays.asList(items));
            replace(list, items);
        });
        b.bind(Intrinsics.LIST_REVERSED, (NativeFunction.OfRef) a -> {
            ScriptList copy = copy(list(a, 0));
            Collections.reverse(copy);
            return copy;
        });
        b.bind(Intrinsics.LIST_SORT, (NativeFunction.OfVoid) a -> {
            List<Object> list = list(a, 0);
            Object[] items = snapshot(list);
            Arrays.sort(items, NATURAL);
            replace(list, items);
        });
        b.bind(Intrinsics.LIST_SORTED, (NativeFunction.OfRef) a -> {
            Object[] items = snapshot(list(a, 0));
            Arrays.sort(items, NATURAL);
            return new ScriptList(Arrays.asList(items));
        });
        b.bind(Intrinsics.LIST_SORT_BY, (NativeFunction.OfVoid) a -> replace(list(a, 0), sortedBy(list(a, 0), fn(a, 1), false)));
        b.bind(Intrinsics.LIST_SORTED_BY, (NativeFunction.OfRef) a ->
                new ScriptList(Arrays.asList(sortedBy(list(a, 0), fn(a, 1), false))));
        b.bind(Intrinsics.LIST_SORT_BY_DESCENDING, (NativeFunction.OfVoid) a ->
                replace(list(a, 0), sortedBy(list(a, 0), fn(a, 1), true)));
        b.bind(Intrinsics.LIST_SORTED_BY_DESCENDING, (NativeFunction.OfRef) a ->
                new ScriptList(Arrays.asList(sortedBy(list(a, 0), fn(a, 1), true))));
        b.bind(Intrinsics.LIST_SORT_WITH, (NativeFunction.OfVoid) a -> {
            List<Object> list = list(a, 0);
            ScriptFunction comparator = fn(a, 1);
            Object[] items = snapshot(list);
            Arrays.sort(items, (x, y) -> ((Integer) comparator.invoke(x, y)));
            replace(list, items);
        });
        b.bind(Intrinsics.LIST_SHUFFLE, (NativeFunction.OfVoid) a -> {
            List<Object> list = list(a, 0);
            Object[] items = snapshot(list);
            Collections.shuffle(Arrays.asList(items), ThreadLocalRandom.current());
            replace(list, items);
        });
        b.bind(Intrinsics.LIST_RANDOM, (NativeFunction.OfRef) a -> {
            Object[] items = snapshot(list(a, 0));
            return items.length == 0 ? null : items[ThreadLocalRandom.current().nextInt(items.length)];
        });
        b.bind(Intrinsics.LIST_FIRST, (NativeFunction.OfRef) a -> {
            Object[] items = snapshot(list(a, 0));
            return items.length == 0 ? null : items[0];
        });
        b.bind(Intrinsics.LIST_LAST, (NativeFunction.OfRef) a -> {
            Object[] items = snapshot(list(a, 0));
            return items.length == 0 ? null : items[items.length - 1];
        });
        b.bind(Intrinsics.LIST_FILTER, (NativeFunction.OfRef) a -> {
            ScriptFunction predicate = fn(a, 1);
            ScriptList result = new ScriptList();
            for (Object item : snapshot(list(a, 0))) {
                if (test(predicate, item)) {
                    result.add(item);
                }
            }
            return result;
        });
        b.bind(Intrinsics.LIST_MAP, (NativeFunction.OfRef) a -> {
            ScriptFunction transform = fn(a, 1);
            Object[] items = snapshot(list(a, 0));
            ScriptList result = new ScriptList(items.length);
            for (Object item : items) {
                result.add(transform.invoke(item));
            }
            return result;
        });
        b.bind(Intrinsics.LIST_FOR_EACH, (NativeFunction.OfVoid) a -> {
            ScriptFunction action = fn(a, 1);
            for (Object item : snapshot(list(a, 0))) {
                action.invoke(item);
            }
        });
        b.bind(Intrinsics.LIST_ANY, (NativeFunction.OfBool) a -> {
            ScriptFunction predicate = fn(a, 1);
            for (Object item : snapshot(list(a, 0))) {
                if (test(predicate, item)) {
                    return true;
                }
            }
            return false;
        });
        b.bind(Intrinsics.LIST_ALL, (NativeFunction.OfBool) a -> {
            ScriptFunction predicate = fn(a, 1);
            for (Object item : snapshot(list(a, 0))) {
                if (!test(predicate, item)) {
                    return false;
                }
            }
            return true;
        });
        b.bind(Intrinsics.LIST_NONE, (NativeFunction.OfBool) a -> {
            ScriptFunction predicate = fn(a, 1);
            for (Object item : snapshot(list(a, 0))) {
                if (test(predicate, item)) {
                    return false;
                }
            }
            return true;
        });
        b.bind(Intrinsics.LIST_COUNT, (NativeFunction.OfInt) a -> {
            ScriptFunction predicate = fn(a, 1);
            int count = 0;
            for (Object item : snapshot(list(a, 0))) {
                if (test(predicate, item)) {
                    count++;
                }
            }
            return count;
        });
        b.bind(Intrinsics.LIST_FIND, (NativeFunction.OfRef) a -> {
            ScriptFunction predicate = fn(a, 1);
            for (Object item : snapshot(list(a, 0))) {
                if (test(predicate, item)) {
                    return item;
                }
            }
            return null;
        });
        b.bind(Intrinsics.LIST_FIND_INDEX, (NativeFunction.OfInt) a -> {
            ScriptFunction predicate = fn(a, 1);
            Object[] items = snapshot(list(a, 0));
            for (int i = 0; i < items.length; i++) {
                if (test(predicate, items[i])) {
                    return i;
                }
            }
            return -1;
        });
        b.bind(Intrinsics.LIST_JOIN, (NativeFunction.OfRef) a -> {
            String separator = a.getString(1);
            Object text = a.getRef(2);
            ScriptFunction toText = text instanceof ScriptFunction function ? function : null;
            StringBuilder out = new StringBuilder();
            Object[] items = snapshot(list(a, 0));
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    out.append(separator);
                }
                out.append(toText != null ? (String) toText.invoke(items[i]) : Values.toString(items[i]));
            }
            return out.toString();
        });
        b.bind(Intrinsics.LIST_SUM_LONG, (NativeFunction.OfLong) a -> {
            long sum = 0;
            for (Object item : snapshot(list(a, 0))) {
                sum += ((Number) item).longValue();
            }
            return sum;
        });
        b.bind(Intrinsics.LIST_SUM_DOUBLE, (NativeFunction.OfDouble) a -> {
            double sum = 0;
            for (Object item : snapshot(list(a, 0))) {
                sum += ((Number) item).doubleValue();
            }
            return sum;
        });
        b.bind(Intrinsics.LIST_AVERAGE, (NativeFunction.OfDouble) a -> {
            Object[] items = snapshot(list(a, 0));
            if (items.length == 0) {
                return 0;
            }
            double sum = 0;
            for (Object item : items) {
                sum += ((Number) item).doubleValue();
            }
            return sum / items.length;
        });
        b.bind(Intrinsics.LIST_MIN, (NativeFunction.OfRef) a -> extreme(snapshot(list(a, 0)), -1));
        b.bind(Intrinsics.LIST_MAX, (NativeFunction.OfRef) a -> extreme(snapshot(list(a, 0)), 1));
        b.bind(Intrinsics.LIST_MIN_BY, (NativeFunction.OfRef) a -> extremeBy(snapshot(list(a, 0)), fn(a, 1), -1));
        b.bind(Intrinsics.LIST_MAX_BY, (NativeFunction.OfRef) a -> extremeBy(snapshot(list(a, 0)), fn(a, 1), 1));
        b.bind(Intrinsics.LIST_TAKE, (NativeFunction.OfRef) a -> {
            Object[] items = snapshot(list(a, 0));
            int count = count(a.getInt(1));
            return new ScriptList(Arrays.asList(items).subList(0, Math.min(count, items.length)));
        });
        b.bind(Intrinsics.LIST_DROP, (NativeFunction.OfRef) a -> {
            Object[] items = snapshot(list(a, 0));
            int count = count(a.getInt(1));
            return new ScriptList(Arrays.asList(items).subList(Math.min(count, items.length), items.length));
        });
        b.bind(Intrinsics.LIST_SUB_LIST, (NativeFunction.OfRef) a -> {
            Object[] items = snapshot(list(a, 0));
            int from = a.getInt(1);
            int to = a.getInt(2);
            if (from < 0 || to > items.length || from > to) {
                throw indexError("subList(" + from + ", " + to + ") is out of bounds for a list of size " + items.length + ".");
            }
            return new ScriptList(Arrays.asList(items).subList(from, to));
        });
        b.bind(Intrinsics.LIST_DISTINCT, (NativeFunction.OfRef) a ->
                new ScriptList(new LinkedHashSet<>(Arrays.asList(snapshot(list(a, 0))))));
        b.bind(Intrinsics.LIST_GROUP_BY, (NativeFunction.OfRef) a -> {
            ScriptFunction key = fn(a, 1);
            ScriptMap groups = new ScriptMap();
            for (Object item : snapshot(list(a, 0))) {
                Object group = key.invoke(item);
                ((ScriptList) groups.computeIfAbsent(group, k -> new ScriptList())).add(item);
            }
            return groups;
        });
        b.bind(Intrinsics.LIST_ASSOCIATE_BY, (NativeFunction.OfRef) a -> {
            ScriptFunction key = fn(a, 1);
            ScriptMap result = new ScriptMap();
            for (Object item : snapshot(list(a, 0))) {
                result.put(key.invoke(item), item);
            }
            return result;
        });
        b.bind(Intrinsics.LIST_REPEAT, (NativeFunction.OfRef) a -> {
            int count = count(a.getInt(0));
            return new ScriptList(Collections.nCopies(count, a.getRef(1)));
        });
    }

    // ================================================================= maps

    private static void maps(Bindings.Builder b) {
        b.bind(Intrinsics.MAP_NEW, (NativeFunction.OfRef) a -> new ScriptMap());
        b.bind(Intrinsics.MAP_GET, (NativeFunction.OfRef) a -> map(a, 0).get(a.getRef(1)));
        b.bind(Intrinsics.MAP_GET_OR_DEFAULT, (NativeFunction.OfRef) a -> map(a, 0).getOrDefault(a.getRef(1), a.getRef(2)));
        b.bind(Intrinsics.MAP_PUT, (NativeFunction.OfVoid) a -> mutate(map(a, 0), m -> m.put(a.getRef(1), a.getRef(2))));
        b.bind(Intrinsics.MAP_PUT_IF_ABSENT, (NativeFunction.OfRef) a ->
                mutate(map(a, 0), m -> m.putIfAbsent(a.getRef(1), a.getRef(2))));
        b.bind(Intrinsics.MAP_REMOVE, (NativeFunction.OfRef) a -> mutate(map(a, 0), m -> m.remove(a.getRef(1))));
        b.bind(Intrinsics.MAP_CONTAINS_KEY, (NativeFunction.OfBool) a -> map(a, 0).containsKey(a.getRef(1)));
        b.bind(Intrinsics.MAP_CONTAINS_VALUE, (NativeFunction.OfBool) a -> map(a, 0).containsValue(a.getRef(1)));
        b.bind(Intrinsics.MAP_SIZE, (NativeFunction.OfInt) a -> map(a, 0).size());
        b.bind(Intrinsics.MAP_CLEAR, (NativeFunction.OfVoid) a -> mutate(map(a, 0), m -> {
            m.clear();
            return null;
        }));
        b.bind(Intrinsics.MAP_KEYS, (NativeFunction.OfRef) a -> {
            Map<Object, Object> map = map(a, 0);
            return map instanceof ScriptMap script ? script.keyList() : new ScriptList(map.keySet());
        });
        b.bind(Intrinsics.MAP_VALUES, (NativeFunction.OfRef) a -> {
            Map<Object, Object> map = map(a, 0);
            return map instanceof ScriptMap script ? script.valueList() : new ScriptList(map.values());
        });
        b.bind(Intrinsics.MAP_ENTRIES, (NativeFunction.OfRef) a -> new ScriptList(Arrays.asList(flat(map(a, 0)))));
        b.bind(Intrinsics.MAP_PUT_ALL, (NativeFunction.OfVoid) a -> {
            Map<Object, Object> other = map(a, 1);
            Map<Object, Object> copy = other instanceof ScriptMap script ? script.toLinkedHashMap() : Map.copyOf(other);
            mutate(map(a, 0), m -> {
                m.putAll(copy);
                return null;
            });
        });
        b.bind(Intrinsics.MAP_TEXT, (NativeFunction.OfRef) a -> {
            Object[] flat = flat(map(a, 0));
            ScriptFunction keyText = fn(a, 1);
            ScriptFunction valueText = fn(a, 2);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < flat.length; i += 2) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append((String) keyText.invoke(flat[i])).append(": ").append((String) valueText.invoke(flat[i + 1]));
            }
            return out.append('}').toString();
        });
        b.bind(Intrinsics.MAP_COPY, (NativeFunction.OfRef) a -> {
            Map<Object, Object> map = map(a, 0);
            return map instanceof ScriptMap script ? script.copy() : new ScriptMap(map);
        });
        b.bind(Intrinsics.MAP_ADD_INT, (NativeFunction.OfInt) a -> (Integer) mutate(map(a, 0),
                m -> m.merge(a.getRef(1), a.getInt(2), (x, y) -> (Integer) x + (Integer) y)));
        b.bind(Intrinsics.MAP_ADD_LONG, (NativeFunction.OfLong) a -> (Long) mutate(map(a, 0),
                m -> m.merge(a.getRef(1), a.getLong(2), (x, y) -> (Long) x + (Long) y)));
        b.bind(Intrinsics.MAP_ADD_DOUBLE, (NativeFunction.OfDouble) a -> (Double) mutate(map(a, 0),
                m -> m.merge(a.getRef(1), a.getDouble(2), (x, y) -> (Double) x + (Double) y)));
        b.bind(Intrinsics.MAP_FILTER, (NativeFunction.OfRef) a -> {
            ScriptFunction predicate = fn(a, 1);
            Object[] flat = flat(map(a, 0));
            ScriptMap result = new ScriptMap();
            for (int i = 0; i < flat.length; i += 2) {
                if (Boolean.TRUE.equals(predicate.invoke(flat[i], flat[i + 1]))) {
                    result.put(flat[i], flat[i + 1]);
                }
            }
            return result;
        });
        b.bind(Intrinsics.MAP_FOR_EACH, (NativeFunction.OfVoid) a -> {
            ScriptFunction action = fn(a, 1);
            Object[] flat = flat(map(a, 0));
            for (int i = 0; i < flat.length; i += 2) {
                action.invoke(flat[i], flat[i + 1]);
            }
        });
        b.bind(Intrinsics.MAP_SORTED_KEYS_BY_VALUE, (NativeFunction.OfRef) a -> {
            Object[] flat = flat(map(a, 0));
            boolean descending = a.getBool(1);
            Integer[] order = new Integer[flat.length / 2];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Comparator<Integer> byValue = (x, y) -> compare(flat[2 * x + 1], flat[2 * y + 1]);
            Arrays.sort(order, descending ? byValue.reversed() : byValue);
            ScriptList keys = new ScriptList(order.length);
            for (Integer index : order) {
                keys.add(flat[2 * index]);
            }
            return keys;
        });
    }

    // ================================================================= helpers

    @SuppressWarnings("unchecked")
    private static List<Object> list(Arguments arguments, int index) {
        return (List<Object>) arguments.getRef(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Arguments arguments, int index) {
        return (Map<Object, Object>) arguments.getRef(index);
    }

    private static ScriptFunction fn(Arguments arguments, int index) {
        return (ScriptFunction) arguments.getRef(index);
    }

    private static boolean test(ScriptFunction predicate, Object item) {
        return Boolean.TRUE.equals(predicate.invoke(item));
    }

    /** The elements of a list, copied atomically for script lists. */
    public static Object[] snapshot(List<?> list) {
        if (list instanceof ScriptList script) {
            return script.snapshot();
        }
        synchronized (list) {
            return list.toArray();
        }
    }

    private static Object[] flat(Map<Object, Object> map) {
        if (map instanceof ScriptMap script) {
            return script.flatSnapshot();
        }
        List<Object> flat = new ArrayList<>(map.size() * 2);
        map.forEach((key, value) -> {
            flat.add(key);
            flat.add(value);
        });
        return flat.toArray();
    }

    private static ScriptList copy(List<Object> list) {
        return new ScriptList(Arrays.asList(snapshot(list)));
    }

    /** Replaces a list's contents in one step. */
    private static void replace(List<Object> list, Object[] items) {
        mutate(list, l -> {
            synchronized (l) {
                l.clear();
                l.addAll(Arrays.asList(items));
            }
            return null;
        });
    }

    private static Object[] sortedBy(List<Object> list, ScriptFunction key, boolean descending) {
        Object[] items = snapshot(list);
        Object[] keys = new Object[items.length];
        for (int i = 0; i < items.length; i++) {
            keys[i] = key.invoke(items[i]);
        }
        Integer[] order = new Integer[items.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Comparator<Integer> byKey = (x, y) -> compare(keys[x], keys[y]);
        Arrays.sort(order, descending ? byKey.reversed() : byKey);
        Object[] sorted = new Object[items.length];
        for (int i = 0; i < order.length; i++) {
            sorted[i] = items[order[i]];
        }
        return sorted;
    }

    private static Object extreme(Object[] items, int sign) {
        Object best = null;
        for (Object item : items) {
            if (best == null || compare(item, best) * sign > 0) {
                best = item;
            }
        }
        return best;
    }

    private static Object extremeBy(Object[] items, ScriptFunction key, int sign) {
        Object best = null;
        Object bestKey = null;
        boolean found = false;
        for (Object item : items) {
            Object itemKey = key.invoke(item);
            if (!found || compare(itemKey, bestKey) * sign > 0) {
                best = item;
                bestKey = itemKey;
                found = true;
            }
        }
        return best;
    }

    /** Compares two values of the same script type; nulls sort last. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        if (a instanceof Integer x && b instanceof Integer y) {
            return Integer.compare(x, y);
        }
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        if (a instanceof Number x && b instanceof Number y) {
            if ((x instanceof Integer || x instanceof Long) && (y instanceof Integer || y instanceof Long)) {
                return Long.compare(x.longValue(), y.longValue());
            }
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Comparable comparable && a.getClass() == b.getClass()) {
            return comparable.compareTo(b);
        }
        return Values.toString(a).compareTo(Values.toString(b));
    }

    private static int count(int count) {
        if (count < 0) {
            throw indexError("The count must not be negative, got " + count + ".");
        }
        return count;
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw indexError("List index " + index + " is out of bounds (size " + size + ").");
        }
    }

    private static ScriptRuntimeException indexError(String message) {
        return new ScriptRuntimeException(ScriptRuntimeException.Kind.INDEX, message, null);
    }

    /** Runs a change, reporting read-only collections (such as lists returned by the server) clearly. */
    private static <C, R> R mutate(C collection, java.util.function.Function<C, R> change) {
        try {
            return change.apply(collection);
        } catch (UnsupportedOperationException readOnly) {
            throw new ScriptRuntimeException(ScriptRuntimeException.Kind.SCRIPT,
                    "This " + (collection instanceof Map ? "map" : "list") + " cannot be modified.", readOnly);
        }
    }
}
