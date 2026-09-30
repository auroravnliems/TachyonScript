package dev.tachyonscript.engine;

import dev.tachyonscript.api.storage.KeyedValues;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.PrimitiveType;
import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.engine.spi.ArgumentTypes;
import dev.tachyonscript.engine.spi.CommandRegistry;
import dev.tachyonscript.engine.spi.Platform;
import dev.tachyonscript.language.semantic.BoundCommand;
import dev.tachyonscript.runtime.error.ScriptRuntimeException;
import dev.tachyonscript.runtime.interpreter.CompiledFunction;
import dev.tachyonscript.runtime.interpreter.Interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the {@code command} declarations of the active scripts: builds a tree of root commands
 * and sub-commands, registers the roots with the server, and on each use checks permission,
 * player-only and cooldown, converts the arguments to their declared types and calls the
 * command's body.
 */
final class CommandManager {

    /** A command declared by a script. */
    private record Entry(LoadedScript script, BoundCommand command, CompiledFunction function) {
    }

    /** A word of the command tree: {@code warp} and its sub-commands {@code set}, {@code delete}... */
    private static final class Node {
        final String word;
        final String path;
        Entry entry;
        final Map<String, Node> children = new LinkedHashMap<>();

        Node(String word, String path) {
            this.word = word;
            this.path = path;
        }
    }

    private final ScriptEngine engine;
    private final Platform platform;
    private final MessageFormatter formatter;
    private volatile CommandMessages messages;
    /** Last use of each command by each sender (cooldowns), by command path, then sender id. */
    private final Map<String, Map<String, Long>> lastUse = new ConcurrentHashMap<>();
    private volatile Map<String, Node> roots = Map.of();

    CommandManager(ScriptEngine engine, Platform platform, CommandMessages messages) {
        this.engine = engine;
        this.platform = platform;
        this.formatter = new MessageFormatter(platform.text());
        this.messages = messages;
    }

    void messages(CommandMessages newMessages) {
        this.messages = newMessages;
    }

    /**
     * Rebuilds the command tree from the active scripts and registers it. Returns warnings about
     * commands declared twice (the first script in path order wins).
     */
    List<String> update(Collection<LoadedScript> scripts) {
        List<String> warnings = new ArrayList<>();
        Map<String, Node> tree = new LinkedHashMap<>();
        for (LoadedScript script : scripts) {
            if (script.compiled().bound() == null) {
                continue;
            }
            for (BoundCommand command : script.compiled().bound().commands()) {
                CompiledFunction function = script.linked().function(command.function().key()).orElse(null);
                if (function == null) {
                    continue;
                }
                Node node = null;
                StringBuilder path = new StringBuilder();
                for (String word : command.path()) {
                    path.append(path.isEmpty() ? "" : " ").append(word);
                    String current = path.toString();
                    Map<String, Node> level = node == null ? tree : node.children;
                    node = level.computeIfAbsent(word, w -> new Node(w, current));
                }
                if (node.entry != null) {
                    warnings.add("Command /" + node.path + " is declared by both " + node.entry.script().path() + " and "
                            + script.path() + "; the one in " + node.entry.script().path() + " is used.");
                    continue;
                }
                node.entry = new Entry(script, command, function);
            }
        }
        this.roots = tree;
        List<CommandRegistry.Command> commands = new ArrayList<>();
        for (Node root : tree.values()) {
            commands.add(new Root(root));
        }
        platform.commands().update(commands);
        return warnings;
    }

    /** Words of the active root commands (for tools). */
    List<String> rootNames() {
        return List.copyOf(roots.keySet());
    }

    // ================================================================= execution

    private final class Root implements CommandRegistry.Command {
        private final Node node;

        Root(Node node) {
            this.node = node;
        }

        private BoundCommand.Options options() {
            return node.entry != null ? node.entry.command().options() : null;
        }

        @Override
        public String name() {
            return node.word;
        }

        @Override
        public List<String> aliases() {
            BoundCommand.Options options = options();
            return options == null ? List.of() : options.aliases();
        }

        @Override
        public String description() {
            BoundCommand.Options options = options();
            return options == null ? "" : options.description();
        }

        @Override
        public String usage() {
            return node.entry != null ? usageOf(node.entry.command(), "/" + node.word) : "/" + node.word;
        }

        @Override
        public String permission() {
            BoundCommand.Options options = options();
            return options == null ? "" : options.permission();
        }

        @Override
        public List<CommandRegistry.HelpEntry> helpEntries() {
            List<CommandRegistry.HelpEntry> entries = new ArrayList<>();
            collectHelp(node, entries);
            return entries;
        }

        private static void collectHelp(Node node, List<CommandRegistry.HelpEntry> entries) {
            if (node.entry != null) {
                BoundCommand.Options options = node.entry.command().options();
                entries.add(new CommandRegistry.HelpEntry(usageOf(node.entry.command(), "/" + node.path),
                        options.description(), options.permission()));
            }
            for (Node child : node.children.values()) {
                collectHelp(child, entries);
            }
        }

