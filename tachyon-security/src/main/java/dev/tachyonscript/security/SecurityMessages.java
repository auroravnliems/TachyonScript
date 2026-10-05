package dev.tachyonscript.security;

import java.util.ArrayList;
import java.util.List;

/** Console/inspect retain complete flows; Discord renders bounded security cards. */
public final class SecurityMessages {
    private SecurityMessages() { }
    public static String detail(SecurityIncident incident, boolean snippets) {
        StringBuilder out = new StringBuilder("[TachyonSecurity/" + incident.severity() + "]\n");
        out.append("Incident: ").append(incident.id()).append("\nAction: ").append(incident.action())
                .append("\nDecision: ").append(incident.decision()).append("\nScript: ").append(incident.file())
                .append("\nTimestamp: ").append(incident.timestamp()).append("\nSHA-256: ").append(incident.sha256())
                .append("\nActor: ").append(incident.actor()).append("\nAffected dependencies: ")
                .append(String.join(", ", incident.dependencies())).append("\nSummary: ").append(incident.summary()).append('\n');
        if (!incident.approvalContext().isEmpty()) out.append("Approval context SHA-256: ").append(incident.approvalContext()).append('\n');
        if (incident.findings().isEmpty()) out.append("Location: UNKNOWN — Precise source location unavailable. No vulnerability was fabricated.\n");
        for (SecurityFinding finding : incident.findings()) {
            out.append("\nFinding: ").append(finding.id()).append(" / ").append(finding.ruleId()).append(" / ").append(finding.category())
                    .append("\nSeverity: ").append(finding.severity()).append("\nLocation: ").append(finding.location().display())
                    .append("\nFunction: ").append(finding.functionName()).append("\nHandler: ").append(finding.handlerName())
                    .append("\nSource: ").append(finding.source()).append("\nSink: ").append(finding.sink())
                    .append("\nCapability: ").append(finding.capability()).append("\nConfidence: ").append(finding.confidence())
                    .append("\nAnalyzer: ").append(finding.origin()).append("\nReason: ").append(finding.explanation())
                    .append("\nEvidence: ").append(finding.evidence()).append("\nRecommendation: ").append(finding.recommendation()).append('\n');
            if (snippets) out.append("Code:\n").append(finding.codeSnippet()).append('\n');
            if (!finding.flow().isEmpty()) {
                out.append("Data flow:\n");
                for (TaintStep step : finding.flow()) out.append("  → ").append(step.expression()).append(" @ ")
                        .append(step.location().display()).append(" [").append(step.kind()).append("]\n");
            }
        }
        out.append("Inspect: /tys security inspect ").append(incident.id());
        return out.toString();
    }

    public static List<String> admin(SecurityIncident incident) {
        List<String> result = new ArrayList<>();
        result.add("[TachyonSecurity] " + incident.severity() + " — " + incident.file() + " " + incident.action());
        if (incident.findings().isEmpty()) result.add("Location: UNKNOWN — Precise source location unavailable.");
        for (SecurityFinding finding : incident.findings()) {
            result.add("Finding: " + finding.category() + " — " + finding.location().display());
            result.add("Function/handler: " + finding.functionName() + " / " + finding.handlerName());
            result.add("Code: " + compact(vulnerableCode(finding), 220));
            result.add("Source: " + finding.source() + " → Sink: " + finding.sink());
            result.add("Flow: " + compact(finding.flow().stream().map(TaintStep::expression).reduce((a,b) -> a + " → " + b).orElse("UNKNOWN"), 350));
            if (result.size() >= 20) break;
        }
        result.add("Incident: " + incident.id());
        result.add("Use /tys security inspect " + incident.id());
        return List.copyOf(result);
    }

    public static List<String> discord(SecurityIncident incident, boolean snippets) {
        return DiscordSecurityCard.render(incident, snippets);
    }

    private static String compact(String text, int length) {
        String plain = text.replace('\n', ' ').replace('\r', ' ');
        return plain.length() > length ? plain.substring(0, length) + " …" : plain;
    }
    private static String vulnerableCode(SecurityFinding finding) {
        if (!finding.location().known()) return "Precise source location unavailable.";
        String prefix = finding.location().startLine() + " | ";
        return finding.codeSnippet().lines().filter(line -> line.startsWith(prefix)).findFirst()
                .map(line -> line.substring(prefix.length())).orElse("Precise source location unavailable.");
    }
}
