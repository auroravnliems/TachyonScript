package dev.tachyonscript.plugin;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.api.addon.AddonRegistrar;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.ScriptControls;
import dev.tachyonscript.engine.addon.AddonAssembly;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.diagnostic.Severity;
import dev.tachyonscript.platform.paper.PaperPlatform;
import dev.tachyonscript.platform.paper.PlatformCapabilities;
import dev.tachyonscript.stdlib.StandardLibrary;
import dev.tachyonscript.security.SecurityAuditStore;
import dev.tachyonscript.security.SecurityIncident;
import dev.tachyonscript.security.SecurityMessages;
import dev.tachyonscript.security.SecurityService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
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
    /** The PlaceholderAPI expansion (an Object so this class loads without PlaceholderAPI). */
    private Object expansion;

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
            // The constant tables (Material.X, Sound.X, ...) come from the server's registries, so
            // constants added by data packs and newer versions work too.
            AddonAssembly.Result assembly = AddonAssembly.assemble(builder -> {
                StandardLibrary.register(builder);
                PaperPlatform.useServerKeys(builder);
            }, PaperPlatform.builtInBindings(this, capabilities, active::debug), pendingAddons.close(),
                    PaperPlatform::isEventClass);
            for (AddonAssembly.Rejected rejected : assembly.rejected()) {
                getLogger().severe("Addon '" + rejected.addon() + "' was not loaded: " + rejected.reason());
            }
            PaperPlatform created = new PaperPlatform(this, capabilities, assembly.registry(), assembly.bindings(),
                    assembly.eventClasses());
            ScriptEngine started = new ScriptEngine(assembly.registry(), created,
                    settings.engineOptions(getDataFolder().toPath()),
                    new CrashReports(getDataFolder().toPath().resolve("logs"), getLogger(), Bukkit.getVersion()),
                    new SecurityService(SecuritySettings.from(getConfig()),
                            new SecurityAuditStore(getDataFolder().toPath().resolve("security")),
                            this::notifySecurity, message -> getLogger().severe(message)));
            started.controls(new ScriptControls(getDataFolder().toPath().resolve("disabled-scripts.properties")));
            created.attach(started);
            platform = created;
            engine = started;
            addons = assembly.loaded();

            getLogger().info("TachyonScript " + TachyonVersion.RUNTIME + " (language level " + TachyonVersion.LANGUAGE_LEVEL
                    + ", interpreter backend) on " + capabilities.serverName() + " " + capabilities.minecraftVersion()
                    + (capabilities.folia() ? " with regionized multithreading" : "")
                    + (addons.isEmpty() ? "" : "; addons: " + String.join(", ", addons)));
            registerPlaceholders();
            // Required security approval completes on a worker before the engine links/activates any script.
            reloadAsync(Set.of(), report -> { });
        } catch (IOException | RuntimeException | LinkageError e) {
            startFailure = e.toString();
            getLogger().log(Level.SEVERE, "TachyonScript failed to start; no scripts are active.", e);
        }
    }

    private void notifySecurity(SecurityIncident incident) {
        String detail = SecurityMessages.detail(incident, true);
        if (incident.decision().deniesExecution() || incident.severity().ordinal() >= dev.tachyonscript.security.SecuritySeverity.HIGH.ordinal())
            getLogger().severe(detail);
        else getLogger().warning(detail);
        if (!isEnabled()) return;
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            Component inspect = Component.text("/tys security inspect " + incident.id(), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand("/tys security inspect " + incident.id()))
                    .hoverEvent(Component.text("Inspect the complete source location and data flow"));
            for (var player : getServer().getOnlinePlayers()) {
                if (!player.hasPermission("tachyonscript.security.alerts")) continue;
                for (String line : SecurityMessages.admin(incident)) player.sendMessage(Component.text(line, NamedTextColor.RED));
                player.sendMessage(inspect);
            }
        });
    }

    /** Registers %tys_...% with PlaceholderAPI when it is installed. */
    private void registerPlaceholders() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            TachyonExpansion created = new TachyonExpansion(this);
            if (created.register()) {
                expansion = created;
                getLogger().info("Registered the PlaceholderAPI expansion: %tys_<placeholder>%");
            }
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.WARNING, "Cannot register the PlaceholderAPI expansion.", e);
        }
    }

    @Override
    public void onDisable() {
        if (expansion != null) {
            try {
                ((TachyonExpansion) expansion).unregister();
            } catch (RuntimeException | LinkageError ignored) {
                // PlaceholderAPI is shutting down too.
            }
        }
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
                LoadReport report = forceRecompile.isEmpty() ? current.load(scripts) : current.reload(scripts, forceRecompile);
                lastReport = report;
                logReport(report);
                done.accept(report);
            } catch (RuntimeException | LinkageError e) {
                getLogger().log(Level.SEVERE, "Reload failed unexpectedly; security-revoked scripts remain disabled.", e);
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
            log.severe(report.failure() + " Security-revoked scripts remain disabled.");
            return;
        }
        DiagnosticRenderer renderer = new DiagnosticRenderer(false, SCRIPTS_PREFIX);
        for (Diagnostic diagnostic : report.diagnostics()) {
            String text = renderer.renderCompact(diagnostic);
            if (engine != null) text = engine.security().options().redactor().withSource(diagnostic.file()).redact(text);
            if (diagnostic.severity() == Severity.ERROR) {
                text.lines().forEach(log::severe);
            } else if (diagnostic.severity() == Severity.WARNING) {
                text.lines().forEach(log::warning);
            } else if (settings.debug()) {
                text.lines().forEach(log::info);
            }
        }
        for (Map.Entry<String, List<String>> entry : report.linkProblems().entrySet()) {
            for (String problem : entry.getValue()) {
                log.severe(SCRIPTS_PREFIX + entry.getKey() + ": " + problem);
            }
        }
        if (!report.activated()) {
            log.severe("Load cancelled: " + report.errorCount() + " errors in "
                    + report.failed().size() + " scripts. Approved previous versions remain; security-revoked scripts stay disabled.");
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
