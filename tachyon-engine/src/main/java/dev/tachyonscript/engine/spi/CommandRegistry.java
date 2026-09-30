package dev.tachyonscript.engine.spi;

import java.util.List;

/**
 * Registers the commands declared by scripts with the server, and gives the engine what it
 * needs to run them: permission checks, player lookup and message output.
 */
public interface CommandRegistry {

    /**
     * One runnable command of a tree, as a help page shows it.
     *
     * @param usage       the usage line, e.g. {@code /warp set <name>}
     * @param description the description (possibly empty)
     * @param permission  the permission needed to use it, or an empty string
     */
    record HelpEntry(String usage, String description, String permission) {
    }

    /** A root command ({@code /warp}) with everything the server shows about it. */
    interface Command {
        String name();

        List<String> aliases();

        String description();

        String usage();

        /** Permission needed to see and run the command, or an empty string. */
        String permission();

        /**
         * The runnable commands of this tree for help pages: the root itself (when it runs
         * code) and every sub-command, in declaration order.
         */
        default List<HelpEntry> helpEntries() {
            return List.of();
        }

        /** Runs the command; {@code arguments} are the words after the command name. */
        void execute(Object sender, String label, String[] arguments);

        /** Suggestions for the last of {@code arguments} (which may be empty text). */
        List<String> complete(Object sender, String label, String[] arguments);
    }

    /**
     * Replaces the script commands registered with the server by {@code commands}. Called on
     * the global thread after every load.
     */
    void update(List<Command> commands);

    boolean hasPermission(Object sender, String permission);

    /** The sender as a player object, or {@code null} for the console and other senders. */
    Object asPlayer(Object sender);

    /** A stable identifier of the sender for cooldowns: the player UUID, or the sender name. */
    String id(Object sender);

    /** Sends a formatted message (a component of the platform's text service). */
    void send(Object sender, Object component);
}
