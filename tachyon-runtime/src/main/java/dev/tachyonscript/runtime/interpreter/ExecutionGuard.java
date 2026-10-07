package dev.tachyonscript.runtime.interpreter;

/**
 * Host-owned revocation of a script's code, including functions other scripts imported from it.
 *
 * <p>The interpreter asks at the points where a revoked script could still act or run on:
 * every function entry, every native call and the watchdog's loop checks. Between those points
 * code only computes and updates script data; it reaches the server only through natives. So a
 * revoked script stops before its next native call, function call or batch of loop iterations,
 * without paying for a check on every instruction. Implementations must stay cheap (a few
 * volatile reads): they run on every script call.
 */
public interface ExecutionGuard {
    boolean securityRevoked();
}
