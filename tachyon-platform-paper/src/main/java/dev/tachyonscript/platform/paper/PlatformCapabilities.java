package dev.tachyonscript.platform.paper;

import org.bukkit.Bukkit;

/**
 * What the running server supports, detected once at startup from its API (never from
 * brand strings): whether regions tick in parallel (Folia), and version information.
 *
 * @param folia            whether the server runs regions on separate threads
 * @param serverName       server implementation name, e.g. {@code Paper}
 * @param minecraftVersion Minecraft version, e.g. {@code 1.21.11}
 */
public record PlatformCapabilities(boolean folia, String serverName, String minecraftVersion) {

    /** Folia's regionized server class; only present when regions tick in parallel. */
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    public static PlatformCapabilities detect() {
        return new PlatformCapabilities(classExists(FOLIA_MARKER), Bukkit.getName(), Bukkit.getMinecraftVersion());
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, PlatformCapabilities.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