        @Override
        public void execute(Object sender, String label, String[] arguments) {
            run(node, sender, label, arguments);
        }

        @Override
        public List<String> complete(Object sender, String label, String[] arguments) {
            return completions(node, sender, arguments);
        }
    }

    private void run(Node root, Object sender, String label, String[] arguments) {
        Node node = root;
        int consumed = 0;
        while (consumed < arguments.length) {
            Node child = node.children.get(arguments[consumed].toLowerCase(Locale.ROOT));
            if (child == null) {
                break;
            }
            node = child;
            consumed++;
        }
        String shownLabel = "/" + label + (node == root ? "" : node.path.substring(root.word.length()));
        if (node.entry == null) {
            send(sender, messages.subcommands(), Map.of("label", shownLabel.substring(1), "commands", subcommandList(node)));
            return;
        }
        Entry entry = node.entry;
        BoundCommand.Options options = entry.command().options();
        CommandRegistry registry = platform.commands();
        if (!options.permission().isEmpty() && !registry.hasPermission(sender, options.permission())) {
            send(sender, options.permissionMessage().isEmpty() ? messages.noPermission() : options.permissionMessage(), Map.of());
            return;
        }
        Object player = registry.asPlayer(sender);
        if (options.playerOnly() && player == null) {
            send(sender, messages.playerOnly(), Map.of());
            return;
        }
        String usage = usageOf(entry.command(), shownLabel);
        Object[] values = parse(entry.command(), Arrays.copyOfRange(arguments, consumed, arguments.length), sender, usage);
        if (values == null) {
            return;
        }
        if (options.cooldownMillis() > 0 && !(options.cooldownBypass().isEmpty() ? false
                : registry.hasPermission(sender, options.cooldownBypass()))) {
            Map<String, Long> uses = lastUse.computeIfAbsent(node.path, key -> new ConcurrentHashMap<>());
            String id = registry.id(sender);
            long now = System.currentTimeMillis();
            Long last = uses.get(id);
            if (last != null && now - last < options.cooldownMillis()) {
                long remaining = options.cooldownMillis() - (now - last);
                // Round up to whole seconds so "0s" is never shown.
                String text = Values.durationToString(remaining < 1000 ? remaining : (remaining + 999) / 1000 * 1000);
                send(sender, options.cooldownMessage().isEmpty() ? messages.cooldown() : options.cooldownMessage(),
                        Map.of("remaining", text));
                return;
            }
            uses.put(id, now);
        }
        Object[] call = new Object[values.length + 2];
        call[0] = sender;
        call[1] = player;
        System.arraycopy(values, 0, call, 2, values.length);
        if (!entry.script().isActive()) {
            return;
        }
        try {
            Interpreter.call(entry.function(), call);
        } catch (ScriptRuntimeException error) {
            engine.errors().report(entry.script().path(), error);
            send(sender, messages.error(), Map.of());
        }
    }

    /** Converts the arguments; sends the problem to the sender and returns null when they are invalid. */
    private Object[] parse(BoundCommand command, String[] arguments, Object sender, String usage) {
        List<BoundCommand.Parameter> parameters = command.parameters();
        Object[] values = new Object[parameters.size()];
        int index = 0;
        for (int i = 0; i < parameters.size(); i++) {
            BoundCommand.Parameter parameter = parameters.get(i);
            if (index >= arguments.length) {
                if (!parameter.optional()) {
                    send(sender, messages.missingArgument(), Map.of("name", parameter.name(), "usage", usage));
                    return null;
                }
                values[i] = defaultValue(parameter);
                continue;
            }
            String text = parameter.rest() ? String.join(" ", Arrays.copyOfRange(arguments, index, arguments.length))
                    : arguments[index];
            index = parameter.rest() ? arguments.length : index + 1;
            try {
                values[i] = convert(parameter.type().nonNullable(), text, sender);
            } catch (ArgumentTypes.InvalidArgument invalid) {
                send(sender, messages.invalidArgument(), Map.of("message", invalid.getMessage()));
                return null;
            } catch (NumberFormatException notANumber) {
                send(sender, messages.notANumber(), Map.of("input", text));
                return null;
            }
        }
        if (index < arguments.length) {
            send(sender, messages.tooManyArguments(), Map.of("usage", usage));
            return null;
        }
        return values;
    }

    private Object defaultValue(BoundCommand.Parameter parameter) {
        if (parameter.defaultKey() != null && parameter.type().nonNullable() instanceof ClassType type) {
            KeyedValues values = platform.bindings().keyedValues(type).orElse(null);
            return values == null ? null : values.resolve(parameter.defaultKey());
        }
        return parameter.defaultValue();
    }

