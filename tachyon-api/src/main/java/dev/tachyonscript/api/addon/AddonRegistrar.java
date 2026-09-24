package dev.tachyonscript.api.addon;

/**
 * Accepts {@link TachyonAddon}s. On Paper and Folia the TachyonScript plugin provides it
 * through Bukkit's services manager:
 *
 * <pre>{@code
 * AddonRegistrar registrar = getServer().getServicesManager().load(AddonRegistrar.class);
 * registrar.register(new CoinsAddon());
 * }</pre>
 *
 * <p>Scripts are loaded once the server has finished starting, so plugins that depend on
 * TachyonScript register their addons in {@code onEnable}.
 */
public interface AddonRegistrar {

    /**
     * Registers an addon for the first script load.
     *
     * @throws IllegalStateException if scripts have already been loaded; addons cannot be
     *                               added to a running registry
     */
    void register(TachyonAddon addon);
}
