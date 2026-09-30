package dev.tachyonscript.engine.storage;

import java.util.List;
import java.util.Map;

/**
 * Where saved variables are kept: rows of {@code (scope, owner, name, value)}. The scope is
 * the module name, the owner is empty for {@code persistent} variables and the player's UUID
 * for {@code playerdata}, the name is the variable name and the value its encoded text.
 *
 * <p>Methods block and are only called from storage threads, never from a server thread.
 * Implementations must be thread-safe.
 */
public interface StorageBackend extends AutoCloseable {

    /** A saved value; {@code value} is {@code null} to delete the row. */
    record Row(String scope, String owner, String name, String value) {
    }

    /** Every saved value of an owner ({@code ""} for persistent variables), keyed by {@code scope::name}. */
    Map<String, String> load(String owner) throws StorageException;

    /** Writes (or deletes) rows in one transaction. */
    void save(List<Row> rows) throws StorageException;

    /** A short description for logs, e.g. {@code SQLite plugins/TachyonScript/data.db}. */
    String describe();

    @Override
    void close();

    /** A storage failure; the message is logged. */
    final class StorageException extends Exception {
        private static final long serialVersionUID = 1L;

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
