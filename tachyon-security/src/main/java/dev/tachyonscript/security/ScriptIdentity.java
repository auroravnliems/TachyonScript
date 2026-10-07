package dev.tachyonscript.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Paths originate only from registered compiler sources, never from an AI action. */
public final class ScriptIdentity {
    /** Looking up the algorithm costs more than hashing a short key; the analyzer hashes thousands. */
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    });

    private ScriptIdentity() { }

    public static String of(String path) {
        if (path == null || path.isBlank() || path.length() > 1024 || path.chars().anyMatch(c -> c < 32)
                || path.startsWith("/") || path.contains(":")
                || path.contains("\\") || path.indexOf('\0') >= 0 || !path.endsWith(".tys")) {
            throw new IllegalArgumentException("Invalid registered script path");
        }
        for (String part : path.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Invalid registered script path");
            }
        }
        return "ts-" + hash(path).substring(0, 32);
    }

    public static String hash(String content) {
        // digest() also resets the instance for the next call on this thread.
        return HexFormat.of().formatHex(SHA_256.get().digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
