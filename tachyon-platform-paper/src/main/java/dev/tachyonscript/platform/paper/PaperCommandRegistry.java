package dev.tachyonscript.platform.paper;

import dev.tachyonscript.engine.spi.CommandRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.help.HelpMap;
import org.bukkit.help.HelpTopic;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers script commands in the server's command map under the {@code tachyonscript}
 * fallback prefix ({@code /heal} and {@code /tachyonscript:heal}), replacing the previous set
 * on every load, refreshes the players' command lists so tab completion knows them, and
 * gives every command a {@code /help} page.
 */
final class PaperCommandRegistry implements CommandRegistry {

    private static final String PREFIX = "tachyonscript";

    private final Plugin plugin;
    private final List<ScriptCommand> registered = new ArrayList<>();

    PaperCommandRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    /** A Bukkit command running a script command. */
    private static final class ScriptCommand extends org.bukkit.command.Command {
        private final CommandRegistry.Command command;

        ScriptCommand(CommandRegistry.Command command) {
            super(command.name(), command.description(), command.usage(), command.aliases());
            this.command = command;
            if (!command.permission().isEmpty()) {
                setPermission(command.permission());
            }
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            command.execute(sender, label, args);
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
            return command.complete(sender, alias, args);
        }
    }

    /**
     * The {@code /help} page of a script command. The server builds its help pages once, when
     * it starts, so commands registered later need their own topic. Topics cannot be removed
     * from the help map, so each one looks up the command of the active scripts every time it
     * is shown: it follows reloads, and it is hidden once no script declares the command.
     */
    private final class ScriptHelpTopic extends HelpTopic {
        private final String command;

        ScriptHelpTopic(String command) {
            this.command = command;
            this.name = "/" + command;
        }

        private @Nullable ScriptCommand current() {
            return active.get(command);
        }

        @Override
        public boolean canSee(@NotNull CommandSender sender) {
            ScriptCommand current = current();
            if (current == null) {
                return false;
            }
            if (amendedPermission != null && !amendedPermission.isEmpty()) {
                return sender.hasPermission(amendedPermission);
            }
            return current.testPermissionSilent(sender);
        }

        @Override
        public @NotNull String getShortText() {
            ScriptCommand current = current();
            if (current == null) {
                return "";
            }
            String description = current.getDescription().strip();
            return description.isEmpty() ? current.getUsage() : description.lines().findFirst().orElse("");
        }

        @Override
        public @NotNull String getFullText(@NotNull CommandSender forWho) {
            ScriptCommand current = current();
            if (current == null) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            if (!current.getDescription().isBlank()) {
                text.append(GOLD).append("Description: ").append(WHITE).append(current.getDescription().strip());
            }
            List<CommandRegistry.HelpEntry> entries = current.command.helpEntries().stream()
                    .filter(entry -> entry.permission().isEmpty() || forWho.hasPermission(entry.permission()))
                    .toList();
            if (entries.size() == 1 && entries.getFirst().usage().equals(current.getUsage())) {
                line(text).append(GOLD).append("Usage: ").append(WHITE).append(current.getUsage());
            } else if (!entries.isEmpty()) {
                line(text).append(GOLD).append("Commands:");
                for (CommandRegistry.HelpEntry entry : entries) {
                    line(text).append(WHITE).append(entry.usage());
                    // The root's own description is already shown above.
                    boolean root = entry.usage().equals(current.getUsage());
                    String description = entry.description().strip();
                    if (!root && !description.isEmpty()) {
                        text.append(GRAY).append(" - ").append(description.lines().findFirst().orElse(""));
                    }
                }
            }
            if (!current.getAliases().isEmpty()) {
                line(text).append(GOLD).append("Aliases: ").append(WHITE).append("/")
                        .append(String.join(", /", current.getAliases()));
            }
            return text.toString();
        }

        private static StringBuilder line(StringBuilder text) {
            return text.isEmpty() ? text : text.append('\n');
        }
    }

    // Help pages use legacy formatting codes (§6 gold, §f white, §7 gray).
    private static final String GOLD = "§6";
    private static final String WHITE = "§f";
    private static final String GRAY = "§7";

    /** The registered script commands by name, read by the help topics. */
    private final Map<String, ScriptCommand> active = new ConcurrentHashMap<>();
    /** Names that already have a help topic (topics stay in the help map for good). */
    private final Set<String> helpTopics = new HashSet<>();

    @Override
    public synchronized void update(List<CommandRegistry.Command> commands) {
        CommandMap map = Bukkit.getCommandMap();
        Map<String, org.bukkit.command.Command> known = map.getKnownCommands();
        for (ScriptCommand old : registered) {
            old.unregister(map);
            known.values().removeIf(command -> command == old);
        }
        registered.clear();
        Map<String, ScriptCommand> named = new HashMap<>();
        for (CommandRegistry.Command command : commands) {
            ScriptCommand wrapper = new ScriptCommand(command);
            if (map.register(command.name(), PREFIX, wrapper)) {
                named.put(command.name(), wrapper);
            } else {
                plugin.getLogger().warning("Another plugin already uses /" + command.name()
                        + "; the script command is available as /" + PREFIX + ":" + command.name() + ".");
            }
            registered.add(wrapper);
        }
        active.putAll(named);
        active.keySet().retainAll(named.keySet());
        HelpMap help = Bukkit.getHelpMap();
        for (String name : named.keySet()) {
            if (helpTopics.add(name)) {
                help.addTopic(new ScriptHelpTopic(name));
            }
        }
        // Players' clients keep a list of commands for completion; send the new one.
        if (plugin.isEnabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(plugin, task -> player.updateCommands(), null);
            }
        }
    }

    @Override
    public boolean hasPermission(Object sender, String permission) {
        return ((CommandSender) sender).hasPermission(permission);
    }

    @Override
    public Object asPlayer(Object sender) {
        return sender instanceof Player player ? player : null;
    }

    @Override
    public String id(Object sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : ((CommandSender) sender).getName();
    }

    @Override
    public void send(Object sender, Object component) {
        ((CommandSender) sender).sendMessage((Component) component);
    }
}
