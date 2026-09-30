package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.type.Type;
import dev.tachyonscript.language.source.Span;

import java.util.List;

/**
 * A type-checked {@code command} declaration.
 *
 * @param path       command words: {@code ["warp", "set"]} for {@code command warp.set}
 * @param parameters typed parameters, parsed from the command line
 * @param options    permission, aliases, cooldown and messages from the annotations
 * @param function   the body; its parameters are {@code sender}, {@code player}, then the command parameters
 * @param span       the declaration
 */
public record BoundCommand(List<String> path, List<Parameter> parameters, Options options, BoundFunction function,
                           Span span) {

    /**
     * A command parameter.
     *
     * @param name         name shown in usage messages
     * @param type         type the argument is converted to (nullable when optional without default)
     * @param optional     whether the argument may be left out
     * @param defaultValue value used when left out: a boxed constant, or null
     * @param defaultKey   key of a keyed constant used as default ({@code Material.STONE}), or null
     * @param rest         whether the parameter takes the rest of the command line
     */
    public record Parameter(String name, Type type, boolean optional, Object defaultValue, String defaultKey,
                            boolean rest) {
    }

    /**
     * Options from annotations; strings are empty when not given.
     *
     * @param permission        required permission
     * @param permissionMessage MiniMessage text sent when the permission is missing
     * @param aliases           other names of a root command
     * @param description       one line for help and tab lists
     * @param usage             usage text; generated from the parameters when empty
     * @param cooldownMillis    per-player cooldown, 0 for none
     * @param cooldownMessage   MiniMessage text sent while cooling down ({@code <remaining>} is replaced)
     * @param cooldownBypass    permission that skips the cooldown
     * @param playerOnly        whether the console may not run the command
     */
    public record Options(String permission, String permissionMessage, List<String> aliases, String description,
                          String usage, long cooldownMillis, String cooldownMessage, String cooldownBypass,
                          boolean playerOnly) {
        public Options {
            aliases = List.copyOf(aliases);
        }
    }

    public BoundCommand {
        path = List.copyOf(path);
        parameters = List.copyOf(parameters);
    }
}
