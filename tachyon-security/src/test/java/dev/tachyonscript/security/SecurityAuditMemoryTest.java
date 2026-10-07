package dev.tachyonscript.security;

import dev.tachyonscript.language.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit only grows; memory holds a bounded part of it and reads the rest back on demand. */
class SecurityAuditMemoryTest {

    @TempDir
    Path directory;

    private static SecurityIncident warning(SourceFile source, int index) {
        return new SecurityIncident(SecurityAuditStore.incidentId(), Instant.now(), ScriptIdentity.of(source.path()), source.path(),
                source.hash(), SecurityDecision.WARN, "WARN", SecuritySeverity.MEDIUM, "Advisory " + index, List.of(), List.of(),
                "test", "");
    }

    @Test
    void oldIncidentsLeaveMemoryButStayReadableCountedAndDeduplicated() throws Exception {
        SourceFile source = new SourceFile("busy.tys", "on load { log(\"x\") }\n");
        int total = SecurityAuditStore.RECENT + 40;
        String first;
        SecurityIncident blocking;
        try (SecurityAuditStore store = new SecurityAuditStore(directory)) {
            blocking = new SecurityIncident(SecurityAuditStore.incidentId(), Instant.now(), ScriptIdentity.of(source.path()),
                    source.path(), source.hash(), SecurityDecision.QUARANTINE, "QUARANTINE", SecuritySeverity.HIGH,
                    "Blocked early", List.of(), List.of(), "test", "");
            store.append(blocking, source);
            first = blocking.id();
            for (int i = 0; i < total; i++) store.append(warning(source, i), null);
            assertEquals(SecurityAuditStore.RECENT, store.incidents().size(), "memory keeps only the recent incidents");
            assertEquals(total + 1, store.incidentCount());
            assertNotNull(store.incident(first), "a state-deciding incident stays available");
            assertTrue(store.blocked().containsKey(ScriptIdentity.of(source.path())));
            assertTrue(store.recorded(ScriptIdentity.of(source.path()), source.hash(), "QUARANTINE", List.of()));
        }
        try (SecurityAuditStore reopened = new SecurityAuditStore(directory)) {
            assertEquals(total + 1, reopened.incidentCount());
            assertEquals(SecurityAuditStore.RECENT, reopened.incidents().size());
            SecurityIncident early = reopened.incident(first);
            assertNotNull(early, "read back from its report file");
            assertEquals("Blocked early", early.summary());
            assertTrue(reopened.blocked().containsKey(ScriptIdentity.of(source.path())), "state survives the restart");
            assertTrue(reopened.recorded(ScriptIdentity.of(source.path()), source.hash(), "WARN", List.of()));
            assertFalse(reopened.recorded(ScriptIdentity.of(source.path()), source.hash(), "APPROVE", List.of()));
            assertNull(reopened.incident("TS-SEC-20260101-" + "0".repeat(32)));
        }
    }
}
