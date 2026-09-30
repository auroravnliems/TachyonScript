package me.clip.placeholderapi.expansion;

import me.clip.placeholderapi.PlaceholderHook;

/**
 * Compile-time stand-in for PlaceholderAPI's class of the same name (only the members
 * TachyonScript uses). It is not packaged: on a server the real class is used.
 */
public abstract class PlaceholderExpansion extends PlaceholderHook {

    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public boolean persist() {
        return false;
    }

    public boolean register() {
        throw new UnsupportedOperationException("PlaceholderAPI stub");
    }

    public boolean unregister() {
        throw new UnsupportedOperationException("PlaceholderAPI stub");
    }
}
