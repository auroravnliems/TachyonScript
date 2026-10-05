package dev.tachyonscript.security;

import java.util.Map;

public record TaintStep(String nodeId, SourceSpan location, String expression, String kind) {
    public Map<String, Object> toJson() {
        return Map.of("nodeId", nodeId, "location", location.toJson(), "expression", expression, "kind", kind);
    }

    static TaintStep read(Map<String, Object> value) {
        return new TaintStep(SecurityJson.string(value, "nodeId"),
                SourceSpan.read(SecurityJson.object(value.get("location"))),
                SecurityJson.string(value, "expression"), SecurityJson.string(value, "kind"));
    }
}
