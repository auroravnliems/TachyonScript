package dev.tachyonscript.engine;

import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.engine.database.DatabaseConfig;
import dev.tachyonscript.engine.storage.StorageBackend;
import dev.tachyonscript.runtime.interpreter.RuntimeLimits;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Engine configuration.
 *
 * @param mode                behaviour when some scripts fail
 * @param compiler            compiler limits
 * @param limits              runtime safety limits
 * @param slowThresholdNanos  executions slower than this are reported (0 disables the check)
 * @param debug               include Java causes in runtime error reports
 * @param storage             where {@code persistent} and {@code playerdata} variables are saved, or
 *                            {@code null} to keep them in memory only
 * @param flushIntervalMillis how often changed saved variables are written (0 = only on shutdown)
 * @param messages            messages sent by script commands
 * @param databases           databases scripts open with {@code Database("name")}, by name
 * @param databaseFolder      the folder of SQLite files opened with {@code Database.sqlite("file")}
 */
public record EngineOptions(LoadMode mode, CompilerOptions compiler, RuntimeLimits limits, long slowThresholdNanos,
                            boolean debug, StorageBackend storage, long flushIntervalMillis, CommandMessages messages,
                            Map<String, DatabaseConfig> databases, Path databaseFolder) {

    public static final EngineOptions DEFAULT = new EngineOptions(LoadMode.LENIENT, CompilerOptions.DEFAULT,
            RuntimeLimits.DEFAULT, 5_000_000L, false, null, 30_000L, CommandMessages.DEFAULT, Map.of(),
            Path.of("databases"));

    public EngineOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(messages, "messages");
        databases = Map.copyOf(databases);
        Objects.requireNonNull(databaseFolder, "databaseFolder");
        if (slowThresholdNanos < 0) {
            throw new IllegalArgumentException("slowThresholdNanos must not be negative");
        }
        if (flushIntervalMillis < 0) {
            throw new IllegalArgumentException("flushIntervalMillis must not be negative");
        }
    }

    public EngineOptions(LoadMode mode, CompilerOptions compiler, RuntimeLimits limits, long slowThresholdNanos,
                         boolean debug) {
        this(mode, compiler, limits, slowThresholdNanos, debug, null, 30_000L, CommandMessages.DEFAULT, Map.of(),
                Path.of("databases"));
    }

    /** These options with another storage and flush interval. */
    public EngineOptions withStorage(StorageBackend newStorage, long newFlushIntervalMillis) {
        return new EngineOptions(mode, compiler, limits, slowThresholdNanos, debug, newStorage, newFlushIntervalMillis,
                messages, databases, databaseFolder);
    }

    /** These options with other command messages. */
    public EngineOptions withMessages(CommandMessages newMessages) {
        return new EngineOptions(mode, compiler, limits, slowThresholdNanos, debug, storage, flushIntervalMillis,
                newMessages, databases, databaseFolder);
    }

    /** These options with other databases for scripts. */
    public EngineOptions withDatabases(Map<String, DatabaseConfig> newDatabases, Path newDatabaseFolder) {
        return new EngineOptions(mode, compiler, limits, slowThresholdNanos, debug, storage, flushIntervalMillis,
                messages, newDatabases, newDatabaseFolder);
    }
}
