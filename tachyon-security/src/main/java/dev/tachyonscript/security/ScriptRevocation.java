package dev.tachyonscript.security;

/** Revocation changes an epoch: restoring permits new versions, never resurrects old exported functions. */
public final class ScriptRevocation {
    private volatile long epoch;
    private volatile boolean blocked;
    public ScriptRevocation(boolean blocked) { this.blocked = blocked; }
    public long epoch() { return epoch; }
    public boolean blocked() { return blocked; }
    public synchronized void block() { blocked = true; epoch++; }
    public synchronized void restore() { blocked = false; }
}
