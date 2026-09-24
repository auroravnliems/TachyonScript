package dev.tachyonscript.engine;

/** What happens when some scripts fail to compile or link. */
public enum LoadMode {
    /** Any failure cancels the whole load; the previous generation stays active unchanged. */
    STRICT,
    /**
     * Failed scripts keep their previous working version (or stay disabled if they have none);
     * every other script is updated.
     */
    LENIENT
}
