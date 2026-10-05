package dev.tachyonscript.security;

import java.util.List;
import java.util.Map;

/** Compiler-backed evidence. AI can reference the ID but cannot replace any of these values. */
public record SecurityNode(String id, String kind, SourceSpan span, String function, String handler,
                           String nativeId, Capability capability, List<String> effects, String code,
                           List<Map<String, Object>> arguments, List<TaintStep> flow,
                           boolean concreteDanger, String permission) {
    public SecurityNode {
        effects = List.copyOf(effects);
        arguments = List.copyOf(arguments);
        flow = List.copyOf(flow);
    }
    public Map<String, Object> toJson() {
        return Map.ofEntries(Map.entry("nodeId", id), Map.entry("kind", kind), Map.entry("span", span.toJson()),
                Map.entry("function", function), Map.entry("handler", handler), Map.entry("nativeId", nativeId),
                Map.entry("capability", capability.name()), Map.entry("effects", effects), Map.entry("code", code),
                Map.entry("arguments", arguments), Map.entry("taintPath", flow.stream().map(TaintStep::toJson).toList()),
                Map.entry("concreteDanger", concreteDanger), Map.entry("permission", permission));
    }
}
