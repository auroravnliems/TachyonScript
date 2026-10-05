package dev.tachyonscript.security;

import dev.tachyonscript.language.semantic.BoundModule;

import java.util.List;
import java.util.Map;

public record SecurityManifest(String scriptId, String sha256, String file, String module,
                               List<String> imports, List<String> exports, List<Map<String, Object>> handlers,
                               List<Map<String, Object>> commands, List<Map<String, Object>> tasks,
                               Map<String, String> dependencies, List<SecurityNode> nodes,
                               List<SecurityFinding> findings) {
    public SecurityManifest {
        imports = List.copyOf(imports); exports = List.copyOf(exports); handlers = List.copyOf(handlers);
        commands = List.copyOf(commands); tasks = List.copyOf(tasks); dependencies = Map.copyOf(dependencies);
        nodes = List.copyOf(nodes); findings = List.copyOf(findings);
    }

    public SecurityNode node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst().orElse(null);
    }

    public Map<String, Object> toJson() {
        return Map.ofEntries(Map.entry("schemaVersion", 1), Map.entry("scriptId", scriptId),
                Map.entry("sha256", sha256), Map.entry("file", file), Map.entry("module", module),
                Map.entry("imports", imports), Map.entry("exports", exports), Map.entry("handlers", handlers),
                Map.entry("commands", commands), Map.entry("scheduledTasks", tasks),
                Map.entry("dependencyHashes", dependencies), Map.entry("nodes", nodes.stream().map(SecurityNode::toJson).toList()),
                Map.entry("deterministicFindings", findings.stream().map(SecurityFinding::toJson).toList()));
    }

    static SecurityManifest of(BoundModule bound, List<SecurityNode> nodes, List<SecurityFinding> findings,
                               Map<String, String> dependencies) {
        return new SecurityManifest(ScriptIdentity.of(bound.file().path()), bound.file().hash(), bound.file().path(),
                bound.name(), bound.imports(), java.util.stream.Stream.concat(bound.functions().stream().map(f -> f.key()),
                java.util.stream.Stream.concat(bound.globals().stream().map(g -> g.name()),
                java.util.stream.Stream.concat(bound.constants().stream().map(c -> c.name()), bound.records().stream().map(r -> r.name())))).toList(),
                bound.handlers().stream().<Map<String, Object>>map(h -> Map.of("event", h.event().name(),
                        "span", SourceSpan.from(bound.file(), h.span()).toJson())).toList(),
                bound.commands().stream().<Map<String, Object>>map(c -> Map.of("path", c.path(),
                        "permission", c.options().permission(), "span", SourceSpan.from(bound.file(), c.span()).toJson())).toList(),
                bound.tasks().stream().<Map<String, Object>>map(t -> Map.of("intervalMillis", t.intervalMillis(),
                        "dailyMinute", t.dailyMinute(), "async", t.async(),
                        "span", SourceSpan.from(bound.file(), t.span()).toJson())).toList(), dependencies, nodes, findings);
    }
}
