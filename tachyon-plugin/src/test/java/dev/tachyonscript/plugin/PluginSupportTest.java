package dev.tachyonscript.plugin;

import dev.tachyonscript.api.addon.TachyonAddon;
import dev.tachyonscript.api.registry.Bindings;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.engine.EngineOptions;
import dev.tachyonscript.engine.LoadMode;
import dev.tachyonscript.language.source.SourceFile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSupportTest {

    @TempDir
    Path directory;

    private final List<String> warnings = new ArrayList<>();

    private Logger logger() {
        Logger logger = Logger.getLogger("TachyonScriptPluginTest");
        logger.setUseParentHandlers(false);
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
        }
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static YamlConfiguration defaults() throws IOException {
        try (var in = Objects.requireNonNull(PluginSupportTest.class.getResourceAsStream("/config.yml"));
             var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void defaultConfigurationMatchesEngineDefaults() throws IOException {
        TachyonSettings settings = TachyonSettings.from(defaults(), logger());
        assertEquals(List.of(), warnings);
        assertEquals(LoadMode.LENIENT, settings.mode());
        EngineOptions options = settings.engineOptions();
        assertEquals(EngineOptions.DEFAULT.limits().maxCallDepth(), options.limits().maxCallDepth());
        assertEquals(EngineOptions.DEFAULT.limits().maxExecutionNanos(), options.limits().maxExecutionNanos());
        assertEquals(EngineOptions.DEFAULT.slowThresholdNanos(), options.slowThresholdNanos());
        assertFalse(options.debug());
    }

    @Test
    void replacesInvalidValuesWithDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("reload.mode", "sometimes");
        config.set("safety.recursion-limit", -1);
        config.set("safety.max-execution-time-ms", 0);
        config.set("performance.slow-execution-warning-ms", -5);
        config.set("runtime.backend", "bytecode");
        TachyonSettings settings = TachyonSettings.from(config, logger());
        assertEquals(LoadMode.LENIENT, settings.mode());
        assertEquals(128, settings.recursionLimit());
        assertEquals(1000, settings.maxExecutionMillis());
        assertEquals(5, settings.slowWarningMillis());
        assertEquals(5, warnings.size(), warnings::toString);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("bytecode")), warnings::toString);
    }

    @Test
    void readsStrictMode() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("reload.mode", "STRICT");
        assertEquals(LoadMode.STRICT, TachyonSettings.from(config, logger()).mode());
    }

    @Test
    void readsScriptsRecursivelySkippingDisabledOnes() throws IOException {
        Path scripts = directory.resolve("scripts");
        Files.createDirectories(scripts.resolve("shop/-old"));
        Files.writeString(scripts.resolve("join.tys"), "event player.join {}");
        Files.writeString(scripts.resolve("shop/buy.tys"), "event player.quit {}");
        Files.writeString(scripts.resolve("shop/-old/sell.tys"), "broken");
        Files.writeString(scripts.resolve("-disabled.tys"), "broken");
        Files.writeString(scripts.resolve("notes.txt"), "not a script");
        List<SourceFile> files = new ScriptDirectory(scripts).read();
        assertEquals(List.of("join.tys", "shop/buy.tys"), files.stream().map(SourceFile::path).toList());
    }

    @Test
    void createsAMissingScriptsDirectory() throws IOException {
        Path scripts = directory.resolve("new/scripts");
        assertEquals(List.of(), new ScriptDirectory(scripts).read());
        assertTrue(Files.isDirectory(scripts));
    }

    @Test
    void refusesHugeFiles() throws IOException {
        Path scripts = directory.resolve("scripts");
        Files.createDirectories(scripts);
        Files.write(scripts.resolve("huge.tys"), new byte[5 * 1024 * 1024]);
        IOException error = assertThrows(IOException.class, () -> new ScriptDirectory(scripts).read());
        assertTrue(error.getMessage().contains("huge.tys"), error.getMessage());
    }

    @Test
    void addonRegistrationClosesAtTheFirstLoad() {
        PendingAddons pending = new PendingAddons();
        TachyonAddon addon = new TachyonAddon() {
            @Override
            public String name() {
                return "Test";
            }

            @Override
            public void declare(SymbolRegistry.Builder registry) {
            }

            @Override
            public void bind(Bindings.Builder bindings) {
            }
        };
        pending.register(addon);
        assertEquals(List.of(addon), pending.close());
        IllegalStateException late = assertThrows(IllegalStateException.class, () -> pending.register(addon));
        assertTrue(late.getMessage().contains("onEnable"), late.getMessage());
    }

    @Test
    void pluginDescriptionDeclaresFoliaSupportAndPermissions() throws IOException {
        YamlConfiguration plugin;
        try (var in = Objects.requireNonNull(PluginSupportTest.class.getResourceAsStream("/plugin.yml"));
             var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            plugin = YamlConfiguration.loadConfiguration(reader);
        }
        assertEquals("dev.tachyonscript.plugin.TachyonPlugin", plugin.getString("main"));
        assertTrue(plugin.getBoolean("folia-supported"));
        assertTrue(plugin.isConfigurationSection("commands.tys"));
        // Bukkit's configuration API reads "tachyonscript.admin" as a nested path.
        for (String permission : List.of("*", "admin", "reload", "profile", "debug")) {
            assertTrue(plugin.isConfigurationSection("permissions.tachyonscript." + permission), permission);
        }
        assertFalse(plugin.getString("version").contains("${"), "version is expanded at build time");
    }
}
