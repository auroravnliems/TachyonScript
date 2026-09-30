package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.platform.paper.PaperContext;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBarViewer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss bar helpers of the standard library bindings. Boss bars are Adventure boss bars: they
 * are thread-safe and shown per player. A boss bar belongs to the script that created it and
 * is hidden from everyone when that script reloads.
 */
public final class Bars {

    private Bars() {
    }

    public static BossBar create(PaperContext context, Component title, double progress, BossBar.Color color,
                                 BossBar.Overlay overlay) {
        BossBar bar = BossBar.bossBar(title, (float) Math.max(0, Math.min(1, progress)), color, overlay);
        return context.own(bar, owned -> hideAll((BossBar) owned));
    }

    public static void flag(BossBar bar, BossBar.Flag flag, boolean enabled) {
        if (enabled) {
            bar.addFlag(flag);
        } else {
            bar.removeFlag(flag);
        }
    }

    public static List<Object> viewers(BossBar bar) {
        List<Object> players = new ArrayList<>();
        for (BossBarViewer viewer : bar.viewers()) {
            if (viewer instanceof Player player) {
                players.add(player);
            }
        }
        return players;
    }

    public static void hideAll(BossBar bar) {
        List<BossBarViewer> viewers = new ArrayList<>();
        bar.viewers().forEach(viewers::add);
        for (BossBarViewer viewer : viewers) {
            if (viewer instanceof Audience audience) {
                audience.hideBossBar(bar);
            }
        }
    }
}
