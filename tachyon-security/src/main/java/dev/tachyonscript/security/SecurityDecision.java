package dev.tachyonscript.security;

public enum SecurityDecision {
    ALLOW, WARN, DISABLE, QUARANTINE;

    public boolean deniesExecution() {
        return this == DISABLE || this == QUARANTINE;
    }
}
