package dev.tachyonscript.engine.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Saves variables in an SQL database through JDBC: SQLite (a file, the default) or MySQL /
 * MariaDB. One table holds every saved value, keyed by {@code (owner, scope, name)}.
 *
 * <p>The connection is opened on first use and reopened when it breaks. Writes of one flush go
 * into one transaction.
 */
public final class JdbcBackend implements StorageBackend {

    /** SQL dialect of the database. */
    public enum Dialect {
        SQLITE,
        MYSQL
    }

    private final Dialect dialect;
    private final String url;
    private final Properties properties;
    private final String table;
    private final String description;
    private Connection connection;

    private JdbcBackend(Dialect dialect, String url, Properties properties, String table, String description) {
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid table name '" + table + "'");
        }
        this.dialect = dialect;
        this.url = url;
        this.properties = properties;
        this.table = table;
        this.description = description;
    }

    /** An SQLite database in {@code file} (created if missing). */
    public static JdbcBackend sqlite(Path file, String table) {
        return new JdbcBackend(Dialect.SQLITE, "jdbc:sqlite:" + file.toAbsolutePath(), new Properties(), table,
                "SQLite " + file);
    }

    /** A MySQL or MariaDB database. {@code extra} are additional connection properties. */
    public static JdbcBackend mysql(String host, int port, String database, String user, String password, String table,
                                    Map<String, String> extra) {
        Properties properties = new Properties();
        properties.setProperty("user", Objects.requireNonNull(user, "user"));
        properties.setProperty("password", Objects.requireNonNull(password, "password"));
        properties.setProperty("useUnicode", "true");
        properties.setProperty("characterEncoding", "utf8");
        extra.forEach(properties::setProperty);
        return new JdbcBackend(Dialect.MYSQL, "jdbc:mysql://" + host + ":" + port + "/" + database, properties, table,
                "MySQL " + host + ":" + port + "/" + database);
    }

    @Override
    public Map<String, String> load(String owner) throws StorageException {
        synchronized (this) {
            try {
                Connection current = connection();
                try (PreparedStatement statement = current.prepareStatement(
                        "SELECT scope, name, value FROM " + table + " WHERE owner = ?")) {
                    statement.setString(1, owner);
                    Map<String, String> values = new LinkedHashMap<>();
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            values.put(rows.getString(1) + "::" + rows.getString(2), rows.getString(3));
                        }
                    }
                    return values;
                }
            } catch (SQLException e) {
                reset();
                throw new StorageException("Cannot load saved values from " + description + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void save(List<Row> rows) throws StorageException {
        if (rows.isEmpty()) {
            return;
        }
        synchronized (this) {
            try {
                Connection current = connection();
                boolean autoCommit = current.getAutoCommit();
                current.setAutoCommit(false);
                try (PreparedStatement upsert = current.prepareStatement(upsertSql());
                     PreparedStatement delete = current.prepareStatement(
                             "DELETE FROM " + table + " WHERE owner = ? AND scope = ? AND name = ?")) {
                    long now = System.currentTimeMillis();
                    for (Row row : rows) {
                        if (row.value() == null) {
                            delete.setString(1, row.owner());
                            delete.setString(2, row.scope());
                            delete.setString(3, row.name());
                            delete.addBatch();
                        } else {
                            upsert.setString(1, row.owner());
                            upsert.setString(2, row.scope());
                            upsert.setString(3, row.name());
                            upsert.setString(4, row.value());
                            upsert.setLong(5, now);
                            upsert.addBatch();
                        }
                    }
                    upsert.executeBatch();
                    delete.executeBatch();
                    current.commit();
                } catch (SQLException e) {
                    current.rollback();
                    throw e;
                } finally {
                    current.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                reset();
                throw new StorageException("Cannot save values to " + description + ": " + e.getMessage(), e);
            }
        }
    }

    private String upsertSql() {
        String insert = "INSERT INTO " + table + " (owner, scope, name, value, updated) VALUES (?, ?, ?, ?, ?)";
        return switch (dialect) {
            case SQLITE -> insert + " ON CONFLICT(owner, scope, name) DO UPDATE SET value = excluded.value,"
                    + " updated = excluded.updated";
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE value = VALUES(value), updated = VALUES(updated)";
        };
    }

    private Connection connection() throws SQLException {
        if (connection != null && connection.isValid(2)) {
            return connection;
        }
        reset();
        loadDriver();
        if (dialect == Dialect.SQLITE) {
            try {
                Path file = Path.of(url.substring("jdbc:sqlite:".length()));
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
            } catch (java.io.IOException e) {
                throw new SQLException("Cannot create the database folder: " + e.getMessage(), e);
            }
        }
        Connection opened = DriverManager.getConnection(url, properties);
        try (Statement statement = opened.createStatement()) {
            if (dialect == Dialect.SQLITE) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("CREATE TABLE IF NOT EXISTS " + table + " (owner TEXT NOT NULL, scope TEXT NOT NULL,"
                        + " name TEXT NOT NULL, value TEXT NOT NULL, updated INTEGER NOT NULL,"
                        + " PRIMARY KEY (owner, scope, name))");
            } else {
                statement.execute("CREATE TABLE IF NOT EXISTS " + table + " (owner VARCHAR(64) NOT NULL,"
                        + " scope VARCHAR(128) NOT NULL, name VARCHAR(128) NOT NULL, value LONGTEXT NOT NULL,"
                        + " updated BIGINT NOT NULL, PRIMARY KEY (owner, scope, name)) CHARACTER SET utf8mb4");
            }
        }
        connection = opened;
        return opened;
    }

    private void loadDriver() {
        String driver = dialect == Dialect.SQLITE ? "org.sqlite.JDBC" : "com.mysql.cj.jdbc.Driver";
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException missing) {
            // DriverManager may still find a driver through the service loader (for example MariaDB's).
        }
    }

    private void reset() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Already broken.
            }
            connection = null;
        }
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public synchronized void close() {
        reset();
    }
}
