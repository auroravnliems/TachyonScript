package dev.tachyonscript.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Validated AI review results by exact input, kept across restarts so that an unchanged script
 * is never sent to the provider again.
 *
 * <p>The key covers everything the provider saw or that shaped its answer: the manifest (source
 * hash, nodes, deterministic findings), the approval context (dependency and importer hashes,
 * network exceptions), and the provider settings and prompt. Only the AI findings are stored;
 * decisions are recomputed from them with the current thresholds on every load. The file is a
 * cache, not an audit record: when it is missing or damaged, scripts are simply reviewed again.
 */
final class AiReviewCache {
    static final String FILE = "ai-reviews.json";
    private static final int MAX_ENTRIES = 512;
    private static final long MAX_FILE_BYTES = 16_777_216;

    private record Entry(List<SecurityFinding> findings, Instant reviewed) { }

    private final Path file;
    private final Consumer<String> errors;
    /** Access-ordered: the least recently used review is dropped first. */
    private final Map<String, Entry> entries = new LinkedHashMap<>(64, .75f, true);

    /** @param folder the security folder, or {@code null} to keep reviews in memory only */
    AiReviewCache(Path folder, Consumer<String> errors) {
        this.errors = errors;
        Path resolved = null;
        if (folder != null) {
            try {
                resolved = folder.resolve(FILE);
                SecurityAuditStore.safe(resolved);
                load(resolved);
            } catch (IOException | RuntimeException e) {
                entries.clear();
                errors.accept("Stored AI security reviews could not be read; affected scripts will be reviewed again.");
            }
        }
        this.file = resolved;
    }

    synchronized List<SecurityFinding> get(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.findings();
    }

    synchronized void put(String key, List<SecurityFinding> findings) {
        if (findings.stream().anyMatch(finding -> finding.origin() != SecurityFinding.Origin.AI)) {
            throw new IllegalArgumentException("Only AI findings are cached");
        }
        entries.put(key, new Entry(List.copyOf(findings), Instant.now()));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
        save();
    }

    synchronized int size() {
        return entries.size();
    }

    private void load(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("AI review cache exceeds its size limit");
        }
        var root = SecurityJson.object(SecurityJson.parse(Files.readString(path, StandardCharsets.UTF_8)));
        if (SecurityJson.integer(root, "schemaVersion") != 1) {
            throw new IOException("Unknown AI review cache version");
        }
        for (Object item : SecurityJson.array(root.get("entries"))) {
            var value = SecurityJson.object(item);
            String key = SecurityJson.string(value, "key");
            if (!key.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid AI review cache key");
            }
            List<SecurityFinding> findings = new ArrayList<>();
            for (Object finding : SecurityJson.array(value.get("findings"))) {
                SecurityFinding read = SecurityFinding.read(SecurityJson.object(finding));
                if (read.origin() != SecurityFinding.Origin.AI) {
                    throw new IOException("Invalid AI review cache entry");
                }
                findings.add(read);
            }
            entries.put(key, new Entry(List.copyOf(findings), Instant.parse(SecurityJson.string(value, "reviewed"))));
        }
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    /** Rewrites the whole file through a temporary file; a failed write only loses the cache. */
    private void save() {
        if (file == null) {
            return;
        }
        String json = serialize();
        // The file must stay readable by the bounded JSON reader: drop the oldest reviews until it is.
        while (!readable(json) && !entries.isEmpty()) {
            entries.remove(entries.keySet().iterator().next());
            json = serialize();
        }
        Path temporary = null;
        try {
            SecurityAuditStore.safe(file);
            temporary = Files.createTempFile(file.getParent(), "ai-reviews-", ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            errors.accept("AI security reviews could not be stored; they will be repeated after a restart.");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Only a leftover temporary file.
                }
            }
        }
    }

    private String serialize() {
        List<Object> list = new ArrayList<>(entries.size());
        for (var entry : entries.entrySet()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("key", entry.getKey());
            value.put("reviewed", entry.getValue().reviewed().toString());
            value.put("findings", entry.getValue().findings().stream().map(SecurityFinding::toJson).toList());
            list.add(value);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("entries", list);
        return SecurityJson.write(root);
    }

    private static boolean readable(String json) {
        try {
            SecurityJson.parse(json);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
