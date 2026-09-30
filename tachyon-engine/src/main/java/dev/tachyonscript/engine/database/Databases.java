package dev.tachyonscript.engine.database;

import dev.tachyonscript.api.natives.ScriptError;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The databases of an engine: those configured by the server owner ({@code Database("main")})
 * and SQLite files opened by scripts ({@code Database.sqlite("shop.db")}, confined to one
 * folder). Each is opened once and shared by every script; all are closed when the engine
 * shuts down.
 */
public final class Databases {

    private final Map<String, DatabaseConfig> configured;
    private final Path folder;
    private final Map<String, ScriptDatabase> open = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public Databases(Map<String, DatabaseConfig> configured, Path folder) {
        this.configured = Map.copyOf(configured);
        this.folder = folder.toAbsolutePath().normalize();
    }

    /** The configured database with this name. */
    public ScriptDatabase named(String name) {
        DatabaseConfig config = configured.get(name);
        if (config == null) {
            throw new ScriptError("No database named '" + name + "' is configured"
                    + (configured.isEmpty() ? " (add it under 'databases' in config.yml)."
                    : "; configured: " + String.join(", ", configured.keySet().stream().sorted().toList()) + "."));
        }
        return open("config:" + name, config);
    }

    /** An SQLite file in the databases folder. */
    public ScriptDatabase sqlite(String file) {
        String cleaned = file.strip().replace('\\', '/');
        if (!cleaned.matches("[A-Za-z0-9_\\-./]+") || cleaned.startsWith("/") || cleaned.contains("..")) {
            throw new ScriptError("Invalid database file '" + file + "': use a name such as 'shop.db' or 'games/arena.db'.");
        }
        Path path = folder.resolve(cleaned).normalize();
        if (!path.startsWith(folder)) {
            throw new ScriptError("The database file '" + file + "' leaves the databases folder.");
        }
        return open("sqlite:" + path, DatabaseConfig.sqlite(cleaned, path));
    }

    private ScriptDatabase open(String key, DatabaseConfig config) {
        if (closed) {
            throw new ScriptError("Databases are closed (the server is stopping).");
        }
        return open.computeIfAbsent(key, k -> new ScriptDatabase(config));
    }

    /** Names of the configured databases. */
    public List<String> configuredNames() {
        return configured.keySet().stream().sorted().toList();
    }

    /** Finishes queued statements and closes every database. */
    public void close() {
        closed = true;
        for (ScriptDatabase database : open.values()) {
            database.close(10);
        }
        open.clear();
    }
}
