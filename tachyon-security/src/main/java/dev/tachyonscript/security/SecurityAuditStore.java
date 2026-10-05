package dev.tachyonscript.security;

import dev.tachyonscript.language.source.SourceFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fsynced, append-only hash-chained audit; source snapshots are immutable and never removed. */
public final class SecurityAuditStore implements AutoCloseable {
    private final Path folder;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, SecurityIncident> incidents = new LinkedHashMap<>();
    private final Map<String, SecurityIncident> blocked = new LinkedHashMap<>();
    private final Map<String, SecurityIncident> approvals = new LinkedHashMap<>();
    private String previousHash = "0".repeat(64);

    public SecurityAuditStore(Path folder) throws IOException {
        this.folder = folder == null ? null : folder.toAbsolutePath().normalize();
        if (folder == null) { lockChannel = null; lock = null; return; }
        safe(this.folder);
        Files.createDirectories(this.folder);
        safe(this.folder);
        Path lockPath = path("audit.lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (IOException | RuntimeException failure) { lockChannel.close(); throw failure; }
        lock = acquired;
        if (lock == null) { lockChannel.close(); throw new IOException("Security audit directory is already in use"); }
        try { load(); recoverUnjournaledReports(); }
        catch (IOException | RuntimeException failure) { close(); throw failure; }
    }

    static void safe(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Security storage cannot follow symbolic links");
    }
    private Path path(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid security storage identifier");
        Path result = folder.resolve(name);
        safe(result);
        return result;
    }

    public static String incidentId() {
        return "TS-SEC-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.now())
                + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    public synchronized void append(SecurityIncident incident, SourceFile source) throws IOException {
        if (incidents.containsKey(incident.id())) throw new IOException("Duplicate security incident");
        if (source != null && (!source.hash().equals(incident.sha256()) || !source.path().equals(incident.file())))
            throw new IOException("Incident source snapshot mismatch");
        String json = SecurityJson.write(incident.toJson());
        if (json.length() > 4_000_000) throw new IOException("Security audit record exceeds bounded record size");
        if (folder != null) {
            if (source != null && incident.decision().deniesExecution()) {
                Path snapshot = path(incident.id() + ".source.tys");
                writeNew(snapshot, source.content());
            }
            writeNew(path(incident.id() + ".report.json"), json);
            String hash = ScriptIdentity.hash(previousHash + json);
            appendFile(path("audit.jsonl"), SecurityJson.write(Map.of("previousHash", previousHash, "hash", hash, "incident", incident.toJson())) + "\n");
            previousHash = hash;
        }
        apply(incident, true);
    }

    private void recoverUnjournaledReports() throws IOException {
        // Immutable reports are write-ahead records for local revocation, never a historical webhook replay source.
        try (var entries = Files.list(folder)) {
            for (Path file : entries.filter(p -> p.getFileName().toString().endsWith(".report.json")).sorted().toList()) {
                safe(file);
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".report.json".length());
                if (incidents.containsKey(id)) continue;
                if (!id.matches("TS-SEC-[0-9]{8}-[0-9a-f]{32}") || Files.size(file) > 4_194_304)
                    throw new IOException("Invalid security write-ahead report");
                SecurityIncident incident;
                try { incident = SecurityIncident.read(SecurityJson.object(SecurityJson.parse(Files.readString(file, StandardCharsets.UTF_8)))); }
                catch (RuntimeException e) { throw new IOException("Invalid incomplete security report; activation blocked", e); }
                if (!incident.id().equals(id)) throw new IOException("Security report identity mismatch");
                String json = SecurityJson.write(incident.toJson());
                // A recovered older approval/restore must never undo a newer committed disable.
                boolean applyState = incident.decision().deniesExecution() || incidents.values().stream().noneMatch(existing ->
                        existing.scriptId().equals(incident.scriptId()) && !existing.timestamp().isBefore(incident.timestamp()));
                String hash = ScriptIdentity.hash(previousHash + json + "|stateApplied=" + applyState);
                appendFile(path("audit.jsonl"), SecurityJson.write(Map.of("previousHash", previousHash, "hash", hash,
                        "incident", incident.toJson(), "stateApplied", applyState)) + "\n");
                previousHash = hash; apply(incident, applyState);
            }
        }
    }

    private void apply(SecurityIncident incident, boolean applyState) {
        incidents.put(incident.id(), incident);
        if (!applyState) return;
        if (incident.decision().deniesExecution()) { blocked.put(incident.scriptId(), incident); approvals.remove(incident.scriptId()); }
        if (incident.action().equals("RESTORE") || incident.action().equals("ENABLE")) blocked.remove(incident.scriptId());
        if (incident.action().equals("APPROVE")) approvals.put(incident.scriptId(), incident);
    }
    public synchronized Map<String, SecurityIncident> blocked() { return Map.copyOf(blocked); }
    public synchronized boolean approved(String id, String hash) {
        SecurityIncident approval = approvals.get(id); return approval != null && hash.equals(approval.sha256());
    }
    public synchronized boolean approved(String id, String hash, String context) {
        SecurityIncident approval = approvals.get(id);
        return approved(id, hash) && !context.isEmpty() && context.equals(approval.approvalContext());
    }
    public synchronized SecurityIncident incident(String id) { return incidents.get(id); }
    public synchronized List<SecurityIncident> incidents() { return new ArrayList<>(incidents.values()); }

    public synchronized SourceFile snapshot(String incidentId) throws IOException {
        SecurityIncident incident = incidents.get(incidentId);
        if (incident == null || !incident.decision().deniesExecution() || folder == null) throw new IOException("No stored quarantine source");
        Path file = path(incidentId + ".source.tys");
        if (Files.size(file) > 16_777_216) throw new IOException("Stored source exceeds limit");
        SourceFile source = new SourceFile(incident.file(), Files.readString(file, StandardCharsets.UTF_8));
        if (!source.hash().equals(incident.sha256())) throw new IOException("Quarantine source hash mismatch");
        return source;
    }

    private void load() throws IOException {
        Path file = path("audit.jsonl");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.length() > 4_194_304) throw new IOException("Audit record exceeds limit");
                var value = SecurityJson.object(SecurityJson.parse(line));
                var event = SecurityJson.object(value.get("incident"));
                boolean applyState = !value.containsKey("stateApplied") || Boolean.TRUE.equals(value.get("stateApplied"));
                if (value.containsKey("stateApplied") && !(value.get("stateApplied") instanceof Boolean))
                    throw new IOException("Invalid recovered audit state");
                String hash = ScriptIdentity.hash(previousHash + SecurityJson.write(event)
                        + (value.containsKey("stateApplied") ? "|stateApplied=" + applyState : ""));
                if (!previousHash.equals(SecurityJson.string(value, "previousHash")) || !hash.equals(SecurityJson.string(value, "hash")))
                    throw new IOException("Security audit integrity check failed; activation blocked");
                SecurityIncident incident = SecurityIncident.read(event);
                if (incidents.containsKey(incident.id())) throw new IOException("Duplicate persisted incident");
                apply(incident, applyState); previousHash = hash;
            }
        } catch (IllegalArgumentException e) { throw new IOException("Invalid security audit; activation blocked", e); }
    }

    private static void writeNew(Path file, String text) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            write(channel, text); channel.force(true);
        }
    }
    static void appendFile(Path file, String text) throws IOException {
        safe(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            write(channel, text); channel.force(true);
        }
    }
    private static void write(FileChannel channel, String text) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
        while (bytes.hasRemaining()) channel.write(bytes);
    }
    public Path folder() { return folder; }
    @Override public synchronized void close() throws IOException {
        if (lock != null && lock.isValid()) lock.release();
        if (lockChannel != null) lockChannel.close();
    }
}
