package dev.tachyonscript.engine.database;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.api.value.Values;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An open database, the value of type {@code Database} in scripts.
 *
 * <p>Statements run on the database's own background threads, each with its own connection
 * (one thread for SQLite, which allows a single writer). Connections are opened on first use
 * and reopened when they break.
 */
public final class ScriptDatabase {

    /** Largest number of rows a query returns to a script. */
    static final int MAX_ROWS = 100_000;

    private final DatabaseConfig config;
    private final ExecutorService executor;
    private final ThreadLocal<Connection> connection = new ThreadLocal<>();
    private final List<Connection> opened = Collections.synchronizedList(new ArrayList<>());

    ScriptDatabase(DatabaseConfig config) {
        this.config = config;
        AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(config.poolSize(), task -> {
            Thread thread = new Thread(task, "TachyonScript database " + config.name() + " #" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public String name() {
        return config.name();
    }

    public DatabaseConfig config() {
        return config;
    }

    /** Runs work on the database's threads. */
    <T> Future<T> submit(Callable<T> work) {
        return executor.submit(work);
    }

    // ------------------------------------------------------------------ statements (database threads only)

    List<Object> query(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = prepare(sql, parameters); ResultSet results = statement.executeQuery()) {
            ResultSetMetaData meta = results.getMetaData();
            String[] columns = new String[meta.getColumnCount()];
            for (int i = 0; i < columns.length; i++) {
                columns[i] = meta.getColumnLabel(i + 1);
            }
            List<Object> rows = new ArrayList<>();
            while (results.next()) {
                if (rows.size() >= MAX_ROWS) {
                    throw new SQLException("The query returned more than " + MAX_ROWS + " rows; add a LIMIT.");
                }
                Object[] values = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    values[i] = scriptValue(results.getObject(i + 1));
                }
                rows.add(new DatabaseRow(columns, values));
            }
            return rows;
        }
    }

    int update(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = prepare(sql, parameters)) {
            return statement.executeUpdate();
        }
    }

    private PreparedStatement prepare(String sql, List<Object> parameters) throws SQLException {
        PreparedStatement statement = connection().prepareStatement(sql);
        try {
            int expected = statement.getParameterMetaData().getParameterCount();
            if (expected != parameters.size()) {
                throw new SQLException("The statement has " + expected + " '?' placeholders but " + parameters.size()
                        + " values were given.");
            }
            for (int i = 0; i < parameters.size(); i++) {
                Object value = parameters.get(i);
                switch (value) {
                    case null -> statement.setNull(i + 1, java.sql.Types.NULL);
                    case String text -> statement.setString(i + 1, text);
                    case Integer number -> statement.setInt(i + 1, number);
                    case Long number -> statement.setLong(i + 1, number);
                    case Double number -> statement.setDouble(i + 1, number);
                    case Float number -> statement.setDouble(i + 1, number);
                    case Boolean bool -> statement.setBoolean(i + 1, bool);
                    case UUID uuid -> statement.setString(i + 1, uuid.toString());
                    default -> statement.setString(i + 1, Values.toString(value));
                }
            }
            return statement;
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
    }

    private static Object scriptValue(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Boolean bool -> bool;
            case Integer number -> number.longValue();
            case Short number -> number.longValue();
            case Byte number -> number.longValue();
            case Long number -> number;
            case Float number -> number.doubleValue();
            case Double number -> number;
            case BigDecimal number -> number.scale() <= 0 && number.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                    ? (Object) number.longValue() : (Object) number.doubleValue();
            case java.math.BigInteger number -> number.longValue();
            case java.sql.Timestamp time -> time.getTime();
            case java.sql.Date date -> date.getTime();
            case java.sql.Time time -> time.getTime();
            case byte[] bytes -> java.util.Base64.getEncoder().encodeToString(bytes);
            default -> value.toString();
        };
    }

    private Connection connection() throws SQLException {
        Connection current = connection.get();
        if (current != null && current.isValid(2)) {
            return current;
        }
        if (current != null) {
            opened.remove(current);
            try {
                current.close();
            } catch (SQLException ignored) {
                // Already broken.
            }
        }
        loadDriver();
        if (config.kind() == DatabaseConfig.Kind.SQLITE) {
            try {
                Path file = Path.of(config.url().substring("jdbc:sqlite:".length()));
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
            } catch (java.io.IOException e) {
                throw new SQLException("Cannot create the database folder: " + e.getMessage(), e);
            }
        }
        Properties properties = new Properties();
        properties.putAll(config.properties());
        Connection created = DriverManager.getConnection(config.url(), properties);
        if (config.kind() == DatabaseConfig.Kind.SQLITE) {
            try (Statement statement = created.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
            }
        }
        connection.set(created);
        opened.add(created);
        return created;
    }

    private void loadDriver() {
        String driver = config.kind() == DatabaseConfig.Kind.SQLITE ? "org.sqlite.JDBC" : "com.mysql.cj.jdbc.Driver";
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException missing) {
            // DriverManager may still find a driver through the service loader.
        }
    }

    /** Finishes queued statements (waiting up to {@code seconds}) and closes the connections. */
    void close(long seconds) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        synchronized (opened) {
            for (Connection open : opened) {
                try {
                    open.close();
                } catch (SQLException ignored) {
                    // Closing anyway.
                }
            }
            opened.clear();
        }
    }

    /** A failure message for scripts (SQL errors are expected when a query is wrong). */
    static ScriptError error(String what, SQLException e) {
        return new ScriptError(what + " failed: " + e.getMessage());
    }

    @Override
    public String toString() {
        return "Database(" + config.name() + ")";
    }
}
