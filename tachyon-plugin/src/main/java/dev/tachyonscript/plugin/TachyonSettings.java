package dev.tachyonscript.plugin;

import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.engine.CommandMessages;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadMode;
import dev.tachyonscript.engine.database.DatabaseConfig;
import dev.tachyonscript.engine.storage.JdbcBackend;
import dev.tachyonscript.engine.storage.StorageBackend;
import dev.tachyonscript.runtime.ExecutionBackend;
import dev.tachyonscript.runtime.interpreter.RuntimeLimits;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Validated configuration. Invalid values are reported and replaced by defaults instead of
 * failing the plugin.
 *
 * @param storage      where {@code persistent} and {@code playerdata} variables are saved
 * @param flushSeconds how often changed saved variables are written
 * @param databases    databases scripts open with {@code Database("name")}
 * @param messages     overridden messages of script commands, by key
 * @param backend      how scripts execute: the interpreter (reference) or JVM bytecode
 */
record TachyonSettings(LoadMode mode, int recursionLimit, long maxExecutionMillis, long slowWarningMillis,
                       boolean debug, Storage storage, long flushSeconds, Map<String, Database> databases,
                       Map<String, String> messages, ExecutionBackend backend) {

    /** Keys of {@code commands.messages}. */
    private static final Set<String> MESSAGE_KEYS = Set.of("usage", "no-permission", "player-only", "cooldown",
            "not-a-number", "invalid-argument", "missing-argument", "too-many-arguments", "error", "subcommands");

    /** The table saved variables are kept in. */
    static final String DATA_TABLE = "tys_data";

    /** Where saved variables go: {@code sqlite} (a file), {@code mysql} or {@code memory}. */
    record Storage(String type, String file, Server server) {
    }

    /** A database server. */
    record Server(String host, int port, String database, String user, String password, Map<String, String> properties) {
    }

    /** A database for scripts: an SQLite file or a MySQL server. */
    record Database(String name, String type, String file, Server server, int poolSize) {
    }

    static TachyonSettings from(ConfigurationSection config, Logger logger) {
        String modeName = config.getString("reload.mode", "lenient").toLowerCase(Locale.ROOT);
        LoadMode mode = switch (modeName) {
            case "strict" -> LoadMode.STRICT;
            case "lenient" -> LoadMode.LENIENT;
            default -> {
                logger.warning("Unknown reload.mode '" + modeName + "'; using 'lenient'.");
                yield LoadMode.LENIENT;
            }
        };
        int recursion = (int) positive(config.getInt("safety.recursion-limit", 128), 128, "safety.recursion-limit", logger);
        long maxTime = positive(config.getLong("safety.max-execution-time-ms", 1000), 1000,
                "safety.max-execution-time-ms", logger);
        long slow = config.getLong("performance.slow-execution-warning-ms", 50);
        if (slow < 0) {
            logger.warning("performance.slow-execution-warning-ms must not be negative; using 50.");
            slow = 50;
        }
        // Explicit opt-in also silences legacy configs with a 5/10 ms threshold.
        if (!config.getBoolean("performance.slow-execution-warnings", false)) slow = 0;
        slow = Math.min(slow, Long.MAX_VALUE / 1_000_000L);
        String backendName = config.getString("runtime.backend", "interpreter").toLowerCase(Locale.ROOT);
        ExecutionBackend backend = switch (backendName) {
            case "interpreter" -> ExecutionBackend.INTERPRETER;
            case "bytecode" -> ExecutionBackend.BYTECODE;
            default -> {
                logger.warning("Unknown runtime.backend '" + backendName + "' (interpreter or bytecode); using 'interpreter'.");
                yield ExecutionBackend.INTERPRETER;
            }
        };
        long flush = config.getLong("storage.flush-interval-seconds", 30);
        if (flush < 0) {
            logger.warning("storage.flush-interval-seconds must not be negative; using 30.");
            flush = 30;
        }
        return new TachyonSettings(mode, recursion, maxTime, slow, config.getBoolean("debug.enabled", false),
                storage(config, logger), flush, databases(config.getConfigurationSection("databases"), logger),
                messages(config.getConfigurationSection("commands.messages"), logger), backend);
    }

    private static Storage storage(ConfigurationSection config, Logger logger) {
        String type = config.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        if (!type.equals("sqlite") && !type.equals("mysql") && !type.equals("memory")) {
            logger.warning("Unknown storage.type '" + type + "' (sqlite, mysql or memory); using 'sqlite'.");
            type = "sqlite";
        }
        return new Storage(type, config.getString("storage.sqlite-file", "data.db"),
                server(config.getConfigurationSection("storage.mysql")));
    }

    private static Server server(ConfigurationSection section) {
        if (section == null) {
            return new Server("localhost", 3306, "minecraft", "root", "", Map.of());
        }
        Map<String, String> properties = new LinkedHashMap<>();
        ConfigurationSection extra = section.getConfigurationSection("properties");
        if (extra != null) {
            for (String key : extra.getKeys(false)) {
                properties.put(key, String.valueOf(extra.get(key)));
            }
        }
        return new Server(section.getString("host", "localhost"), section.getInt("port", 3306),
                section.getString("database", "minecraft"), section.getString("user", "root"),
                section.getString("password", ""), properties);
    }

    private static Map<String, Database> databases(ConfigurationSection section, Logger logger) {
        Map<String, Database> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection database = section.getConfigurationSection(name);
            if (database == null) {
                logger.warning("databases." + name + " must be a section with a 'type'; ignored.");
                continue;
            }
            String type = database.getString("type", "sqlite").toLowerCase(Locale.ROOT);
            if (!type.equals("sqlite") && !type.equals("mysql")) {
                logger.warning("databases." + name + ".type must be sqlite or mysql, not '" + type + "'; ignored.");
                continue;
            }
            int pool = database.getInt("pool-size", 2);
            if (pool < 1 || pool > 32) {
                logger.warning("databases." + name + ".pool-size must be between 1 and 32; using 2.");
                pool = 2;
            }
            result.put(name, new Database(name, type, database.getString("file", "databases/" + name + ".db"),
                    server(database), pool));
        }
        return result;
    }

    private static Map<String, String> messages(ConfigurationSection section, Logger logger) {
        Map<String, String> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            if (!MESSAGE_KEYS.contains(key)) {
                logger.warning("Unknown message commands.messages." + key + "; ignored.");
                continue;
            }
            result.put(key, section.getString(key, ""));
        }
        return result;
    }

    private static long positive(long value, long fallback, String key, Logger logger) {
        if (value <= 0) {
            logger.warning(key + " must be positive; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    /** Options without storage or databases (they need the plugin folder). */
    EngineOptions engineOptions() {
        RuntimeLimits limits = new RuntimeLimits(recursionLimit, maxExecutionMillis * 1_000_000L,
                RuntimeLimits.DEFAULT.loopCheckInterval());
        return new EngineOptions(mode, CompilerOptions.DEFAULT, limits, slowWarningMillis * 1_000_000L, debug)
                .withMessages(CommandMessages.DEFAULT.with(messages)).withBackend(backend);
    }

    /** The complete engine options, with files relative to the plugin folder. */
    EngineOptions engineOptions(Path dataFolder) {
        StorageBackend backend = switch (storage.type()) {
            case "memory" -> null;
            case "mysql" -> JdbcBackend.mysql(storage.server().host(), storage.server().port(), storage.server().database(),
                    storage.server().user(), storage.server().password(), DATA_TABLE, storage.server().properties());
            default -> JdbcBackend.sqlite(dataFolder.resolve(storage.file()), DATA_TABLE);
        };
        Map<String, DatabaseConfig> configs = new LinkedHashMap<>();
        for (Database database : databases.values()) {
            configs.put(database.name(), database.type().equals("mysql")
                    ? DatabaseConfig.mysql(database.name(), database.server().host(), database.server().port(),
                    database.server().database(), database.server().user(), database.server().password(),
                    database.server().properties(), database.poolSize())
                    : DatabaseConfig.sqlite(database.name(), dataFolder.resolve(database.file())));
        }
        return engineOptions().withStorage(backend, flushSeconds * 1000).withDatabases(configs, dataFolder.resolve("databases"));
    }
}
