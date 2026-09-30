package dev.tachyonscript.engine.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps saved values in memory (tests, and servers that disable storage). Nothing survives a restart. */
public final class MemoryBackend implements StorageBackend {

    private final Map<String, Map<String, String>> owners = new ConcurrentHashMap<>();

    @Override
    public Map<String, String> load(String owner) {
        Map<String, String> rows = owners.get(owner);
        if (rows == null) {
            return Map.of();
        }
        synchronized (rows) {
            return new LinkedHashMap<>(rows);
        }
    }

    @Override
    public void save(List<Row> rows) {
        for (Row row : rows) {
            Map<String, String> values = owners.computeIfAbsent(row.owner(), key -> new LinkedHashMap<>());
            synchronized (values) {
                String key = row.scope() + "::" + row.name();
                if (row.value() == null) {
                    values.remove(key);
                } else {
                    values.put(key, row.value());
                }
            }
        }
    }

    /** A saved value, or {@code null} (tests). */
    public String get(String owner, String scope, String name) {
        return load(owner).get(scope + "::" + name);
    }

    @Override
    public String describe() {
        return "memory (not saved across restarts)";
    }

    @Override
    public void close() {
    }
}
