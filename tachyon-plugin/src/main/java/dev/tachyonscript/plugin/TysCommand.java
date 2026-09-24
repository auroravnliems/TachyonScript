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
import dev.tachyonscript.language.util.Suggestions;
import dev.tachyonscript.platform.paper.PlatformCapabilities;
import dev.tachyonscript.runtime.code.Disassembler;
import dev.tachyonscript.runtime.event.CompiledHandler;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;

import java.nio.file.Files;
import java.nio.file.Path;
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
        RELOAD("tachyonscript.reload", "[script]", "Recompile changed scripts and activate them"),
        SCRIPTS("tachyonscript.admin", "", "List the scripts"),
        INFO("tachyonscript.admin", "<script>", "Show details about a script"),
        ERRORS("tachyonscript.admin", "", "Show compile errors and runtime errors"),
        PROFILE("tachyonscript.profile", "start|stop|report", "Measure script execution time"),
        DUMP("tachyonscript.debug", "<script> [ir|code]", "Print the compiled form of a script to the console"),
        VERSION("tachyonscript.admin", "", "Show version information");

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
            case SCRIPTS -> scripts(sender);
            case INFO -> info(sender, rest);
            case ERRORS -> errors(sender);
            case PROFILE -> profile(sender, rest);
            case DUMP -> dump(sender, rest);
            case VERSION -> version(sender);
        }
        return true;
    }

    private static Sub find(String name) {
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
        if (args.length > 0) {
            String path = scriptPath(args[0]);
            if (path == null) {
                error(sender, "No script named '" + args[0] + "' in the scripts directory.");
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
            error(sender, "Reload cancelled (strict mode): " + report.errorCount()
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

    private void scripts(CommandSender sender) {
        ScriptEngine engine = plugin.engine();
        Generation generation = engine.generation();
        LoadReport report = plugin.lastReport();
        Set<String> failed = report == null ? Set.of() : new TreeSet<>(report.failed());
        send(sender, Component.text("Scripts (generation " + generation.id() + ", activated "
                + TIME.format(generation.created()) + "):", ACCENT));
        if (generation.scripts().isEmpty() && failed.isEmpty()) {
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
            long handlers = script.linked().handlers().size();
            Component line = Component.text("  " + path, failed.contains(path) ? NamedTextColor.YELLOW : NamedTextColor.WHITE)
                    .append(Component.text(" - " + handlers + (handlers == 1 ? " handler" : " handlers")
                            + (failed.contains(path) ? ", has errors (previous version active)" : ""), NamedTextColor.GRAY));
            send(sender, line);
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length == 0) {
            error(sender, "Usage: /tys info <script>");
            return;
        }
        String path = knownScript(args[0]);
        LoadedScript script = path == null ? null : plugin.engine().generation().scripts().get(path);
        LoadReport report = plugin.lastReport();
        boolean failed = report != null && path != null && report.failed().contains(path);
        if (script == null) {
            if (failed) {
                error(sender, path + " failed to compile and is not active. Use /tys errors.");
            } else {
                error(sender, "No active script named '" + args[0] + "'.");
            }
            return;
        }
        send(sender, Component.text(path, ACCENT));
        field(sender, "Status", failed ? "has errors; the previous working version is active" : "active");
        Map<String, Integer> events = new TreeMap<>();
        for (CompiledHandler handler : script.linked().handlers()) {
            events.merge(handler.event().name(), 1, Integer::sum);
        }
        List<String> eventList = new ArrayList<>();
        events.forEach((event, count) -> eventList.add(count == 1 ? event : event + " (" + count + ")"));
        field(sender, "Events", eventList.isEmpty() ? "none" : String.join(", ", eventList));
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

    private void version(CommandSender sender) {
        PlatformCapabilities capabilities = plugin.platform().capabilities();
        send(sender, Component.text("TachyonScript " + TachyonVersion.RUNTIME, ACCENT));
        field(sender, "Language level", String.valueOf(TachyonVersion.LANGUAGE_LEVEL));
        field(sender, "IR format", String.valueOf(TachyonVersion.IR_FORMAT));
        field(sender, "Backend", "interpreter");
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
        if (args.length == 2) {
            return switch (sub) {
                case RELOAD, INFO, DUMP -> matching(knownScripts(), args[1]);
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
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
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
        for (Diagnostic diagnostic : report.diagnostics()) {
            if (diagnostic.severity() != Severity.ERROR && diagnostic.severity() != Severity.WARNING) {
                continue;
            }
            String text = console ? renderer.render(diagnostic)
                    : diagnostic.severity() + " " + TachyonPlugin.SCRIPTS_PREFIX + diagnostic.position()
                    + " [" + diagnostic.code().id() + "] " + diagnostic.message();
            lines.add(Component.text(text, diagnostic.isError() ? NamedTextColor.RED : NamedTextColor.YELLOW));
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
