package dev.tachyonscript.security;

import dev.tachyonscript.language.source.SourceFile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityDiscordCardTest {
    private static final SourceFile SOURCE = new SourceFile("shop.tys", "\n\nevent player.chat {\n    server.dispatch(message)\n}\n");
    private static SecurityIncident incident(List<SecurityFinding> findings) {
        return new SecurityIncident(SecurityAuditStore.incidentId(), Instant.parse("2026-10-01T16:00:00Z"),
                ScriptIdentity.of(SOURCE.path()), SOURCE.path(), SOURCE.hash(), SecurityDecision.QUARANTINE,
                "AUTO_QUARANTINE", SecuritySeverity.CRITICAL, "Blocked before execution", findings,
                List.of("market.tys"), "security-policy", "");
    }
    private static SecurityFinding finding() {
        return SecurityAnalyzerTest.analyze(SOURCE).findings().stream()
                .filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).findFirst().orElseThrow();
    }
    @Test void ordinaryQuarantineUsesOneColoredCardWithAuthoritativeEvidence() {
        var incident = incident(List.of(finding()));
        var payloads = SecurityMessages.discord(incident, true);
        assertEquals(1, payloads.size(), "An ordinary finding should fit in one readable card");
        var payload = SecurityJson.object(SecurityJson.parse(payloads.getFirst()));
        var embed = embed(payload);
        assertEquals("", SecurityJson.string(payload, "content"));
        assertEquals(0xED4245, SecurityJson.integer(embed, "color"));
        assertTrue(SecurityJson.string(embed, "title").contains("Đã cách ly"));
        assertEquals(incident.timestamp().toString(), SecurityJson.string(embed, "timestamp"));
        var fields = fields(embed);
        for (int index = 0; index < 6; index++) assertEquals(true, fields.get(index).get("inline"));
        assertEquals("`shop.tys:4:5-4:29`", value(fields, "Vị trí"));
        assertTrue(value(fields, "Handler / hàm").contains("event player.chat"));
        assertTrue(value(fields, "Nguồn dữ liệu").contains("message @ shop.tys:4:21"));
        assertTrue(value(fields, "Đích thực thi").contains("server.dispatch @ shop.tys:4:5"));
        assertTrue(value(fields, "Đoạn mã").contains("4 |     server.dispatch(message)"));
        assertTrue(value(fields, "Luồng dữ liệu").contains("[SOURCE]"));
        assertTrue(value(fields, "Luồng dữ liệu").contains("[SINK]"));
        assertTrue(value(fields, "Phụ thuộc ảnh hưởng").contains("market.tys"));
        assertTrue(value(fields, "SHA-256").contains(SOURCE.hash()));
        assertTrue(SecurityJson.string(SecurityJson.object(embed.get("footer")), "text").contains(incident.id()));
        assertTrue(value(fields, "Xem đầy đủ").contains("/tys security inspect " + incident.id()));
        assertValid(payload);
    }
    @Test void largeUnicodeFlowsAndSnippetsArePaginatedWithoutLosingTheirTailOrLocation() {
        var base = finding();
        List<TaintStep> flow = new ArrayList<>();
        for (int index = 0; index < 130; index++) flow.add(new TaintStep(base.nodeId(), base.location(),
                "step-" + index + " 😀 " + "x".repeat(90), index == 0 ? "SOURCE" : index == 129 ? "SINK" : "READ"));
        var large = copy(base, "server.dispatch(message)\n" + "Unicode 😀 ``` @everyone\n".repeat(200) + "END-SNIPPET",
                "Unicode 😀 **reason** ".repeat(150) + "END-REASON", flow);
        var incident = incident(List.of(large));
        var payloads = SecurityMessages.discord(incident, true);
        assertTrue(payloads.size() > 1);
        String combined = String.join("", payloads);
        assertTrue(combined.contains("END-SNIPPET")); assertTrue(combined.contains("END-REASON"));
        assertTrue(combined.contains("step-0")); assertTrue(combined.contains("step-129"));
        assertTrue(combined.contains(SOURCE.hash()));
        for (String json : payloads) {
            var payload = SecurityJson.object(SecurityJson.parse(json));
            assertValid(payload);
            assertEquals("`shop.tys:4:5-4:29`", value(fields(embed(payload)), "Vị trí"));
            assertTrue(SecurityJson.string(SecurityJson.object(embed(payload).get("footer")), "text").contains(incident.id()));
        }
        assertFalse(combined.contains("Unicode 😀 ``` @everyone"), "Untrusted source must not close a code fence");
    }
    @Test void snippetPreferenceDoesNotHideLocationsOrTaintEvidence() {
        var embed = embed(SecurityJson.object(SecurityJson.parse(SecurityMessages.discord(incident(List.of(finding())), false).getFirst())));
        var fields = fields(embed);
        assertTrue(fields.stream().noneMatch(field -> SecurityJson.string(field, "name").startsWith("Đoạn mã")));
        assertTrue(value(fields, "Vị trí").contains("shop.tys:4:5"));
        assertTrue(value(fields, "Luồng dữ liệu").contains("[SINK]"));
    }
    @Test void manualDisableWithoutFindingsReportsUnknownInsteadOfInventingALine() {
        var original = incident(List.of());
        var manual = new SecurityIncident(original.id(), original.timestamp(), original.scriptId(), original.file(), original.sha256(),
                SecurityDecision.DISABLE, "DISABLE", SecuritySeverity.HIGH, "Administrator requested disable", List.of(), List.of(), "admin", "");
        var payload = SecurityJson.object(SecurityJson.parse(SecurityMessages.discord(manual, true).getFirst()));
        assertTrue(value(fields(embed(payload)), "Vị trí").contains("UNKNOWN — Precise source location unavailable."));
        assertFalse(SecurityJson.write(payload).contains("shop.tys:0"));
        assertValid(payload);
    }
    @Test void multipleFindingsKeepTheirOwnCompilerLocation() {
        var source = new SourceFile("shop.tys", "event player.chat {\n    server.dispatch(message)\n    server.dispatch(\"say \" + message)\n}\n");
        var findings = SecurityAnalyzerTest.analyze(source).findings().stream()
                .filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).toList();
        var incident = new SecurityIncident(SecurityAuditStore.incidentId(), Instant.now(), ScriptIdentity.of(source.path()), source.path(),
                source.hash(), SecurityDecision.QUARANTINE, "AUTO_QUARANTINE", SecuritySeverity.CRITICAL, "Blocked", findings, List.of(), "security-policy", "");
        var payloads = SecurityMessages.discord(incident, true);
        assertEquals(2, payloads.size());
        for (SecurityFinding finding : findings) assertTrue(payloads.stream().anyMatch(json -> json.contains(finding.location().display())));
    }
    private static SecurityFinding copy(SecurityFinding base, String snippet, String reason, List<TaintStep> flow) {
        return new SecurityFinding(base.id(), base.ruleId(), base.category(), base.severity(), base.confidence(), base.scriptId(), base.sha256(),
                base.nodeId(), base.location(), base.functionName(), base.handlerName(), snippet, base.source(), base.sink(), base.capability(),
                reason, base.evidence(), base.recommendation(), base.origin(), base.concreteEvidence(), flow);
    }
    private static Map<String, Object> embed(Map<String, Object> payload) {
        return SecurityJson.object(SecurityJson.array(payload.get("embeds")).getFirst());
    }
    private static List<Map<String, Object>> fields(Map<String, Object> embed) {
        return SecurityJson.array(embed.get("fields")).stream().map(SecurityJson::object).toList();
    }
    private static String value(List<Map<String, Object>> fields, String name) {
        return fields.stream().filter(field -> SecurityJson.string(field, "name").equals(name))
                .map(field -> SecurityJson.string(field, "value")).findFirst().orElseThrow();
    }
    static void assertValid(Map<String, Object> payload) {
        assertEquals(List.of(), SecurityJson.array(SecurityJson.object(payload.get("allowed_mentions")).get("parse")));
        assertTrue(SecurityJson.string(payload, "content").length() <= 2000);
        var embed = embed(payload);
        int count = SecurityJson.string(embed, "title").length() + SecurityJson.string(embed, "description").length()
                + SecurityJson.string(SecurityJson.object(embed.get("footer")), "text").length();
        assertTrue(SecurityJson.string(embed, "title").length() <= 256);
        assertTrue(SecurityJson.string(embed, "description").length() <= 4096);
        assertTrue(SecurityJson.string(SecurityJson.object(embed.get("footer")), "text").length() <= 2048);
        assertTrue(fields(embed).size() <= 25);
        for (var field : fields(embed)) {
            String name = SecurityJson.string(field, "name"), value = SecurityJson.string(field, "value");
            assertTrue(name.length() <= 256); assertTrue(value.length() <= 1024); assertFalse(value.isBlank());
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (Character.isHighSurrogate(character)) assertTrue(++index < value.length() && Character.isLowSurrogate(value.charAt(index)));
                else assertFalse(Character.isLowSurrogate(character));
            }
            count += name.length() + value.length();
        }
        assertTrue(count <= 6000, "Discord message embed limit exceeded: " + count);
    }
}
