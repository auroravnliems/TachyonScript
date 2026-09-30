package dev.tachyonscript.platform.paper;

import dev.tachyonscript.engine.spi.CommandRegistry;
import dev.tachyonscript.engine.spi.Scheduler;

import java.util.List;

/**
 * A scheduler for binding tests without a server: work for "now" runs immediately on the
 * calling thread; delayed and repeating work never runs.
 */
final class InlineScheduler implements Scheduler {

    static final InlineScheduler INSTANCE = new InlineScheduler();

    /** Script commands are not registered in binding tests. */
    static final CommandRegistry NO_COMMANDS = new CommandRegistry() {
        @Override
        public void update(List<Command> commands) {
        }

        @Override
        public boolean hasPermission(Object sender, String permission) {
            return ((org.bukkit.command.CommandSender) sender).hasPermission(permission);
        }

        @Override
        public Object asPlayer(Object sender) {
            return sender instanceof org.bukkit.entity.Player player ? player : null;
        }

        @Override
        public String id(Object sender) {
            return ((org.bukkit.command.CommandSender) sender).getName();
        }

        @Override
        public void send(Object sender, Object component) {
            ((org.bukkit.command.CommandSender) sender).sendMessage((net.kyori.adventure.text.Component) component);
        }
    };

    private static final Handle NONE = () -> {
    };

    private InlineScheduler() {
    }

    @Override
    public Handle runLater(long delayTicks, Runnable task) {
        return NONE;
    }

    @Override
    public Handle runRepeating(long delayTicks, long periodTicks, Runnable task) {
        return NONE;
    }

    @Override
    public Handle runLaterFor(Object entity, long delayTicks, Runnable task) {
        return NONE;
    }

    @Override
    public Handle runRepeatingFor(Object entity, long delayTicks, long periodTicks, Runnable task) {
        return NONE;
    }

    @Override
    public Handle runAsyncLater(long delayMillis, Runnable task) {
        return NONE;
    }

    @Override
    public Handle runAsyncRepeating(long delayMillis, long periodMillis, Runnable task) {
        return NONE;
    }

    @Override
    public void runAsync(Runnable task) {
        task.run();
    }

    @Override
    public void runGlobal(Runnable task) {
        task.run();
    }

    @Override
    public boolean isGlobalThread() {
        return true;
    }

    @Override
    public boolean isTickThread() {
        return true;
    }
}