    private Object convert(Type type, String text, Object sender) throws ArgumentTypes.InvalidArgument {
        if (type instanceof PrimitiveType primitive) {
            return switch (primitive) {
                case INT -> Integer.parseInt(text);
                case LONG -> Long.parseLong(text);
                case DOUBLE -> Double.parseDouble(finite(text));
                case FLOAT -> Float.parseFloat(finite(text));
                case BOOL -> switch (text.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "on", "1" -> true;
                    case "false", "no", "off", "0" -> false;
                    default -> throw new ArgumentTypes.InvalidArgument("'" + text + "' is not true or false.");
                };
                case DURATION -> parseDuration(text);
                default -> throw new ArgumentTypes.InvalidArgument("Unsupported argument type " + type.displayName());
            };
        }
        if (type == Types.STRING) {
            return text;
        }
        return platform.arguments().parse((ClassType) type, text, sender);
    }

    private static String finite(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("nan") || lower.contains("infinity") || lower.endsWith("d") || lower.endsWith("f")) {
            throw new NumberFormatException(text);
        }
        return text;
    }

    /** {@code 90}, {@code 90s}, {@code 5m}, {@code 1h30m}, {@code 2d}, {@code 500ms}; a plain number means seconds. */
    static long parseDuration(String text) throws ArgumentTypes.InvalidArgument {
        String input = text.toLowerCase(Locale.ROOT).strip();
        if (input.matches("\\d+")) {
            return Long.parseLong(input) * 1000;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)(ms|d|h|m|s|t)").matcher(input);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                break;
            }
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (matcher.group(2)) {
                case "ms" -> 1L;
                case "t" -> 50L;
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                default -> 86_400_000L;
            };
            total = Math.addExact(total, Math.multiplyExact(amount, unit));
            end = matcher.end();
        }
        if (end == 0 || end != input.length()) {
            throw new ArgumentTypes.InvalidArgument("'" + text + "' is not a duration (examples: 30s, 5m, 1h30m, 2d).");
        }
        return total;
    }

    // ================================================================= help and completion

    static String usageOf(BoundCommand command, String label) {
        BoundCommand.Options options = command.options();
        if (!options.usage().isEmpty()) {
            return options.usage();
        }
        StringJoiner usage = new StringJoiner(" ");
        usage.add(label);
        for (BoundCommand.Parameter parameter : command.parameters()) {
            String name = parameter.name() + (parameter.rest() ? "..." : "");
            usage.add(parameter.optional() ? "[" + name + "]" : "<" + name + ">");
        }
        return usage.toString();
    }

    private static String subcommandList(Node node) {
        StringJoiner list = new StringJoiner(", ");
        for (Node child : node.children.values()) {
            list.add(child.word);
        }
        return list.toString();
    }

    private List<String> completions(Node root, Object sender, String[] arguments) {
        Node node = root;
        int consumed = 0;
        while (consumed < arguments.length - 1) {
            Node child = node.children.get(arguments[consumed].toLowerCase(Locale.ROOT));
            if (child == null) {
                break;
            }
            node = child;
            consumed++;
        }
        String prefix = arguments.length == 0 ? "" : arguments[arguments.length - 1].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        if (consumed == arguments.length - 1 || arguments.length == 0) {
            for (Node child : node.children.values()) {
                if (child.word.startsWith(prefix) && visible(child, sender)) {
                    suggestions.add(child.word);
                }
            }
        }
        if (node.entry != null && visible(node, sender)) {
            int parameterIndex = arguments.length - 1 - consumed;
            List<BoundCommand.Parameter> parameters = node.entry.command().parameters();
            if (parameterIndex >= 0 && !parameters.isEmpty()) {
                BoundCommand.Parameter parameter = parameters.get(Math.min(parameterIndex, parameters.size() - 1));
                if (parameterIndex < parameters.size() || parameter.rest()) {
                    suggestions.addAll(suggest(parameter.type().nonNullable(), prefix, sender));
                }
            }
        }
        return suggestions;
    }

    private boolean visible(Node node, Object sender) {
        if (node.entry == null) {
            return true;
        }
        String permission = node.entry.command().options().permission();
        return permission.isEmpty() || platform.commands().hasPermission(sender, permission);
    }

    private List<String> suggest(Type type, String prefix, Object sender) {
        if (type == PrimitiveType.BOOL) {
            return List.of("true", "false").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (type instanceof ClassType classType && classType != Types.STRING && platform.arguments().supports(classType)) {
            return platform.arguments().suggest(classType, prefix, sender);
        }
        return List.of();
    }

    private void send(Object sender, String message, Map<String, String> values) {
        try {
            platform.commands().send(sender, formatter.format(message, values));
        } catch (RuntimeException error) {
            platform.logger().warn("Cannot send a command message (" + error.getMessage() + "): " + message);
        }
    }
}
