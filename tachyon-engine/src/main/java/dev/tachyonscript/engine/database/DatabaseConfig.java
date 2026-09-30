package dev.tachyonscript.engine.database;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A database scripts can open with {@code Database("name")}, as configured by the server owner.
 *
 * @param name       the name scripts use
 * @param kind       SQLite (a file) or MySQL / MariaDB (a server)
 * @param url        the JDBC URL
 * @param properties connection properties (user, password, ...)
 * @param poolSize   how many connections (and background threads) run queries; always 1 for SQLite
 */
public record DatabaseConfig(String name, Kind kind, String url, Map<String, String> properties, int poolSize) {

    /** The kind of database. */
    public enum Kind {
        SQLITE,
        MYSQL
    }

    public DatabaseConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(url, "url");
        properties = Map.copyOf(properties);
        if (kind == Kind.SQLITE) {
            poolSize = 1;
        }
        if (poolSize < 1 || poolSize > 32) {
            throw new IllegalArgumentException("Database pool size must be between 1 and 32, not " + poolSize);
        }
    }

    /** An SQLite database file (created if missing). */
    public static DatabaseConfig sqlite(String name, Path file) {
        return new DatabaseConfig(name, Kind.SQLITE, "jdbc:sqlite:" + file.toAbsolutePath(), Map.of(), 1);
    }

    /** A MySQL or MariaDB database; {@code extra} are additional connection properties. */
    public static DatabaseConfig mysql(String name, String host, int port, String database, String user, String password,
                                       Map<String, String> extra, int poolSize) {
        Map<String, String> properties = new LinkedHashMap<>(extra);
        properties.put("user", user);
        properties.put("password", password == null ? "" : password);
        properties.putIfAbsent("useUnicode", "true");
        properties.putIfAbsent("characterEncoding", "utf8");
        return new DatabaseConfig(name, Kind.MYSQL, "jdbc:mysql://" + host + ":" + port + "/" + database, properties,
                poolSize);
    }

    /** Where the database is, for messages (never the password). */
    public String describe() {
        return switch (kind) {
            case SQLITE -> "SQLite " + url.substring("jdbc:sqlite:".length());
            case MYSQL -> "MySQL " + url.substring("jdbc:mysql://".length());
        };
    }
}
