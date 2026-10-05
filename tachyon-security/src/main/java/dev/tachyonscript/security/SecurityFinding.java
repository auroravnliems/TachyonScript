package dev.tachyonscript.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Findings contain locally resolved evidence; model-supplied locations/snippets are never used. */
public record SecurityFinding(String id, String ruleId, SecurityCategory category, SecuritySeverity severity,
                              double confidence, String scriptId, String sha256, String nodeId,
                              SourceSpan location, String functionName, String handlerName, String codeSnippet,
                              String source, String sink, String capability, String explanation, String evidence,
                              String recommendation, Origin origin, boolean concreteEvidence, List<TaintStep> flow) {
    public enum Origin { STATIC, AI, DEPENDENCY, MANUAL }

    public SecurityFinding {
        Objects.requireNonNull(location, "location (use explicit UNKNOWN)");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(origin, "origin");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Invalid security confidence");
        }
        if (id == null || id.isBlank() || ruleId == null || ruleId.isBlank()
                || !scriptId.matches("ts-[0-9a-f]{32}") || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid finding identity");
        }
        flow = List.copyOf(flow);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("ruleId", ruleId); result.put("category", category.name());
        result.put("severity", severity.name()); result.put("confidence", confidence);
        result.put("scriptId", scriptId); result.put("sha256", sha256); result.put("nodeId", nodeId);
        result.put("location", location.toJson()); result.put("function", functionName);
        result.put("handler", handlerName); result.put("codeSnippet", codeSnippet);
        result.put("source", source); result.put("sink", sink); result.put("capability", capability);
        result.put("explanation", explanation); result.put("evidence", evidence);
        result.put("recommendation", recommendation); result.put("origin", origin.name());
        result.put("concreteEvidence", concreteEvidence);
        result.put("flow", flow.stream().map(TaintStep::toJson).toList());
        return result;
    }

    static SecurityFinding read(Map<String, Object> value) {
        return new SecurityFinding(SecurityJson.string(value, "id"), SecurityJson.string(value, "ruleId"),
                SecurityCategory.valueOf(SecurityJson.string(value, "category")),
                SecuritySeverity.valueOf(SecurityJson.string(value, "severity")),
                SecurityJson.number(value, "confidence"), SecurityJson.string(value, "scriptId"),
                SecurityJson.string(value, "sha256"), SecurityJson.string(value, "nodeId"),
                SourceSpan.read(SecurityJson.object(value.get("location"))), SecurityJson.string(value, "function"),
                SecurityJson.string(value, "handler"), SecurityJson.string(value, "codeSnippet"),
                SecurityJson.string(value, "source"), SecurityJson.string(value, "sink"),
                SecurityJson.string(value, "capability"), SecurityJson.string(value, "explanation"),
                SecurityJson.string(value, "evidence"), SecurityJson.string(value, "recommendation"),
                Origin.valueOf(SecurityJson.string(value, "origin")), Boolean.TRUE.equals(value.get("concreteEvidence")),
                SecurityJson.array(value.get("flow")).stream().map(v -> TaintStep.read(SecurityJson.object(v))).toList());
    }
}
