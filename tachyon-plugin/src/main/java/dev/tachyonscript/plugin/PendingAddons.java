package dev.tachyonscript.plugin;

import dev.tachyonscript.api.addon.AddonRegistrar;
import dev.tachyonscript.api.addon.TachyonAddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects addons until the first script load, which {@link #close() closes} registration:
 * the registry scripts are compiled against is immutable from then on.
 */
final class PendingAddons implements AddonRegistrar {

    private final List<TachyonAddon> addons = new ArrayList<>();
    private boolean closed;

    @Override
    public synchronized void register(TachyonAddon addon) {
        Objects.requireNonNull(addon, "addon");
        if (closed) {
            throw new IllegalStateException("TachyonScript has already loaded its scripts. Register addons in the "
                    + "onEnable of a plugin that depends on TachyonScript.");
        }
        addons.add(addon);
    }

    /** Ends registration and returns the addons in registration order. */
    synchronized List<TachyonAddon> close() {
        closed = true;
        return List.copyOf(addons);
    }
}
