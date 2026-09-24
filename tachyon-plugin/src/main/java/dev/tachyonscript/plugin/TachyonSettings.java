package dev.tachyonscript.plugin;

import dev.tachyonscript.compiler.CompilerOptions;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadMode;
import dev.tachyonscript.runtime.interpreter.RuntimeLimits;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Validated configuration. Invalid values are reported and replaced by defaults instead of
 * failing the plugin.
 */
record TachyonSettings(LoadMode mode, int recursionLimit, long maxExecutionMillis, long slowWarningMillis,
                       boolean debug) {

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
        long slow = config.getLong("performance.slow-execution-warning-ms", 5);
        if (slow < 0) {
            logger.warning("performance.slow-execution-warning-ms must not be negative; using 5.");
            slow = 5;
        }
        String backend = config.getString("runtime.backend", "interpreter");
        if (!backend.equalsIgnoreCase("interpreter")) {
            logger.warning("runtime.backend '" + backend + "' is not available yet; using 'interpreter'.");
        }
        return new TachyonSettings(mode, recursion, maxTime, slow, config.getBoolean("debug.enabled", false));
    }

    private static long positive(long value, long fallback, String key, Logger logger) {
        if (value <= 0) {
            logger.warning(key + " must be positive; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    EngineOptions engineOptions() {
        RuntimeLimits limits = new RuntimeLimits(recursionLimit, maxExecutionMillis * 1_000_000L,
                RuntimeLimits.DEFAULT.loopCheckInterval());
        return new EngineOptions(mode, CompilerOptions.DEFAULT, limits, slowWarningMillis * 1_000_000L, debug);
    }
}
