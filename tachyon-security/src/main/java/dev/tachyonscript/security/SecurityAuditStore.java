package dev.tachyonscript.security;

import dev.tachyonscript.language.source.SourceFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

/**
 * Fsynced, append-only hash-chained audit; source snapshots are immutable and never removed.
 *
 * <p>The audit only grows, so memory holds a bounded part of it: the most recent incidents,
 * every incident that decides a script's state (blocks and approvals), all incident IDs and a
 * fingerprint per incident for duplicate suppression. Older incidents are read back from their
 * immutable report files when asked for.
 *
 * <p>Damage never stops TachyonScript. Every incident is written as an immutable report (the
 * write-ahead record) before it is chained into the journal. When a journal record does not
 * verify (edited, removed, reordered, cut off, or the journal replaced by another copy), the
 * records before it stay in force, the damaged journal is kept as evidence, and the rest is
 * rebuilt from the reports in time order. Quarantines and disables are never lost: one found
 * only in the damaged part is kept as well, while approvals and restores need their report. A
 * report that cannot be read is set aside. The journal is checked again before every append,
 * so a copy restored over it while the server runs is recovered instead of being extended with
 * records that point at a history it does not contain.
 *
 * <p>All file work runs on a thread that nothing interrupts (see {@link #shielded}).
 */
public final class SecurityAuditStore implements AutoCloseable {
    /** Incidents kept in memory besides the ones deciding a script's state. */
    static final int RECENT = 512;
    static final String JOURNAL = "audit.jsonl";
    /** A rebuilt journal before it replaces the damaged one. */
    private static final String REBUILD = "audit.jsonl.rebuild";
    private static final String REPORT = ".report.json";
    private static final long MAX_RECORD = 4_194_304;
    private static final String GENESIS = "0".repeat(64);
    /** Recovery notices kept for {@code /tys security status}; each is also logged when it happens. */
    private static final int NOTICES = 20;

    private final Path folder;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Consumer<String> notices;
    private final Map<String, SecurityIncident> incidents = new LinkedHashMap<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private final Set<String> fingerprints = new HashSet<>();
    private final Map<String, Instant> latest = new HashMap<>();
    private final Map<String, SecurityIncident> blocked = new LinkedHashMap<>();
    private final Map<String, SecurityIncident> approvals = new LinkedHashMap<>();
    private final List<String> recoveries = new ArrayList<>();
    private String previousHash = GENESIS;
    /** Size of the journal as this store last verified or wrote it. */
    private long journalSize;
    private boolean closed;

    /** A journal that stopped verifying: its verified records, why the next one failed, and the denials after it. */
    private record Damage(int verified, String problem, List<SecurityIncident> denials) { }

    /** An incident to journal during recovery: from its report, or a denial salvaged from a damaged record. */
    private record Pending(Instant timestamp, String id, Path report, SecurityIncident salvaged) { }

    public SecurityAuditStore(Path folder) throws IOException {
        this(folder, notice -> { });
    }

