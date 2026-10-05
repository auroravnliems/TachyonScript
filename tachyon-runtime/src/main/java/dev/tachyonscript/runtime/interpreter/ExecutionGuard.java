package dev.tachyonscript.runtime.interpreter;

/** Host-owned revocation checked at every interpreter instruction, including imported functions. */
public interface ExecutionGuard {
    boolean securityRevoked();
}
