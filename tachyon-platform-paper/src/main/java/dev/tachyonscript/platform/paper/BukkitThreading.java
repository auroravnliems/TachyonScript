package dev.tachyonscript.platform.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * {@link Threading} on a running server: the entity's scheduler, the region scheduler or the
 * global region scheduler on Folia, the main thread on Paper.
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
        if (ownsEntity(entity)) {
            action.run();
        } else if (folia) {
            entity.getScheduler().run(plugin, task -> action.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    @Override
    public void forRegion(Location location, Runnable action) {
        if (ownsRegion(location)) {
            action.run();
        } else if (folia) {
            Bukkit.getRegionScheduler().execute(plugin, location, action);
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    @Override
    public void global(Runnable action) {
        if (ownsGlobal()) {
            action.run();
        } else if (folia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, action);
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        return folia ? Bukkit.isOwnedByCurrentRegion(entity) : Bukkit.isPrimaryThread();
    }

    @Override
    public boolean ownsRegion(Location location) {
        return folia ? Bukkit.isOwnedByCurrentRegion(location) : Bukkit.isPrimaryThread();
    }

    @Override
    public boolean ownsGlobal() {
        return folia ? Bukkit.isGlobalTickThread() : Bukkit.isPrimaryThread();
    }

    @Override
    public boolean onTickThread() {
        return Bukkit.isPrimaryThread();
    }
}