    /** @param notices receives a description of every recovery from a damaged journal or report */
    public SecurityAuditStore(Path folder, Consumer<String> notices) throws IOException {
        this.folder = folder == null ? null : folder.toAbsolutePath().normalize();
        this.notices = Objects.requireNonNull(notices, "notices");
        if (folder == null) { lockChannel = null; lock = null; return; }
        safe(this.folder);
        Files.createDirectories(this.folder);
        safe(this.folder);
        Path lockPath = path("audit.lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try { acquired = shielded(lockChannel::tryLock); }
        catch (IOException | RuntimeException failure) { lockChannel.close(); throw failure; }
        lock = acquired;
        if (lock == null) { lockChannel.close(); throw new IOException("Security audit directory is already in use"); }
        try { shielded(() -> { open(); return null; }); }
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

    /**
     * Runs file work on a new thread and waits for it without being interruptible. Interrupting a
     * thread closes any {@link FileChannel} it is using, in the middle of a write: a server stopped
     * while a background review was committing its result left an empty report, and the next start
     * refused to activate anything. Nothing interrupts this thread; the caller keeps its interrupt
     * status for its own work.
     */
    static <T> T shielded(Callable<T> work) throws IOException {
        FutureTask<T> task = new FutureTask<>(work);
        Thread thread = new Thread(task, "TachyonSecurity-Storage");
        thread.setDaemon(true);
        thread.start();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IOException("Security storage failed", cause);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public synchronized void append(SecurityIncident incident, SourceFile source) throws IOException {
        if (closed) throw new IOException("Security audit closed");
        if (ids.contains(incident.id())) throw new IOException("Duplicate security incident");
        if (source != null && (!source.hash().equals(incident.sha256()) || !source.path().equals(incident.file())))
            throw new IOException("Incident source snapshot mismatch");
        String json = SecurityJson.write(incident.toJson());
        if (json.length() > 4_000_000) throw new IOException("Security audit record exceeds bounded record size");
        if (folder != null) shielded(() -> { persist(incident, source, json); return null; });
        apply(incident, true);
    }

    /** Writes the snapshot and the report, then chains the record into the journal. */
    private void persist(SecurityIncident incident, SourceFile source, String json) throws IOException {
        long size = journalBytes();
        // Another copy restored or uploaded over the journal, or a record whose failed write still
        // reached the disk: the journal is verified again before it grows.
        if (size != journalSize) reopen("security/" + JOURNAL + " changed while TachyonScript was running ("
                + journalSize + " bytes expected, " + size + " found); it was verified again before adding to it.");
        if (source != null && incident.decision().deniesExecution()) {
            writeNew(path(incident.id() + ".source.tys"), source.content());
        }
        writeNew(path(incident.id() + REPORT), json);
        String hash = ScriptIdentity.hash(previousHash + json);
        appendJournal(SecurityJson.write(Map.of("previousHash", previousHash, "hash", hash, "incident", incident.toJson())) + "\n");
        previousHash = hash;
    }

    /** Verifies the journal and journals every report it lacks. Damage is recovered, never fatal. */
    private void open() throws IOException {
        Path journal = path(JOURNAL);
        // Left by a recovery that was cut short; the damaged journal it came from is still in place.
        Files.deleteIfExists(path(REBUILD));
        Damage damage = load(journal);
        String archived = damage == null ? "" : rebuild(journal, damage);
        journalSize = journalBytes();
        int[] recovered = recoverUnjournaledReports(damage == null ? List.of() : damage.denials());
        if (damage != null) {
            notice("Security journal damaged: " + damage.problem() + ". The damaged journal is kept as security/" + archived
                    + "; the journal was rebuilt from " + damage.verified() + " verified record(s) and " + recovered[0]
                    + " write-ahead report(s)" + (recovered[1] == 0 ? "" : ", plus " + recovered[1]
                    + " quarantine(s)/disable(s) found only in the damaged part") + ". Quarantined and disabled scripts stay blocked;"
                    + " review them with /tys security incidents.");
        }
    }

    /** Forgets the in-memory state and opens the journal again as at startup. */
    private void reopen(String reason) throws IOException {
        incidents.clear(); ids.clear(); fingerprints.clear(); latest.clear(); blocked.clear(); approvals.clear();
        previousHash = GENESIS;
        notice(reason);
        open();
    }

    /** Applies the journal's records up to the first that does not verify, and describes that damage. */
    private Damage load(Path journal) throws IOException {
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return null;
        int verified = 0;
        String problem = null;
        List<SecurityIncident> denials = new ArrayList<>();
        try (BufferedReader reader = reader(journal)) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (problem == null) {
                    problem = verify(line, verified + 1);
                    if (problem == null) { verified++; continue; }
                }
                SecurityIncident denial = denial(line);
                if (denial != null) denials.add(denial);
            }
        }
        return problem == null ? null : new Damage(verified, problem, denials);
    }

    /** Checks one record against the chain and applies it; returns why it does not verify, or {@code null}. */
    private String verify(String line, int number) {
        String record = "record " + number;
        try {
            if (line.length() > MAX_RECORD) return record + " is larger than any record TachyonScript writes";
            var value = SecurityJson.object(SecurityJson.parse(line));
            var event = SecurityJson.object(value.get("incident"));
            Object state = value.get("stateApplied");
            if (value.containsKey("stateApplied") && !(state instanceof Boolean)) return record + " cannot be read";
            boolean applyState = !Boolean.FALSE.equals(state);
            String hash = ScriptIdentity.hash(previousHash + SecurityJson.write(event)
                    + (state == null ? "" : "|stateApplied=" + applyState));
            if (!previousHash.equals(SecurityJson.string(value, "previousHash")))
                return record + " does not follow the record before it (a record was removed or reordered, or another copy of the journal was written)";
            if (!hash.equals(SecurityJson.string(value, "hash"))) return record + " was changed after it was written";
            SecurityIncident incident = SecurityIncident.read(event);
            if (ids.contains(incident.id())) return record + " repeats incident " + incident.id();
            apply(incident, applyState);
            previousHash = hash;
            return null;
        } catch (RuntimeException e) {
            return record + " cannot be read (cut off by a crash, or damaged)";
        }
    }

    /** The quarantine or disable in a record that does not verify: damage may only add blocks. */
    private static SecurityIncident denial(String line) {
        try {
            if (line.length() > MAX_RECORD) return null;
            SecurityIncident incident = SecurityIncident.read(SecurityJson.object(
                    SecurityJson.object(SecurityJson.parse(line)).get("incident")));
            return incident.decision().deniesExecution() ? incident : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Keeps the damaged journal under a new name and replaces it with its verified records; returns the new name. */
    private String rebuild(Path journal, Damage damage) throws IOException {
        Path rebuilt = path(REBUILD);
        try (BufferedReader reader = reader(journal);
             FileChannel out = FileChannel.open(rebuilt, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            for (int record = 0; record < damage.verified(); record++) write(out, reader.readLine() + "\n");
            out.force(true);
        }
        String name = "audit-damaged-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        Path archived = path(name + ".jsonl");
        for (int copy = 2; Files.exists(archived, LinkOption.NOFOLLOW_LINKS); copy++) archived = path(name + "-" + copy + ".jsonl");
        Files.copy(journal, archived, LinkOption.NOFOLLOW_LINKS);
        try (FileChannel copy = FileChannel.open(archived, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { copy.force(true); }
        // Until this replacement the damaged journal stays in place, so a crash only repeats the recovery.
        try {
            Files.move(rebuilt, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(rebuilt, journal, StandardCopyOption.REPLACE_EXISTING);
        }
        return archived.getFileName().toString();
    }

    /**
     * Journals, in time order, every report the journal lacks (a crash between a report and its
     * record, or every report after a damaged record) and the salvaged denials that have no
     * report. Returns how many records came from reports and how many were salvaged.
     */
    private int[] recoverUnjournaledReports(List<SecurityIncident> denials) throws IOException {
        // Immutable reports are write-ahead records for local revocation, never a historical webhook replay source.
        List<Pending> pending = new ArrayList<>();
        Set<String> queued = new HashSet<>();
        List<String> setAside = new ArrayList<>();
        try (var entries = Files.list(folder)) {
            for (Path file : entries.filter(p -> p.getFileName().toString().endsWith(REPORT)).sorted().toList()) {
                safe(file);
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - REPORT.length());
                if (ids.contains(id)) continue;
                SecurityIncident incident = report(file, id);
                if (incident == null) { setAside.add(setAside(file)); continue; }
                pending.add(new Pending(incident.timestamp(), id, file, null));
                queued.add(id);
            }
        }
        int salvaged = 0;
        for (SecurityIncident denial : denials) {
            if (ids.contains(denial.id()) || !queued.add(denial.id())) continue;
            pending.add(new Pending(denial.timestamp(), denial.id(), null, denial));
            salvaged++;
        }
        // Reports are named by date and a random ID: replay them in the order they happened.
        pending.sort(Comparator.comparing(Pending::timestamp).thenComparing(Pending::id));
        int reported = 0;
        for (Pending entry : pending) {
            SecurityIncident incident = entry.salvaged() != null ? entry.salvaged() : report(entry.report(), entry.id());
            if (incident == null) continue;
            journalRecovered(incident);
            if (entry.salvaged() == null) reported++;
        }
        if (!setAside.isEmpty()) {
            notice("Set aside " + setAside.size() + " security report(s) that cannot be read (a write cut off by a crash"
                    + " or a stop); they were not used: " + String.join(", ", setAside) + ".");
        }
        return new int[] {reported, salvaged};
    }

    /** The incident in a report file, or {@code null} when it cannot be read or is not the incident its name says. */
    private static SecurityIncident report(Path file, String id) {
        try {
            if (!id.matches("TS-SEC-[0-9]{8}-[0-9a-f]{32}") || Files.size(file) > MAX_RECORD) return null;
            SecurityIncident incident = SecurityIncident.read(SecurityJson.object(
                    SecurityJson.parse(Files.readString(file, StandardCharsets.UTF_8))));
            return incident.id().equals(id) ? incident : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Renames an unreadable report out of the way, keeping it; returns the name to report. */
    private String setAside(Path file) {
        String name = file.getFileName().toString();
        try {
            Path target = path(name + ".unreadable");
            for (int copy = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); copy++) target = path(name + ".unreadable-" + copy);
            Files.move(file, target);
            return target.getFileName().toString();
        } catch (IOException | RuntimeException e) {
            return name + " (left in place)";
        }
    }

    private void journalRecovered(SecurityIncident incident) throws IOException {
        String json = SecurityJson.write(incident.toJson());
        // A recovered older approval/restore must never undo a newer committed disable.
        Instant newest = latest.get(incident.scriptId());
        boolean applyState = incident.decision().deniesExecution() || newest == null || newest.isBefore(incident.timestamp());
        String hash = ScriptIdentity.hash(previousHash + json + "|stateApplied=" + applyState);
        appendJournal(SecurityJson.write(Map.of("previousHash", previousHash, "hash", hash,
                "incident", incident.toJson(), "stateApplied", applyState)) + "\n");
        previousHash = hash; apply(incident, applyState);
    }

    private void apply(SecurityIncident incident, boolean applyState) {
        incidents.put(incident.id(), incident);
        if (incidents.size() > RECENT) incidents.remove(incidents.keySet().iterator().next());
        ids.add(incident.id());
        fingerprints.add(fingerprint(incident.scriptId(), incident.sha256(), incident.action(), incident.findings()));
        latest.merge(incident.scriptId(), incident.timestamp(), (a, b) -> a.isAfter(b) ? a : b);
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
    /** An incident by ID, read back from its report file when it is no longer kept in memory. */
    public synchronized SecurityIncident incident(String id) {
        SecurityIncident found = incidents.get(id);
        if (found != null || !ids.contains(id)) return found;
        for (SecurityIncident state : blocked.values()) if (state.id().equals(id)) return state;
        for (SecurityIncident state : approvals.values()) if (state.id().equals(id)) return state;
        if (folder == null) return null;
        try {
            Path file = path(id + REPORT);
            if (Files.size(file) > MAX_RECORD) return null;
            SecurityIncident read = SecurityIncident.read(SecurityJson.object(SecurityJson.parse(Files.readString(file, StandardCharsets.UTF_8))));
            return read.id().equals(id) ? read : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
    /** The most recent incidents (at most {@value #RECENT}), oldest first. */
    public synchronized List<SecurityIncident> incidents() { return new ArrayList<>(incidents.values()); }
    /** Every incident ever recorded, including those no longer kept in memory. */
    public synchronized int incidentCount() { return ids.size(); }
    /** Whether an incident with exactly these findings was already recorded for this revision and action. */
    public synchronized boolean recorded(String scriptId, String sha256, String action, List<SecurityFinding> findings) {
        return fingerprints.contains(fingerprint(scriptId, sha256, action, findings));
    }
    private static String fingerprint(String scriptId, String sha256, String action, List<SecurityFinding> findings) {
        return scriptId + "|" + sha256 + "|" + action + "|"
                + ScriptIdentity.hash(SecurityJson.write(findings.stream().map(SecurityFinding::toJson).toList()));
    }
    /** What recoveries from a damaged journal or report did since this store was opened, oldest first. */
    public synchronized List<String> recoveries() { return List.copyOf(recoveries); }

    public synchronized SourceFile snapshot(String incidentId) throws IOException {
        SecurityIncident incident = incident(incidentId);
        if (incident == null || !incident.decision().deniesExecution() || folder == null) throw new IOException("No stored quarantine source");
        Path file = path(incidentId + ".source.tys");
        if (Files.size(file) > 16_777_216) throw new IOException("Stored source exceeds limit");
        SourceFile source = new SourceFile(incident.file(), Files.readString(file, StandardCharsets.UTF_8));
        if (!source.hash().equals(incident.sha256())) throw new IOException("Quarantine source hash mismatch");
        return source;
    }

    private void notice(String text) {
        recoveries.add(text);
        if (recoveries.size() > NOTICES) recoveries.removeFirst();
        try { notices.accept(text); } catch (RuntimeException ignored) { /* Logging must not stop a recovery. */ }
    }

    private long journalBytes() throws IOException {
        Path journal = path(JOURNAL);
        return Files.exists(journal, LinkOption.NOFOLLOW_LINKS) ? Files.size(journal) : 0;
    }

    /** Reads without failing on bytes that are not UTF-8: a record containing them simply does not verify. */
    private static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)));
    }

    /** Appends one record; a failed append is cut off again, so the journal never holds a record the chain lacks. */
    private void appendJournal(String line) throws IOException {
        try (FileChannel channel = FileChannel.open(path(JOURNAL), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            try {
                write(channel, line);
                channel.force(true);
            } catch (IOException failure) {
                try {
                    channel.truncate(journalSize);
                    channel.force(true);
                } catch (IOException ignored) {
                    // The next append finds the journal longer than expected and verifies it again.
                }
                throw failure;
            }
            journalSize = channel.size();
        }
    }

    /** Creates a file and syncs it; a write that fails removes the file instead of leaving it partial. */
    private static void writeNew(Path file, String text) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean written = false;
        try (channel) {
            write(channel, text);
            channel.force(true);
            written = true;
        } finally {
            if (!written) {
                try { Files.deleteIfExists(file); }
                catch (IOException ignored) { /* An unreadable report is set aside at the next start. */ }
            }
        }
    }
    static void appendFile(Path file, String text) throws IOException {
        safe(file);
        shielded(() -> {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                write(channel, text); channel.force(true);
            }
            return null;
        });
    }
    private static void write(FileChannel channel, String text) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
        while (bytes.hasRemaining()) channel.write(bytes);
    }
    public Path folder() { return folder; }
    @Override public synchronized void close() throws IOException {
        closed = true;
        if (lock != null && lock.isValid()) lock.release();
        if (lockChannel != null) lockChannel.close();
    }
}
