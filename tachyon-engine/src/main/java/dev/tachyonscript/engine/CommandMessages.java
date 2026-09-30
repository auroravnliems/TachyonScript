package dev.tachyonscript.engine;

import java.util.Map;
import java.util.Objects;

/**
 * Messages sent by script commands, as MiniMessage text with placeholders such as
 * {@code <usage>}. Placeholder values are always inserted as plain text, so a player's input
 * shown in a message can never add formatting. Servers override them in their configuration.
 *
 * @param usage             {@code <usage>}: the command's usage line
 * @param noPermission      sent when the sender lacks the command's permission
 * @param playerOnly        sent when the console runs a player-only command
 * @param cooldown          {@code <remaining>}: time left before the command can be used again
 * @param notANumber        {@code <input>}: text that should have been a number
 * @param invalidArgument   {@code <message>}: why an argument is invalid
 * @param missingArgument   {@code <name>}, {@code <usage>}: a required argument is missing
 * @param tooManyArguments  {@code <usage>}: more arguments than the command takes
 * @param error             sent when the command's script fails
 * @param subcommands       {@code <label>}, {@code <commands>}: the sub-commands of a command group
 */
public record CommandMessages(String usage, String noPermission, String playerOnly, String cooldown, String notANumber,
                              String invalidArgument, String missingArgument, String tooManyArguments, String error,
                              String subcommands) {

    public static final CommandMessages DEFAULT = new CommandMessages(
            "<red>Usage: <usage>",
            "<red>You do not have permission to use this command.",
            "<red>Only players can use this command.",
            "<red>Please wait <remaining> before using this command again.",
            "<red>'<input>' is not a number.",
            "<red><message>",
            "<red>Missing <name>. Usage: <usage>",
            "<red>Too many arguments. Usage: <usage>",
            "<red>An error occurred while running this command.",
            "<gold>/<label></gold> <gray>sub-commands:</gray> <commands>");

    public CommandMessages {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(noPermission, "noPermission");
        Objects.requireNonNull(playerOnly, "playerOnly");
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(notANumber, "notANumber");
        Objects.requireNonNull(invalidArgument, "invalidArgument");
        Objects.requireNonNull(missingArgument, "missingArgument");
        Objects.requireNonNull(tooManyArguments, "tooManyArguments");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(subcommands, "subcommands");
    }

    /** These messages with the given ones replaced (keys are the component names; unknown keys are ignored). */
    public CommandMessages with(Map<String, String> overrides) {
        return new CommandMessages(
                overrides.getOrDefault("usage", usage),
                overrides.getOrDefault("no-permission", noPermission),
                overrides.getOrDefault("player-only", playerOnly),
                overrides.getOrDefault("cooldown", cooldown),
                overrides.getOrDefault("not-a-number", notANumber),
                overrides.getOrDefault("invalid-argument", invalidArgument),
                overrides.getOrDefault("missing-argument", missingArgument),
                overrides.getOrDefault("too-many-arguments", tooManyArguments),
                overrides.getOrDefault("error", error),
                overrides.getOrDefault("subcommands", subcommands));
    }
}
