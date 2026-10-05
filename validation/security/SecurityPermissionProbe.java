package dev.tachyonscript.validation;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Test fixture only: grant alerts to one non-OP local client. Never shipped in TachyonScript. */
public final class SecurityPermissionProbe extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }
    @EventHandler public void join(PlayerJoinEvent event) {
        if (event.getPlayer().getName().equals("TSAlert")) {
            event.getPlayer().addAttachment(this, "tachyonscript.security.alerts", true);
            event.getPlayer().addAttachment(this, "tachyonscript.security.admin", true);
        }
        if (event.getPlayer().getName().equals("TSAlert") || event.getPlayer().getName().equals("TSObserver"))
            getLogger().info("SECURITY_SMOKE_ROLE " + event.getPlayer().getName() + " op=" + event.getPlayer().isOp()
                    + " alerts=" + event.getPlayer().hasPermission("tachyonscript.security.alerts"));
    }
}
