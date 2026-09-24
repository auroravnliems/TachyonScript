package dev.tachyonscript.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * {@link Threading} on a running server: the entity's scheduler or the global region
 * scheduler on Folia, the main thread on Paper.
 */
final class BukkitThreading implements Threading {

    private final Plugin plugin;
    private final boolean folia;

    BukkitThreading(Plugin plugin, boolean folia) {
        this.plugin = plugin;
        this.folia = folia;
    }

    @Override
    public boolean folia() {
        return folia;
    }

    @Override
    public void forEntity(Entity entity, Runnable action) {
        if (folia) {
            if (Bukkit.isOwnedByCurrentRegion(entity)) {
                action.run();
            } else {
                entity.getScheduler().run(plugin, task -> action.run(), null);
            }
        } else if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    @Override
    public void global(Runnable action) {
        if (folia) {
            if (Bukkit.isGlobalTickThread()) {
                action.run();
            } else {
                Bukkit.getGlobalRegionScheduler().execute(plugin, action);
            }
        } else if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }
}
