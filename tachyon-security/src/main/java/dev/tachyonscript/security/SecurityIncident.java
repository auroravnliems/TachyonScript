package dev.tachyonscript.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SecurityIncident(String id, Instant timestamp, String scriptId, String file, String sha256,
                               SecurityDecision decision, String action, SecuritySeverity severity,
                               String summary, List<SecurityFinding> findings, List<String> dependencies,
                               String actor, String relatedIncident, String approvalContext) {
    public SecurityIncident {
        if (!id.matches("TS-SEC-[0-9]{8}-[0-9a-f]{32}") || !scriptId.matches("ts-[0-9a-f]{32}")
                || !sha256.matches("[0-9a-f]{64}") || !approvalContext.isEmpty() && !approvalContext.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid incident identity");
        findings = List.copyOf(findings); dependencies = List.copyOf(dependencies);
        for (SecurityFinding finding : findings)
            if (!finding.scriptId().equals(scriptId) || !finding.sha256().equals(sha256))
                throw new IllegalArgumentException("Finding belongs to another source revision");
    }
    public SecurityIncident(String id, Instant timestamp, String scriptId, String file, String sha256,
                            SecurityDecision decision, String action, SecuritySeverity severity, String summary,
                            List<SecurityFinding> findings, List<String> dependencies, String actor, String relatedIncident) {
        this(id, timestamp, scriptId, file, sha256, decision, action, severity, summary, findings, dependencies, actor, relatedIncident, "");
    }
    public Map<String, Object> toJson() {
        return Map.ofEntries(Map.entry("schemaVersion", 1), Map.entry("incidentId", id),
                Map.entry("timestamp", timestamp.toString()), Map.entry("scriptId", scriptId), Map.entry("file", file),
                Map.entry("sha256", sha256), Map.entry("decision", decision.name()), Map.entry("action", action),
                Map.entry("severity", severity.name()), Map.entry("summary", summary),
                Map.entry("findings", findings.stream().map(SecurityFinding::toJson).toList()),
                Map.entry("affectedDependencies", dependencies), Map.entry("actor", actor), Map.entry("relatedIncident", relatedIncident),
                Map.entry("approvalContext", approvalContext));
    }
    static SecurityIncident read(Map<String, Object> value) {
        return new SecurityIncident(SecurityJson.string(value, "incidentId"), Instant.parse(SecurityJson.string(value, "timestamp")),
                SecurityJson.string(value, "scriptId"), SecurityJson.string(value, "file"), SecurityJson.string(value, "sha256"),
                SecurityDecision.valueOf(SecurityJson.string(value, "decision")), SecurityJson.string(value, "action"),
                SecuritySeverity.valueOf(SecurityJson.string(value, "severity")), SecurityJson.string(value, "summary"),
                SecurityJson.array(value.get("findings")).stream().map(f -> SecurityFinding.read(SecurityJson.object(f))).toList(),
                SecurityJson.array(value.get("affectedDependencies")).stream().map(v -> (String) v).toList(),
                SecurityJson.string(value, "actor"), SecurityJson.string(value, "relatedIncident"), SecurityJson.optionalString(value, "approvalContext"));
    }
}
