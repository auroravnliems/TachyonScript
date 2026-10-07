package dev.tachyonscript.security;

import dev.tachyonscript.language.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A damaged journal or report never stops the store from opening: the damaged journal is kept,
 * the journal is rebuilt from the write-ahead reports, and quarantines stay in force.
 */
class SecurityAuditRecoveryTest {
    @TempDir Path folder;

    private static final SourceFile SHOP = new SourceFile("shop.tys", "event player.chat {\n    server.dispatch(message)\n}\n");
    private static final SourceFile MARKET = new SourceFile("market.tys", "on load { log(\"market\") }\n");

    private static SecurityIncident incident(SourceFile source, SecurityDecision decision, String action, Instant at) {
        return incident(SecurityAuditStore.incidentId(), source, decision, action, at);
    }
    private static SecurityIncident incident(String id, SourceFile source, SecurityDecision decision, String action, Instant at) {
        return new SecurityIncident(id, at, ScriptIdentity.of(source.path()), source.path(), source.hash(), decision, action,
                decision.deniesExecution() ? SecuritySeverity.CRITICAL : SecuritySeverity.INFO, action + " for the test",
                List.of(), List.of(), "test", "");
    }
    private Path journal() { return folder.resolve("audit.jsonl"); }
    private List<String> lines() throws IOException { return Files.readAllLines(journal(), StandardCharsets.UTF_8); }
    private void write(List<String> lines) throws IOException {
        Files.writeString(journal(), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
    private List<Path> damagedCopies() throws IOException {
        try (var files = Files.list(folder)) {
            return files.filter(path -> path.getFileName().toString().startsWith("audit-damaged-")).toList();
        }
    }
    /** Opens the store again and checks that the journal now verifies without any recovery. */
    private void assertClean() throws IOException {
        try (SecurityAuditStore store = new SecurityAuditStore(folder)) {
            assertEquals(List.of(), store.recoveries());
        }
    }

    /** A quarantine, a warning on another script and a later warning on the first. */
    private List<SecurityIncident> history() throws IOException {
        Instant start = Instant.now().minusSeconds(60);
        List<SecurityIncident> written = List.of(
                incident(SHOP, SecurityDecision.QUARANTINE, "AUTO_QUARANTINE", start),
                incident(MARKET, SecurityDecision.WARN, "WARN", start.plusSeconds(1)),
                incident(SHOP, SecurityDecision.WARN, "API_FAILURE", start.plusSeconds(2)));
        try (SecurityAuditStore store = new SecurityAuditStore(folder)) {
            for (SecurityIncident incident : written) store.append(incident, incident.decision().deniesExecution() ? SHOP : null);
        }
        return written;
    }

    @Test void anEditedRecordIsKeptAsEvidenceAndTheJournalRebuiltFromTheReports() throws Exception {
        List<SecurityIncident> written = history();
        String original = Files.readString(journal());
        Files.writeString(journal(), original.replace("AUTO_QUARANTINE for the test", "nothing to see"));
        List<String> logged = new ArrayList<>();
        try (SecurityAuditStore store = new SecurityAuditStore(folder, logged::add)) {
            assertEquals(1, logged.size(), logged::toString);
            assertTrue(logged.getFirst().contains("record 1 was changed after it was written"), logged::toString);
            assertEquals(logged, store.recoveries());
            assertEquals(3, store.incidentCount());
            SecurityIncident quarantine = store.blocked().get(ScriptIdentity.of(SHOP.path()));
            assertEquals(written.getFirst(), quarantine, "the report, not the edited record, is restored");
            assertEquals(SHOP.content(), store.snapshot(quarantine.id()).content());
        }
        assertEquals(1, damagedCopies().size());
        assertEquals(original.replace("AUTO_QUARANTINE for the test", "nothing to see"), Files.readString(damagedCopies().getFirst()));
        assertClean();
    }

    @Test void aRemovedRecordBreaksTheLinkAndComesBackFromItsReport() throws Exception {
        List<SecurityIncident> written = history();
        List<String> lines = new ArrayList<>(lines());
        lines.remove(1);
        write(lines);
        List<String> logged = new ArrayList<>();
        try (SecurityAuditStore store = new SecurityAuditStore(folder, logged::add)) {
            assertTrue(logged.getFirst().contains("record 2 does not follow the record before it"), logged::toString);
            assertTrue(logged.getFirst().contains("rebuilt from 1 verified record(s) and 2 write-ahead report(s)"), logged::toString);
            for (SecurityIncident incident : written) assertEquals(incident, store.incident(incident.id()));
            assertNotNull(store.blocked().get(ScriptIdentity.of(SHOP.path())));
        }
        assertClean();
    }

    @Test void aJournalReplacedWhileTheServerRunsIsVerifiedBeforeItGrows() throws Exception {
        List<String> logged = new ArrayList<>();
        Instant start = Instant.now().minusSeconds(60);
        SecurityIncident first = incident(SHOP, SecurityDecision.QUARANTINE, "AUTO_QUARANTINE", start);
        SecurityIncident second = incident(MARKET, SecurityDecision.WARN, "WARN", start.plusSeconds(1));
        SecurityIncident third = incident(MARKET, SecurityDecision.WARN, "API_FAILURE", start.plusSeconds(2));
        try (SecurityAuditStore store = new SecurityAuditStore(folder, logged::add)) {
            store.append(first, SHOP);
            String older = Files.readString(journal());
            store.append(second, null);
            // An administrator uploads or restores an older copy of the folder while the server runs.
            Files.writeString(journal(), older);
            store.append(third, null);
            assertEquals(1, logged.size(), logged::toString);
            assertTrue(logged.getFirst().contains("changed while TachyonScript was running"), logged::toString);
            assertEquals(3, store.incidentCount());
        }
        try (SecurityAuditStore restarted = new SecurityAuditStore(folder)) {
            assertEquals(List.of(), restarted.recoveries(), "no record points at a history the journal lacks");
            for (SecurityIncident incident : List.of(first, second, third)) assertEquals(incident, restarted.incident(incident.id()));
            assertNotNull(restarted.blocked().get(first.scriptId()));
        }
    }

    @Test void aRecordThatReachedTheDiskWithoutBeingAppliedIsChainedInsteadOfForked() throws Exception {
        Instant start = Instant.now().minusSeconds(60);
        SecurityIncident lost = incident(MARKET, SecurityDecision.WARN, "WARN", start.plusSeconds(1));
        try (SecurityAuditStore store = new SecurityAuditStore(folder)) {
            store.append(incident(SHOP, SecurityDecision.WARN, "WARN", start), null);
            // A write that failed after it reached the disk: report and record exist, the store never applied them.
            String head = SecurityJson.string(SecurityJson.object(SecurityJson.parse(lines().getLast())), "hash");
            String json = SecurityJson.write(lost.toJson());
            Files.writeString(folder.resolve(lost.id() + ".report.json"), json);
            Files.writeString(journal(), SecurityJson.write(Map.of("previousHash", head, "hash", ScriptIdentity.hash(head + json),
                    "incident", lost.toJson())) + "\n", java.nio.file.StandardOpenOption.APPEND);
            store.append(incident(SHOP, SecurityDecision.WARN, "API_FAILURE", start.plusSeconds(2)), null);
            assertEquals(lost, store.incident(lost.id()));
        }
        try (SecurityAuditStore restarted = new SecurityAuditStore(folder)) {
            assertEquals(List.of(), restarted.recoveries());
            assertEquals(3, restarted.incidentCount());
        }
    }

    @Test void anInterruptedThreadStillWritesCompleteRecordsAndKeepsItsInterrupt() throws Exception {
        SecurityIncident quarantine = incident(SHOP, SecurityDecision.QUARANTINE, "AUTO_QUARANTINE", Instant.now());
        try (SecurityAuditStore store = new SecurityAuditStore(folder)) {
            // A follow-up interrupted by the engine shutting down while it commits a background review.
            Thread.currentThread().interrupt();
            try {
                store.append(quarantine, SHOP);
                assertTrue(Thread.currentThread().isInterrupted(), "the caller's interrupt is preserved");
            } finally {
                Thread.interrupted();
            }
        }
        assertTrue(Files.size(folder.resolve(quarantine.id() + ".report.json")) > 0);
        assertEquals(SHOP.content(), Files.readString(folder.resolve(quarantine.id() + ".source.tys")));
        try (SecurityAuditStore restarted = new SecurityAuditStore(folder)) {
            assertEquals(List.of(), restarted.recoveries());
            assertEquals(quarantine, restarted.blocked().get(quarantine.scriptId()));
        }
    }

    @Test void anUnreadableReportIsSetAsideInsteadOfBlockingTheStart() throws Exception {
        history();
        String empty = SecurityAuditStore.incidentId();
        String cut = SecurityAuditStore.incidentId();
        Files.writeString(folder.resolve(empty + ".report.json"), "");
        Files.writeString(folder.resolve(cut + ".report.json"), "{\"schemaVersion\":1,\"incidentId\":\"" + cut);
        Files.writeString(folder.resolve("notes.report.json"), "{}");
        List<String> logged = new ArrayList<>();
        try (SecurityAuditStore store = new SecurityAuditStore(folder, logged::add)) {
            assertEquals(3, store.incidentCount());
            assertEquals(1, logged.size(), logged::toString);
            assertTrue(logged.getFirst().startsWith("Set aside 3 security report(s)"), logged::toString);
        }
        assertTrue(Files.exists(folder.resolve(empty + ".report.json.unreadable")));
        assertTrue(Files.exists(folder.resolve(cut + ".report.json.unreadable")));
        assertFalse(Files.exists(folder.resolve(empty + ".report.json")));
        assertTrue(damagedCopies().isEmpty(), "the journal itself was fine");
        assertClean();
    }

    @Test void damageCanOnlyAddBlocks() throws Exception {
        Instant start = Instant.now().minusSeconds(60);
        SecurityIncident warning = incident(SHOP, SecurityDecision.WARN, "WARN", start);
        SecurityIncident quarantine = incident(MARKET, SecurityDecision.QUARANTINE, "QUARANTINE", start.plusSeconds(1));
        SecurityIncident approval = incident(SHOP, SecurityDecision.WARN, "APPROVE", start.plusSeconds(2));
        try (SecurityAuditStore store = new SecurityAuditStore(folder)) {
            store.append(warning, null);
            store.append(quarantine, MARKET);
            store.append(approval, null);
        }
        // Records 2 and 3 are edited and their reports are gone: only the quarantine may be kept.
        Files.delete(folder.resolve(quarantine.id() + ".report.json"));
        Files.delete(folder.resolve(approval.id() + ".report.json"));
        List<String> lines = new ArrayList<>(lines());
        lines.set(1, lines.get(1).replace("QUARANTINE for the test", "edited"));
        write(lines);
        List<String> logged = new ArrayList<>();
        try (SecurityAuditStore store = new SecurityAuditStore(folder, logged::add)) {
            assertTrue(logged.getFirst().contains("plus 1 quarantine(s)/disable(s) found only in the damaged part"), logged::toString);
            assertNotNull(store.blocked().get(quarantine.scriptId()), "a quarantine is never lost to damage");
            assertNull(store.incident(approval.id()), "an approval without its report is not trusted");
            assertFalse(store.approved(approval.scriptId(), approval.sha256()));
        }
        assertClean();
    }

    @Test void recoveredReportsReplayInTheOrderTheyHappened() throws Exception {
        Instant start = Instant.now().minusSeconds(60);
        // The restore's ID sorts before the quarantine's: replaying by file name would block the script again.
        SecurityIncident quarantine = incident("TS-SEC-20261007-" + "f".repeat(32), SHOP, SecurityDecision.QUARANTINE, "QUARANTINE", start);
        SecurityIncident restore = incident("TS-SEC-20261007-" + "0".repeat(32), SHOP, SecurityDecision.WARN, "RESTORE", start.plusSeconds(5));
        for (SecurityIncident incident : List.of(quarantine, restore)) {
            Files.writeString(folder.resolve(incident.id() + ".report.json"), SecurityJson.write(incident.toJson()));
        }
        try (SecurityAuditStore store = new SecurityAuditStore(folder)) {
            assertEquals(Map.of(), store.blocked());
            assertEquals(2, store.incidentCount());
        }
        assertClean();
    }

    @Test void aRecoveryCutShortIsRepeatedFromTheDamagedJournal() throws Exception {
        history();
        List<String> lines = new ArrayList<>(lines());
        lines.set(2, lines.get(2).substring(0, 40));
        write(lines);
        Files.writeString(folder.resolve("audit.jsonl.rebuild"), "left by a crash during recovery");
        List<String> logged = new ArrayList<>();
        try (SecurityAuditStore store = new SecurityAuditStore(folder, logged::add)) {
            assertTrue(logged.getFirst().contains("record 3 cannot be read"), logged::toString);
            assertEquals(3, store.incidentCount());
        }
        assertFalse(Files.exists(folder.resolve("audit.jsonl.rebuild")));
        assertClean();
    }

    @Test void aClosedStoreRefusesToWrite() throws Exception {
        SecurityAuditStore store = new SecurityAuditStore(folder);
        store.close();
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> store.append(incident(SHOP, SecurityDecision.WARN, "WARN", Instant.now()), null));
        assertFalse(Files.exists(journal()));
    }
}
