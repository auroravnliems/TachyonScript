package dev.tachyonscript.runtime.value;

import dev.tachyonscript.api.value.Values;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The map created by map literals and map operations of scripts: keys keep their insertion
 * order, and every operation is synchronized so that a map shared between threads (a
 * top-level variable on Folia) stays consistent. Iteration always works on a snapshot.
 *
 * <p>Implements {@link Map} so that natives can accept script maps directly; the views
 * ({@link #keySet()}, {@link #values()}, {@link #entrySet()}) are snapshots, not live views.
 */
public final class ScriptMap implements Map<Object, Object> {

    private final LinkedHashMap<Object, Object> entries;

    public ScriptMap() {
        this.entries = new LinkedHashMap<>();
    }

    public ScriptMap(int capacity) {
        this.entries = LinkedHashMap.newLinkedHashMap(capacity);
    }

    public ScriptMap(Map<?, ?> copy) {
        this.entries = new LinkedHashMap<>(copy);
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    @Override
    public synchronized boolean containsValue(Object value) {
        return entries.containsValue(value);
    }

    @Override
    public synchronized Object get(Object key) {
        return entries.get(key);
    }

    @Override
    public synchronized Object getOrDefault(Object key, Object fallback) {
        return entries.getOrDefault(key, fallback);
    }

    @Override
    public synchronized Object put(Object key, Object value) {
        return entries.put(key, value);
    }

    @Override
    public synchronized Object putIfAbsent(Object key, Object value) {
        return entries.putIfAbsent(key, value);
    }

    @Override
    public synchronized Object remove(Object key) {
        return entries.remove(key);
    }

    @Override
    public synchronized void putAll(Map<?, ?> other) {
        entries.putAll(other);
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }

    @Override
    public synchronized Object compute(Object key, BiFunction<? super Object, ? super Object, ?> function) {
        return entries.compute(key, function);
    }

    @Override
    public synchronized Object merge(Object key, Object value, BiFunction<? super Object, ? super Object, ?> function) {
        return entries.merge(key, value, function);
    }

    @Override
    public synchronized Object computeIfAbsent(Object key, Function<? super Object, ?> function) {
        return entries.computeIfAbsent(key, function);
    }

    /** Snapshot of the keys, in order. */
    @Override
    public synchronized Set<Object> keySet() {
        return new java.util.LinkedHashSet<>(entries.keySet());
    }

    /** Snapshot of the values, in key order. */
    @Override
    public synchronized Collection<Object> values() {
        return new ArrayList<>(entries.values());
    }

    /** Snapshot of the entries, in order. */
    @Override
    public synchronized Set<Entry<Object, Object>> entrySet() {
        Set<Entry<Object, Object>> copy = new java.util.LinkedHashSet<>();
        for (Entry<Object, Object> entry : entries.entrySet()) {
            copy.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
        }
        return copy;
    }

    /** Keys and values alternating, {@code [k0, v0, k1, v1, ...]}, taken atomically. */
    public synchronized Object[] flatSnapshot() {
        Object[] flat = new Object[entries.size() * 2];
        int i = 0;
        for (Entry<Object, Object> entry : entries.entrySet()) {
            flat[i++] = entry.getKey();
            flat[i++] = entry.getValue();
        }
        return flat;
    }

    /** The keys as a new script list, in order. */
    public synchronized ScriptList keyList() {
        return new ScriptList(entries.keySet());
    }

    /** The values as a new script list, in key order. */
    public synchronized ScriptList valueList() {
        return new ScriptList(entries.values());
    }

    public synchronized ScriptMap copy() {
        return new ScriptMap(entries);
    }

    /** A plain copy of the entries (for encoding and natives that need a stable view). */
    public synchronized LinkedHashMap<Object, Object> toLinkedHashMap() {
        return new LinkedHashMap<>(entries);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Map<?, ?> map)) {
            return false;
        }
        Map<Object, Object> mine = toLinkedHashMap();
        Map<?, ?> theirs = other instanceof ScriptMap script ? script.toLinkedHashMap() : map;
        return mine.equals(theirs);
    }

    @Override
    public int hashCode() {
        return toLinkedHashMap().hashCode();
    }

    /** Text form used by templates: {@code {a: 1, b: 2}}. */
    @Override
    public String toString() {
        Object[] flat = flatSnapshot();
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < flat.length; i += 2) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(Values.toString(flat[i])).append(": ").append(flat[i + 1] == this ? "(this map)"
                    : Values.toString(flat[i + 1]));
        }
        return out.append('}').toString();
    }
}
