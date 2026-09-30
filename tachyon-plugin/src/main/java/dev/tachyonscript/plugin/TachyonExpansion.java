package dev.tachyonscript.plugin;

import dev.tachyonscript.api.TachyonVersion;
import dev.tachyonscript.engine.ScriptEngine;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * The PlaceholderAPI expansion {@code tys}: {@code %tys_<name>%} runs the script placeholder
 * {@code placeholder <name> { ... }}, and {@code %tys_<name>_<argument>%} passes an argument.
 * Only loaded when PlaceholderAPI is installed.
 */
final class TachyonExpansion extends PlaceholderExpansion {

    private final TachyonPlugin plugin;

    TachyonExpansion(TachyonPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "tys";
    }

    @Override
    public String getAuthor() {
        return "TachyonScript";
    }

    @Override
    public String getVersion() {
        return TachyonVersion.RUNTIME;
    }

    /** Stays registered when PlaceholderAPI reloads its expansions. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        ScriptEngine engine = plugin.engine();
        return engine == null ? null : engine.placeholder(params, player);
    }
}
