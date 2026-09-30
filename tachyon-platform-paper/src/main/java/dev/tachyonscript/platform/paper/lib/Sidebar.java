package dev.tachyonscript.platform.paper.lib;

import dev.tachyonscript.api.natives.ScriptError;
import dev.tachyonscript.platform.paper.PaperContext;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;

/**
 * A sidebar created by a script: a private scoreboard whose sidebar objective shows up to 15
 * lines of text without score numbers. Changes are applied on the main thread. A sidebar
 * belongs to the script that created it and is removed from its viewers when that script
 * reloads. Folia has no scoreboards, so sidebars are not available there.
 */
public final class Sidebar {

    private static final int MAX_LINES = 15;

    private final PaperContext context;
    private final List<Component> lines = new ArrayList<>();
    private final Scoreboard board;
    private final Objective objective;
    private Component title;
    /** Number of score entries currently shown. */
    private int shown;

    private Sidebar(PaperContext context, Component title) {
        this.context = context;
        this.title = title;
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = board.registerNewObjective("tachyonscript", Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
    }

    public static Sidebar create(PaperContext context, Component title) {
        if (context.folia()) {
            throw new ScriptError("Sidebars are not available on Folia (it has no scoreboards).");
        }
        // Created on the main thread, owned by the script running on this one.
        Sidebar sidebar = context.callGlobal(() -> new Sidebar(context, title));
        return context.own(sidebar, owned -> ((Sidebar) owned).remove());
    }

    public synchronized Component title() {
        return title;
    }

    public void title(Component value) {
        synchronized (this) {
            title = value;
        }
        context.global(() -> objective.displayName(value));
    }

    public synchronized List<Object> lines() {
        return new ArrayList<>(lines);
    }

    public void lines(List<Object> values) {
        if (values.size() > MAX_LINES) {
            throw new ScriptError("A sidebar shows at most " + MAX_LINES + " lines, not " + values.size() + ".");
        }
        synchronized (this) {
            lines.clear();
            for (Object value : values) {
                lines.add(value == null ? Component.empty() : (Component) value);
            }
        }
        refresh();
    }

    public void line(int index, Component text) {
        if (index < 0 || index >= MAX_LINES) {
            throw new ScriptError("Sidebar lines go from 0 to " + (MAX_LINES - 1) + ", not " + index + ".");
        }
        synchronized (this) {
            while (lines.size() <= index) {
                lines.add(Component.empty());
            }
            lines.set(index, text);
        }
        refresh();
    }

    public void removeLine(int index) {
        synchronized (this) {
            if (index < 0 || index >= lines.size()) {
                return;
            }
            lines.remove(index);
        }
        refresh();
    }

    public void clear() {
        synchronized (this) {
            lines.clear();
        }
        refresh();
    }

    private void refresh() {
        context.global(this::apply);
    }

    /** Writes the lines into the scoreboard (main thread). */
    private synchronized void apply() {
        int count = lines.size();
        for (int i = 0; i < count; i++) {
            Score score = objective.getScore(entry(i));
            score.setScore(count - i);
            score.customName(lines.get(i));
        }
        for (int i = count; i < shown; i++) {
            board.resetScores(entry(i));
        }
        shown = count;
    }

    private static String entry(int index) {
        return "line" + index;
    }

    public void show(Player player) {
        player.setScoreboard(board);
    }

    public void hide(Player player) {
        if (player.getScoreboard() == board) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public List<Object> viewers() {
        List<Object> viewers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard() == board) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    /** Hides the sidebar from everyone (when its script unloads). */
    void remove() {
        for (Object viewer : viewers()) {
            Player player = (Player) viewer;
            context.forEntity(player, () -> hide(player));
        }
    }
}
