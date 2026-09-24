package dev.tachyonscript.plugin;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.api.addon.AddonRegistrar;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.addon.AddonAssembly;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.diagnostic.Severity;
import dev.tachyonscript.platform.paper.PaperPlatform;
import dev.tachyonscript.platform.paper.PlatformCapabilities;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin entry point: registers {@code /tys} and the addon registrar, then loads the scripts
 * directory once the server has finished starting.
 *
 * <p>Loading waits for the first server tick so that plugins depending on TachyonScript can
 * register addons in their {@code onEnable}; players cannot join before that anyway. The
 * first load runs on that tick. Reloads run on an async thread: compilation never blocks a
 * tick (or a Folia region), and the engine swaps the new scripts in atomically when they
 * are ready.
 */
public final class TachyonPlugin extends JavaPlugin {

    static final String SCRIPTS_PREFIX = "scripts/";

    private final AtomicBoolean reloading = new AtomicBoolean();
    private final PendingAddons pendingAddons = new PendingAddons();
    private TachyonSettings settings;
    private ScriptDirectory scripts;
    private volatile PaperPlatform platform;
    private volatile ScriptEngine engine;
    private volatile List<String> addons = List.of();
    private volatile String startFailure;
    private volatile LoadReport lastReport;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = TachyonSettings.from(getConfig(), getLogger());
        Path scriptsFolder = getDataFolder().toPath().resolve("scripts");
        if (Files.notExists(scriptsFolder)) {
            saveResource(SCRIPTS_PREFIX + "example.tys", false);
        }
        scripts = new ScriptDirectory(scriptsFolder);

        getServer().getServicesManager().register(AddonRegistrar.class, pendingAddons, this, ServicePriority.Normal);
        TysCommand command = new TysCommand(this);
        PluginCommand tys = Objects.requireNonNull(getCommand("tys"), "plugin.yml does not declare /tys");
        tys.setExecutor(command);
        tys.setTabCompleter(command);

        // Runs on the first tick after startup (the main thread on Paper, the global region on Folia).
        getServer().getGlobalRegionScheduler().run(this, task -> start());
    }

    /** Builds the registry (standard library and addons), the platform and the engine, and loads the scripts. */
    private void start() {
        try {
            PlatformCapabilities capabilities = PlatformCapabilities.detect();
            TachyonSettings active = settings;
            AddonAssembly.Result assembly = AddonAssembly.assemble(StandardLibrary::register,
                    PaperPlatform.builtInBindings(this, capabilities, active::debug), pendingAddons.close(),
                    PaperPlatform::isEventClass);
            for (AddonAssembly.Rejected rejected : assembly.rejected()) {
                getLogger().severe("Addon '" + rejected.addon() + "' was not loaded: " + rejected.reason());
            }
            PaperPlatform created = new PaperPlatform(this, capabilities, assembly.registry(), assembly.bindings(),
                    assembly.eventClasses());
            ScriptEngine started = new ScriptEngine(assembly.registry(), created, settings.engineOptions(),
                    new CrashReports(getDataFolder().toPath().resolve("logs"), getLogger(), Bukkit.getVersion()));
            created.attach(started);
            platform = created;
            engine = started;
            addons = assembly.loaded();

            getLogger().info("TachyonScript " + TachyonVersion.RUNTIME + " (language level " + TachyonVersion.LANGUAGE_LEVEL
                    + ", interpreter backend) on " + capabilities.serverName() + " " + capabilities.minecraftVersion()
                    + (capabilities.folia() ? " with regionized multithreading" : "")
                    + (addons.isEmpty() ? "" : "; addons: " + String.join(", ", addons)));
            LoadReport report = started.load(scripts);
            lastReport = report;
            logReport(report);
        } catch (RuntimeException | LinkageError e) {
            startFailure = e.toString();
            getLogger().log(Level.SEVERE, "TachyonScript failed to start; no scripts are active.", e);
        }
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.shutdown();
        }
        if (platform != null) {
            platform.shutdown();
        }
    }

    /** The engine, or {@code null} before the first load (or if starting failed). */
    ScriptEngine engine() {
        return engine;
    }

    /** Why starting failed, or {@code null}. */
    String startFailure() {
        return startFailure;
    }

    /** Names of the loaded addons. */
    List<String> addons() {
        return addons;
    }

    PaperPlatform platform() {
        return platform;
    }

    TachyonSettings settings() {
        return settings;
    }

    ScriptDirectory scripts() {
        return scripts;
    }

    /** Result of the most recent load, or {@code null} before the first one. */
    LoadReport lastReport() {
        return lastReport;
    }

    /**
     * Reloads the scripts on an async thread and hands the report to {@code done} (on that
     * thread). Returns {@code false} without doing anything if a reload is already running.
     */
    boolean reloadAsync(Set<String> forceRecompile, Consumer<LoadReport> done) {
        ScriptEngine current = engine;
        if (current == null || !reloading.compareAndSet(false, true)) {
            return false;
        }
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                LoadReport report = current.load(scripts, forceRecompile);
                lastReport = report;
                logReport(report);
                done.accept(report);
            } catch (RuntimeException | LinkageError e) {
                getLogger().log(Level.SEVERE, "Reload failed unexpectedly; the previous scripts stay active.", e);
            } finally {
                reloading.set(false);
            }
        });
        return true;
    }

    /** Logs a load report: diagnostics in full, then one summary line. */
    void logReport(LoadReport report) {
        Logger log = getLogger();
        if (report.failure() != null) {
            log.severe(report.failure() + " The previous scripts stay active.");
            return;
        }
        DiagnosticRenderer renderer = new DiagnosticRenderer(false, SCRIPTS_PREFIX);
        for (Diagnostic diagnostic : report.diagnostics()) {
            String text = "\n" + renderer.render(diagnostic);
            if (diagnostic.severity() == Severity.ERROR) {
                log.severe(text);
            } else if (diagnostic.severity() == Severity.WARNING) {
                log.warning(text);
            } else if (settings.debug()) {
                log.info(text);
            }
        }
        for (Map.Entry<String, List<String>> entry : report.linkProblems().entrySet()) {
            for (String problem : entry.getValue()) {
                log.severe(SCRIPTS_PREFIX + entry.getKey() + ": " + problem);
            }
        }
        if (!report.activated()) {
            log.severe("Load cancelled (reload.mode: strict): " + report.errorCount() + " errors in "
                    + report.failed().size() + " scripts. The previous scripts stay active.");
            return;
        }
        if (report.failed().isEmpty()) {
            log.info("Loaded " + report.summary());
        } else {
            log.warning("Loaded " + report.summary() + " (" + report.errorCount() + " errors)");
        }
        if (!report.keptPrevious().isEmpty()) {
            log.warning("Kept the previous working version of: " + String.join(", ", report.keptPrevious()));
        }
    }
}
