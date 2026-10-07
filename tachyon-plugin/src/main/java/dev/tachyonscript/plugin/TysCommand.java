package dev.tachyonscript.plugin;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.engine.ErrorReporter;
import dev.tachyonscript.engine.Generation;
import dev.tachyonscript.engine.LoadReport;
import dev.tachyonscript.engine.LoadedScript;
import dev.tachyonscript.engine.ScriptEngine;
import dev.tachyonscript.engine.profile.ProfileReport;
import dev.tachyonscript.ir.IrPrinter;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.diagnostic.Severity;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.util.Suggestions;
import dev.tachyonscript.platform.paper.PlatformCapabilities;
import dev.tachyonscript.runtime.code.Disassembler;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code /tys}: reload, inspect, profile and debug scripts.
 *
 * <p>Every subcommand checks its own permission. Text that comes from scripts or diagnostics
 * is always sent as plain text components, never parsed as MiniMessage.
 */
final class TysCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_CHAT_LINES = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final TextColor ACCENT = NamedTextColor.AQUA;

    private enum Sub {
        HELP("tachyonscript.admin", "", "Show this help"),
        RELOAD("tachyonscript.reload", "[script|all]", "Reload one script and its importers, or all changed scripts"),
        DISABLE("tachyonscript.manage", "[script|all]", "Stop scripts immediately and keep them disabled after restart"),
        ENABLE("tachyonscript.manage", "<script|all>", "Enable scripts and compile them before activation"),
        PERFORMANCE("tachyonscript.profile", "<milliseconds|off>", "Configure optional slow tick-thread warnings"),
        SCRIPTS("tachyonscript.admin", "", "List the scripts"),
        INFO("tachyonscript.admin", "<script>", "Show details about a script"),
        ERRORS("tachyonscript.admin", "", "Show compile errors and runtime errors"),
        PROFILE("tachyonscript.profile", "start|stop|report", "Measure script execution time"),
        DUMP("tachyonscript.debug", "<script> [ir|code]", "Print the compiled form of a script to the console"),
        STATUS("tachyonscript.admin", "", "Show storage, databases, commands and placeholders"),
        VERSION("tachyonscript.admin", "", "Show version information"),
        SECURITY("tachyonscript.security.admin", "<action> [script|incident]", "Inspect, scan and quarantine scripts");

        final String permission;
        final String usage;
        final String description;

        Sub(String permission, String usage, String description) {
            this.permission = permission;
            this.usage = usage;
            this.description = description;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final TachyonPlugin plugin;

    TysCommand(TachyonPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        Sub sub = find(args[0]);
        if (sub == null) {
            List<String> names = Arrays.stream(Sub.values()).map(Sub::label).toList();
            List<String> close = Suggestions.closest(args[0].toLowerCase(Locale.ROOT), names, 1);
            error(sender, "Unknown subcommand '" + args[0] + "'."
                    + (close.isEmpty() ? "" : " Did you mean '" + close.getFirst() + "'?") + " Use /tys help.");
            return true;
        }
        if (!sender.hasPermission(sub.permission)) {
            error(sender, "You do not have permission to use /tys " + sub.label() + ".");
            return true;
        }
        if (plugin.engine() == null && sub != Sub.HELP) {
            String failure = plugin.startFailure();
            error(sender, failure == null
                    ? "TachyonScript is starting; scripts are loaded when the server has finished starting."
                    : "TachyonScript failed to start (" + failure + "). See the server log.");
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case HELP -> help(sender);
            case RELOAD -> reload(sender, rest);
            case DISABLE -> disable(sender, rest);
            case ENABLE -> enable(sender, rest);
            case PERFORMANCE -> performance(sender, rest);
            case SCRIPTS -> scripts(sender);
            case INFO -> info(sender, rest);
            case ERRORS -> errors(sender);
            case PROFILE -> profile(sender, rest);
            case DUMP -> dump(sender, rest);
            case STATUS -> status(sender);
            case VERSION -> version(sender);
            case SECURITY -> new SecurityCommands(plugin).execute(sender, rest);
        }
        return true;
    }

    private static Sub find(String name) {
        if (name.equalsIgnoreCase("diable")) return Sub.DISABLE;
        for (Sub sub : Sub.values()) {
            if (sub.label().equalsIgnoreCase(name)) {
                return sub;
            }
        }
        return null;
    }

    // ================================================================= subcommands

    private void help(CommandSender sender) {
        send(sender, Component.text("TachyonScript " + TachyonVersion.RUNTIME, ACCENT));
        boolean any = false;
        for (Sub sub : Sub.values()) {
            if (sender.hasPermission(sub.permission)) {
                any = true;
                String usage = "/tys " + sub.label() + (sub.usage.isEmpty() ? "" : " " + sub.usage);
                send(sender, Component.text("  " + usage, NamedTextColor.WHITE)
                        .append(Component.text(" - " + sub.description, NamedTextColor.GRAY)));
            }
        }
        if (!any) {
            error(sender, "You do not have permission to use /tys.");
        }
    }

    private void reload(CommandSender sender, String[] args) {
        Set<String> force = Set.of();
        if (args.length > 0 && !(args.length == 1 && args[0].equalsIgnoreCase("all"))) {
            String requested = String.join(" ", args);
            String path = knownScript(requested);
            if (path == null) path = scriptPath(requested);
            if (path == null) {
                error(sender, "No script named '" + requested + "' in the scripts directory.");
                return;
            }
            if (!plugin.engine().controls().allowed(path)) {
                error(sender, "Script is disabled. Use /tys enable " + path + " (or /tys enable all after an emergency stop).");
                return;
            }
            force = Set.of(path);
        }
        boolean console = sender instanceof ConsoleCommandSender;
        boolean started = plugin.reloadAsync(force, report -> {
            if (!console) {
                sendReport(sender, report);
            }
        });
        if (!started) {
            error(sender, "A reload is already running.");
        } else if (!console) {
            info(sender, "Reloading scripts...");
        }
    }

    private void sendReport(CommandSender sender, LoadReport report) {
        if (report.failure() != null) {
            error(sender, report.failure() + " The previous scripts stay active.");
            return;
        }
        if (!report.activated()) {
            error(sender, "Reload cancelled: " + report.errorCount()
                    + " errors. The previous scripts stay active.");
        } else if (report.failed().isEmpty()) {
            send(sender, Component.text("Reloaded " + report.summary(), NamedTextColor.GREEN));
        } else {
            send(sender, Component.text("Reloaded " + report.summary(), NamedTextColor.YELLOW));
        }
        if (!report.keptPrevious().isEmpty()) {
            send(sender, Component.text("Kept the previous working version of: "
                    + String.join(", ", report.keptPrevious()), NamedTextColor.YELLOW));
        }
        sendDiagnostics(sender, report);
    }

    private void disable(CommandSender sender, String[] args) {
        boolean all = args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("all"));
        String input = String.join(" ", args);
        String path = all ? null : knownScript(input);
        if (!all && path == null) path = scriptPath(input);
        if (!all && path == null) { error(sender, "Unknown script: " + input); return; }
        try {
            Set<String> affected = plugin.engine().disable(all ? Set.of() : Set.of(path), all);
            send(sender, Component.text(all ? "All scripts disabled, including future loads. Use /tys enable all to resume."
                    : "Disabled: " + String.join(", ", new TreeSet<>(affected)) + ". Saved across reloads and restarts.", NamedTextColor.YELLOW));
        } catch (IOException | RuntimeException error) {
            error(sender, "Disable failed to finish: " + error.getMessage()
                    + ". The execution gate remains closed; check the console and persistence before restarting.");
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cannot finish disabling scripts", error);
        }
    }

    private void enable(CommandSender sender, String[] args) {
        if (args.length == 0) { error(sender, "Use /tys enable <script|all>."); return; }
        boolean all = args.length == 1 && args[0].equalsIgnoreCase("all");
        String input = String.join(" ", args);
        String path = all ? null : knownScript(input);
        if (!all && path == null) path = scriptPath(input);
        if (!all && path == null) { error(sender, "Unknown script: " + input); return; }
        try {
            plugin.engine().controls().enable(all ? Set.of() : Set.of(path), all);
            if (!plugin.reloadAsync(all ? Set.of() : Set.of(path), report -> sendReport(sender, report)))
                info(sender, "Enable state saved; another reload is running. Run /tys reload again when it finishes.");
        } catch (IOException | RuntimeException error) {
            error(sender, "Cannot enable: " + error.getMessage());
        }
    }

    private void performance(CommandSender sender, String[] args) {
        if (args.length != 1) { error(sender, "Use /tys performance <milliseconds|off>."); return; }
        long millis;
        try {
            millis = args[0].equalsIgnoreCase("off") ? 0 : Long.parseLong(args[0]);
            if (millis < 0 || millis > Long.MAX_VALUE / 1_000_000L) throw new NumberFormatException();
        } catch (NumberFormatException error) { error(sender, "Expected a nonnegative number of milliseconds, or off."); return; }
        plugin.engine().slowWarnings(millis * 1_000_000L);
        plugin.getConfig().set("performance.slow-execution-warnings", millis > 0);
        plugin.getConfig().set("performance.slow-execution-warning-ms", millis);
        plugin.saveConfig();
        info(sender, millis == 0 ? "Slow execution warnings disabled. /tys profile remains available."
                : "Slow tick-thread warnings enabled above " + millis + " ms (at most once per function every 30 seconds).");
    }

    private void scripts(CommandSender sender) {
        ScriptEngine engine = plugin.engine();
        Generation generation = engine.generation();
        LoadReport report = plugin.lastReport();
        Set<String> failed = report == null ? Set.of() : new TreeSet<>(report.failed());
        send(sender, Component.text("Scripts (generation " + generation.id() + ", activated "
                + TIME.format(generation.created()) + "):", ACCENT));
        if (engine.controls().allDisabled()) info(sender, "Emergency stop active: all scripts disabled, including new files.");
        for (String path : new TreeSet<>(engine.controls().disabled()))
            send(sender, Component.text("  " + path + " - disabled by operator", NamedTextColor.YELLOW));
        if (generation.scripts().isEmpty() && failed.isEmpty() && engine.controls().disabled().isEmpty()) {
            info(sender, "  No scripts. Put .tys files into plugins/TachyonScript/scripts/ and run /tys reload.");
            return;
        }
        Set<String> all = new TreeSet<>(generation.scripts().keySet());
        all.addAll(failed);
        for (String path : all) {
            LoadedScript script = generation.scripts().get(path);
            if (script == null) {
                send(sender, Component.text("  " + path, NamedTextColor.RED)
                        .append(Component.text(" - failed to compile, not active", NamedTextColor.GRAY)));
                continue;
            }
            Component line = Component.text("  " + path, failed.contains(path) ? NamedTextColor.YELLOW : NamedTextColor.WHITE)
                    .append(Component.text(" - " + ScriptSummary.contents(script.compiled().bound())
                            + (failed.contains(path) ? ", has errors (previous version active)" : ""), NamedTextColor.GRAY));
            send(sender, line);
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length == 0) {
            error(sender, "Usage: /tys info <script>");
            return;
        }
        String input = String.join(" ", args);
        String path = knownScript(input);
        if (path != null && !plugin.engine().controls().allowed(path)) {
            send(sender, Component.text(path, ACCENT));
            field(sender, "Status", "disabled by operator" + (plugin.engine().controls().allDisabled() ? " (all scripts stopped)" : ""));
            return;
        }
        LoadedScript script = path == null ? null : plugin.engine().generation().scripts().get(path);
        LoadReport report = plugin.lastReport();
        boolean failed = report != null && path != null && report.failed().contains(path);
        if (script == null) {
            if (failed) {
                error(sender, path + " failed to compile and is not active. Use /tys errors.");
            } else {
                error(sender, "No active script named '" + input + "'.");
            }
            return;
        }
        send(sender, Component.text(path, ACCENT));
        field(sender, "Status", failed ? "has errors; the previous working version is active" : "active");
        BoundModule bound = script.compiled().bound();
        field(sender, "Module", bound.name() + (bound.imports().isEmpty() ? "" : " (imports " + String.join(", ", bound.imports()) + ")"));
        Map<String, Integer> events = new TreeMap<>();
        for (CompiledHandler handler : script.linked().handlers()) {
            events.merge(handler.event().name(), 1, Integer::sum);
        }
        List<String> eventList = new ArrayList<>();
        events.forEach((event, count) -> eventList.add(count == 1 ? event : event + " (" + count + ")"));
        field(sender, "Events", eventList.isEmpty() ? "none" : String.join(", ", eventList));
        List<String> commands = ScriptSummary.commands(bound);
        if (!commands.isEmpty()) {
            field(sender, "Commands", String.join(", ", commands));
        }
        List<String> tasks = ScriptSummary.tasks(bound);
        if (!tasks.isEmpty()) {
            field(sender, "Tasks", String.join(", ", tasks));
        }
        List<String> placeholders = ScriptSummary.placeholders(bound);
        if (!placeholders.isEmpty()) {
            field(sender, "Placeholders", String.join(", ", placeholders));
        }
        String saved = ScriptSummary.savedVariables(bound);
        if (!saved.isEmpty()) {
            field(sender, "Saved variables", saved);
        }
        int codeWords = 0;
        for (CompiledFunction function : script.linked().functions().values()) {
            codeWords += function.unit().code().length;
        }
        field(sender, "Functions", script.linked().functions().size() + " (" + codeWords + " code words)");
        field(sender, "Source", script.compiled().file().length() + " characters, sha256 "
                + script.hash().substring(0, Math.min(12, script.hash().length())));
    }

    private void errors(CommandSender sender) {
        LoadReport report = plugin.lastReport();
        if (report == null || (report.diagnostics().isEmpty() && report.linkProblems().isEmpty())) {
            send(sender, Component.text("No compile errors or warnings in the last load.", NamedTextColor.GREEN));
        } else {
            send(sender, Component.text("Last load: " + report.errorCount() + " errors, " + report.warningCount()
                    + " warnings", ACCENT));
            sendDiagnostics(sender, report);
        }
        List<ErrorReporter.Site> sites = plugin.engine().errors().sites();
        if (sites.isEmpty()) {
            send(sender, Component.text("No runtime errors since the last reload.", NamedTextColor.GREEN));
            return;
        }
        send(sender, Component.text("Runtime errors since the last reload:", ACCENT));
        int shown = 0;
        for (ErrorReporter.Site site : sites) {
            if (shown++ == MAX_CHAT_LINES) {
                info(sender, "  ...and " + (sites.size() - MAX_CHAT_LINES) + " more locations.");
                break;
            }
            send(sender, Component.text("  " + site.count() + "x ", NamedTextColor.RED)
                    .append(Component.text(site.location() + " ", NamedTextColor.WHITE))
                    .append(Component.text(site.message(), NamedTextColor.GRAY)));
        }
    }

    private void profile(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        ScriptEngine engine = plugin.engine();
        switch (action) {
            case "start" -> {
                engine.startProfiling();
                send(sender, Component.text("Profiling started. Use /tys profile report or /tys profile stop.",
                        NamedTextColor.GREEN));
            }
            case "stop" -> {
                engine.stopProfiling();
                send(sender, Component.text("Profiling stopped.", NamedTextColor.GREEN));
                profileReport(sender, engine.profiler().report());
            }
            case "report" -> profileReport(sender, engine.profiler().report());
            default -> error(sender, "Usage: /tys profile start|stop|report");
        }
    }

    private void profileReport(CommandSender sender, ProfileReport report) {
        List<ProfileReport.Row> rows = report.byHandler();
        if (rows.isEmpty()) {
            info(sender, "No executions recorded" + (plugin.engine().profiler().isEnabled() ? " yet." : ". Use /tys profile start."));
            return;
        }
        send(sender, Component.text("Profile (" + ProfileReport.duration(report.durationNanos()) + "), slowest first:", ACCENT));
        int shown = 0;
        for (ProfileReport.Row row : rows) {
            if (shown++ == MAX_CHAT_LINES * 2) {
                info(sender, "  ...and " + (rows.size() - MAX_CHAT_LINES * 2) + " more handlers.");
                break;
            }
            send(sender, Component.text("  " + row.name(), NamedTextColor.WHITE)
                    .append(Component.text(String.format(Locale.ROOT, " - %,d calls, %s total, %s avg, %s max",
                            row.calls(), ProfileReport.duration(row.totalNanos()),
                            ProfileReport.duration(Math.round(row.averageNanos())),
                            ProfileReport.duration(row.maxNanos())), NamedTextColor.GRAY)));
        }
    }

    private void dump(CommandSender sender, String[] args) {
        if (args.length == 0) {
            error(sender, "Usage: /tys dump <script> [ir|code]");
            return;
        }
        String path = knownScript(args[0]);
        LoadedScript script = path == null ? null : plugin.engine().generation().scripts().get(path);
        if (script == null) {
            error(sender, "No active script named '" + args[0] + "'.");
            return;
        }
        String kind = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "ir";
        String text = switch (kind) {
            case "ir" -> IrPrinter.print(script.compiled().ir());
            case "code" -> Disassembler.disassemble(script.linked().functions().values().stream()
                    .map(CompiledFunction::unit).toList());
            default -> null;
        };
        if (text == null) {
            error(sender, "Unknown dump kind '" + kind + "'. Use 'ir' or 'code'.");
            return;
        }
        plugin.getLogger().info("Dump of " + TachyonPlugin.SCRIPTS_PREFIX + path + " (" + kind + "):\n" + text);
        if (!(sender instanceof ConsoleCommandSender)) {
            info(sender, "The " + kind + " of " + path + " was printed to the server console.");
        }
    }

    private void status(CommandSender sender) {
        ScriptEngine engine = plugin.engine();
        send(sender, Component.text("TachyonScript status", ACCENT));
        field(sender, "Saved variables", engine.data().backend().describe() + ", written every "
                + (engine.data().flushIntervalMillis() / 1000) + " s");
        List<String> databases = engine.databases().configuredNames();
        field(sender, "Databases", databases.isEmpty() ? "none configured (scripts can use Database.sqlite)"
                : String.join(", ", databases));
        List<String> commands = engine.commandNames();
        field(sender, "Script commands", commands.isEmpty() ? "none" : "/" + String.join(", /", commands));
        Set<String> placeholders = new TreeSet<>(engine.placeholderNames());
        field(sender, "Placeholders", placeholders.isEmpty() ? "none" : "%tys_" + String.join("%, %tys_", placeholders) + "%");
        field(sender, "PlaceholderAPI", plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")
                ? "connected" : "not installed");
        field(sender, "Vault", plugin.getServer().getPluginManager().isPluginEnabled("Vault") ? "installed" : "not installed");
    }

    private void version(CommandSender sender) {
        PlatformCapabilities capabilities = plugin.platform().capabilities();
        send(sender, Component.text("TachyonScript " + TachyonVersion.RUNTIME, ACCENT));
        field(sender, "Language level", String.valueOf(TachyonVersion.LANGUAGE_LEVEL));
        field(sender, "IR format", String.valueOf(TachyonVersion.IR_FORMAT));
        field(sender, "Backend", plugin.settings().backend().id());
        field(sender, "Reload mode", plugin.settings().mode().name().toLowerCase(Locale.ROOT));
        field(sender, "Server", capabilities.serverName() + " " + capabilities.minecraftVersion()
                + (capabilities.folia() ? " (regionized multithreading)" : ""));
        field(sender, "Java", System.getProperty("java.version"));
        List<String> addons = plugin.addons();
        field(sender, "Addons", addons.isEmpty() ? "none" : String.join(", ", addons));
    }

    // ================================================================= tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Sub sub : Sub.values()) {
                if (sender.hasPermission(sub.permission)) {
                    names.add(sub.label());
                }
            }
            return matching(names, args[0]);
        }
        Sub sub = find(args[0]);
        if (sub == null || !sender.hasPermission(sub.permission) || plugin.engine() == null) {
            return List.of();
        }
        if (sub == Sub.SECURITY) return new SecurityCommands(plugin).complete(Arrays.copyOfRange(args, 1, args.length));
        if (args.length == 2) {
            return switch (sub) {
                case RELOAD, DISABLE, ENABLE -> {
                    List<String> names = new ArrayList<>(knownScripts());
                    names.add("all");
                    yield matching(names, args[1]);
                }
                case INFO, DUMP -> matching(knownScripts(), args[1]);
                case PERFORMANCE -> matching(List.of("off", "50", "100"), args[1]);
                case PROFILE -> matching(List.of("start", "stop", "report"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub == Sub.DUMP) {
            return matching(List.of("ir", "code"), args[2]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    // ================================================================= helpers

    /** Scripts of the active generation plus scripts that failed in the last load. */
    private List<String> knownScripts() {
        Set<String> paths = new TreeSet<>(plugin.engine().generation().scripts().keySet());
        paths.addAll(plugin.engine().controls().disabled());
        Path root = plugin.scripts().root();
        try (var walk = Files.walk(root)) {
            walk.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".tys"))
                    .filter(p -> { for (Path part : root.relativize(p)) if (part.toString().startsWith("-")) return false; return true; })
                    .forEach(p -> paths.add(root.relativize(p).toString().replace('\\', '/')));
        } catch (IOException ignored) { /* Active and disabled names remain available. */ }
        LoadReport report = plugin.lastReport();
        if (report != null) {
            paths.addAll(report.failed());
        }
        return List.copyOf(paths);
    }

    /** Resolves a user-typed script name against the known scripts ({@code join}, {@code join.tys}). */
    private String knownScript(String input) {
        String normalized = normalize(input);
        for (String path : knownScripts()) {
            if (path.equals(normalized)) {
                return path;
            }
        }
        return null;
    }

    /**
     * Resolves a user-typed script name to a {@code .tys} file inside the scripts directory,
     * or {@code null}. Paths that would leave the directory are rejected.
     */
    private String scriptPath(String input) {
        String normalized = normalize(input);
        Path root = plugin.scripts().root().toAbsolutePath().normalize();
        Path file;
        try { file = root.resolve(normalized).normalize(); }
        catch (java.nio.file.InvalidPathException invalid) { return null; }
        if (!file.startsWith(root) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String normalize(String input) {
        String path = input.replace('\\', '/');
        if (path.startsWith(TachyonPlugin.SCRIPTS_PREFIX)) {
            path = path.substring(TachyonPlugin.SCRIPTS_PREFIX.length());
        }
        return path.endsWith(".tys") ? path : path + ".tys";
    }

    private void sendDiagnostics(CommandSender sender, LoadReport report) {
        List<Component> lines = new ArrayList<>();
        boolean console = sender instanceof ConsoleCommandSender;
        DiagnosticRenderer renderer = new DiagnosticRenderer(false, TachyonPlugin.SCRIPTS_PREFIX);
        java.util.Map<String, dev.tachyonscript.security.SecretRedactor> redactors = new java.util.HashMap<>();
        for (Diagnostic diagnostic : report.diagnostics()) {
            if (diagnostic.severity() != Severity.ERROR && diagnostic.severity() != Severity.WARNING) {
                continue;
            }
            String text = console ? renderer.renderCompact(diagnostic)
                    : diagnostic.severity() + " " + TachyonPlugin.SCRIPTS_PREFIX + diagnostic.position()
                    + " [" + diagnostic.code().id() + "] " + diagnostic.message();
            var redactor = redactors.computeIfAbsent(diagnostic.file().path(),
                    ignored -> plugin.engine().security().options().redactor().withSource(diagnostic.file()));
            lines.add(Component.text(redactor.redact(text), diagnostic.isError() ? NamedTextColor.RED : NamedTextColor.YELLOW)
                    .hoverEvent(Component.text(redactor.redact(renderer.renderCompact(diagnostic))))
                    .clickEvent(ClickEvent.copyToClipboard(TachyonPlugin.SCRIPTS_PREFIX + diagnostic.position())));
        }
        report.linkProblems().forEach((path, problems) -> problems.forEach(problem -> lines.add(
                Component.text("ERROR " + TachyonPlugin.SCRIPTS_PREFIX + path + ": " + problem, NamedTextColor.RED))));
        int limit = console ? Integer.MAX_VALUE : MAX_CHAT_LINES;
        for (int i = 0; i < lines.size() && i < limit; i++) {
            send(sender, lines.get(i));
        }
        if (lines.size() > limit) {
            info(sender, "...and " + (lines.size() - limit) + " more (see the server console).");
        }
    }

    private static void field(CommandSender sender, String name, String value) {
        send(sender, Component.text("  " + name + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE)));
    }

    private static void info(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.GRAY));
    }

    private static void error(CommandSender sender, String message) {
        send(sender, Component.text(message, NamedTextColor.RED));
    }

    private static void send(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }
}
