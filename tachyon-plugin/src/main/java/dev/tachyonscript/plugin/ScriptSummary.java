package dev.tachyonscript.plugin;

import dev.tachyonscript.api.value.Values;
import dev.tachyonscript.language.semantic.BoundCommand;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.semantic.BoundPlaceholder;
import dev.tachyonscript.language.semantic.BoundTask;
import dev.tachyonscript.language.semantic.GlobalSymbol;
import dev.tachyonscript.language.syntax.Declaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Describes what a script declares, in words, for {@code /tys scripts} and {@code /tys info}:
 * event handlers, commands, tasks, placeholders and saved variables. A script of a 0.2 server
 * often has commands but no event handler, so counting handlers alone would call it empty.
 */
final class ScriptSummary {

    private ScriptSummary() {
    }

    /** For example {@code 2 handlers, 4 commands, 1 task}; only what the script has. */
    static String contents(BoundModule module) {
        List<String> parts = new ArrayList<>();
        count(parts, module.handlers().size(), "handler");
        count(parts, module.commands().size(), "command");
        count(parts, module.tasks().size(), "task");
        count(parts, module.placeholders().size(), "placeholder");
        return parts.isEmpty() ? "no handlers or commands" : String.join(", ", parts);
    }

    /** The commands as players type them: {@code /warp}, {@code /warp set}. */
    static List<String> commands(BoundModule module) {
        List<String> commands = new ArrayList<>();
        for (BoundCommand command : module.commands()) {
            commands.add("/" + String.join(" ", command.path()));
        }
        return commands;
    }

    /** {@code every 5m}, {@code at 08:30}; {@code (async)} for tasks that run off the server thread. */
    static List<String> tasks(BoundModule module) {
        List<String> tasks = new ArrayList<>();
        for (BoundTask task : module.tasks()) {
            String when = task.intervalMillis() > 0
                    ? "every " + Values.durationToString(task.intervalMillis())
                    : String.format(Locale.ROOT, "at %02d:%02d", task.dailyMinute() / 60, task.dailyMinute() % 60);
            tasks.add(task.async() ? when + " (async)" : when);
        }
        return tasks;
    }

    /** {@code %tys_coins%} for each placeholder. */
    static List<String> placeholders(BoundModule module) {
        List<String> placeholders = new ArrayList<>();
        for (BoundPlaceholder placeholder : module.placeholders()) {
            placeholders.add("%tys_" + placeholder.name() + "%");
        }
        return placeholders;
    }

    /** {@code 2 persistent, 1 playerdata}, or an empty string when the script saves nothing. */
    static String savedVariables(BoundModule module) {
        int persistent = 0;
        int playerData = 0;
        for (GlobalSymbol global : module.globals()) {
            if (global.storage() == Declaration.Storage.PERSISTENT) {
                persistent++;
            } else if (global.storage() == Declaration.Storage.PLAYERDATA) {
                playerData++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (persistent > 0) {
            parts.add(persistent + " persistent");
        }
        if (playerData > 0) {
            parts.add(playerData + " playerdata");
        }
        return String.join(", ", parts);
    }

    private static void count(List<String> parts, int count, String noun) {
        if (count > 0) {
            parts.add(count + " " + noun + (count == 1 ? "" : "s"));
        }
    }
}
