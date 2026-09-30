package dev.tachyonscript.platform.paper;

import dev.tachyonscript.engine.spi.Scheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * {@link Scheduler} on Paper and Folia through the region-aware schedulers of the Paper API:
 * the global region scheduler (the main thread on Paper), entity schedulers (the thread of the
 * entity's region; tasks are dropped when the entity is removed) and the asynchronous
 * scheduler.
 */
final class PaperScheduler implements Scheduler {

    /** A handle that is never started (the entity was already removed). */
    private static final Handle NONE = () -> {
    };

    private final Plugin plugin;
    private final boolean folia;

    PaperScheduler(Plugin plugin, boolean folia) {
        this.plugin = plugin;
        this.folia = folia;
    }

    private static Handle handle(ScheduledTask task) {
        return task == null ? NONE : task::cancel;
    }

    @Override
    public Handle runLater(long delayTicks, Runnable task) {
        return handle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), Math.max(1, delayTicks)));
    }

    @Override
    public Handle runRepeating(long delayTicks, long periodTicks, Runnable task) {
        return handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(),
                Math.max(1, delayTicks), Math.max(1, periodTicks)));
    }

    @Override
    public Handle runLaterFor(Object entity, long delayTicks, Runnable task) {
        return handle(((Entity) entity).getScheduler().runDelayed(plugin, scheduled -> task.run(), null,
                Math.max(1, delayTicks)));
    }

    @Override
    public Handle runRepeatingFor(Object entity, long delayTicks, long periodTicks, Runnable task) {
        return handle(((Entity) entity).getScheduler().runAtFixedRate(plugin, scheduled -> task.run(), null,
                Math.max(1, delayTicks), Math.max(1, periodTicks)));
    }

    @Override
    public Handle runAsyncLater(long delayMillis, Runnable task) {
        return handle(Bukkit.getAsyncScheduler().runDelayed(plugin, scheduled -> task.run(), Math.max(1, delayMillis),
                TimeUnit.MILLISECONDS));
    }

    @Override
    public Handle runAsyncRepeating(long delayMillis, long periodMillis, Runnable task) {
        return handle(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> task.run(), Math.max(1, delayMillis),
                Math.max(1, periodMillis), TimeUnit.MILLISECONDS));
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    @Override
    public void runGlobal(Runnable task) {
        if (isGlobalThread()) {
            task.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    @Override
    public boolean isGlobalThread() {
        return folia ? Bukkit.isGlobalTickThread() : Bukkit.isPrimaryThread();
    }

    @Override
    public boolean isTickThread() {
        // On Folia every region thread counts as a "primary" thread.
        return Bukkit.isPrimaryThread() || (folia && Bukkit.isGlobalTickThread());
    }
}
