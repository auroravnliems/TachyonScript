package dev.tachyonscript.security;

import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.source.SourceFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Registered identities, exact-revision review, persisted policy and notifications. No Bukkit dependencies. */
public final class SecurityService implements AutoCloseable {
    public record Review(SecurityManifest manifest, List<SecurityFinding> findings, SecurityDecision decision, String failure) {
        public Review { findings = List.copyOf(findings); }
    }
    public record Batch(Map<String, Review> reviews, Set<String> denied, Set<String> pending, long policyVersion) {
        public Batch { reviews = Map.copyOf(reviews); denied = Set.copyOf(denied); pending = Set.copyOf(pending); }
    }
    private volatile SecurityOptions options;
    private final SecurityAuditStore audit;
    private volatile QwenSecurityProvider qwen;
    private volatile DiscordSecurityNotifier discord;
    private volatile long policyVersion;
    private final Consumer<SecurityIncident> notifications;
    private final Consumer<String> errors;
    private final Map<String, SourceFile> registered = new ConcurrentHashMap<>();
    private final Map<String, Review> cache = new ConcurrentHashMap<>();
    private final Map<String, SecurityManifest> manifests = new ConcurrentHashMap<>();
    private final Map<String, String> approvalContexts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> approvalSources = new ConcurrentHashMap<>();
    private final Set<String> immediateBlocks = ConcurrentHashMap.newKeySet();
    private final Map<String, ScriptRevocation> revocations = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SecurityService(SecurityOptions options, SecurityAuditStore audit, Consumer<SecurityIncident> notifications,
                           Consumer<String> errors) throws IOException {
        this.options = options; this.audit = audit; this.notifications = notifications; this.errors = errors;
        qwen = new QwenSecurityProvider(options);
        discord = new DiscordSecurityNotifier(options.discord(), audit.folder(), this::discordFailure, id -> audit.incident(id) != null);
        immediateBlocks.addAll(audit.blocked().keySet());
    }
    public static SecurityService inMemory(SecurityOptions options) {
        try { return new SecurityService(options, new SecurityAuditStore(null), incident -> { }, error -> { }); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    public SecurityOptions options() { return options; }
    public SecurityAuditStore audit() { return audit; }
    public DiscordSecurityNotifier discord() { return discord; }
    public boolean closed() { return closed; }

    public void register(List<SourceFile> sources) {
        for (SourceFile file : sources) {
            String id = ScriptIdentity.of(file.path());
            registered.put(id, file);
            revocations.computeIfAbsent(id, ignored -> new ScriptRevocation(immediateBlocks.contains(id)));
        }
    }
    public ScriptRevocation revocation(String path) {
        String id = ScriptIdentity.of(path);
        return revocations.computeIfAbsent(id, ignored -> new ScriptRevocation(immediateBlocks.contains(id)));
    }
    public SourceFile registeredFile(String path) {
        SourceFile source = registered.get(ScriptIdentity.of(path));
        if (source == null || !source.path().equals(path)) throw new IllegalArgumentException("Unknown registered script");
        return source;
    }
    public boolean blocked(String path) { return immediateBlocks.contains(ScriptIdentity.of(path)); }
    public SecurityManifest manifest(String path) { return manifests.get(path); }

    public synchronized Batch review(List<BoundModule> modules) {
        if (closed) throw new IllegalStateException("Security service closed");
        Map<String, Review> reviews = new LinkedHashMap<>();
        Set<String> denied = new HashSet<>(), pending = new HashSet<>();
        if (!options.enabled()) {
            for (BoundModule module : modules) if (blocked(module.file().path())) denied.add(module.file().path());
            propagateDenial(modules, denied);
            return new Batch(reviews, denied, pending, policyVersion);
        }
        Map<String, SecurityManifest> analyzed;
        try { analyzed = new SecurityAnalyzer(options).analyze(modules); }
        catch (RuntimeException e) {
            for (BoundModule module : modules) {
                pending.add(module.file().path());
                failure(module.file(), "SCANNER_FAILURE", "Security scanner failed; this revision cannot activate. No vulnerability location was fabricated.");
            }
            return new Batch(reviews, denied, pending, policyVersion);
        }
        Map<String, List<String>> importers = affectedDependencies(modules,
                modules.stream().map(module -> module.file().path()).collect(java.util.stream.Collectors.toSet()));
        for (BoundModule module : modules) {
            SecurityManifest manifest = analyzed.get(module.file().path());
            Map<String, String> related = new java.util.TreeMap<>(manifest.dependencies());
            related.put(manifest.file(), manifest.sha256());
            for (String caller : importers.getOrDefault(manifest.file(), List.of())) {
                BoundModule importer = modules.stream().filter(candidate -> candidate.file().path().equals(caller)).findFirst().orElseThrow();
                related.put(caller, importer.file().hash());
            }
            // Source changes in importing modules can introduce new taint without changing this library's bytes.
            String context = ScriptIdentity.hash(SecurityJson.write(List.of(
                    related.entrySet().stream().map(entry -> List.of(entry.getKey(), entry.getValue())).toList(),
                    manifest.nodes().stream().map(node -> node.nativeId() + ":" + node.capability() + ":" + node.permission()
                            + ":" + String.join(",", node.effects())).distinct().sorted().toList(),
                    manifest.findings().stream().map(finding -> finding.id() + ":" + finding.severity()).sorted().toList(),
                    options.allowedNetworkHosts().stream().sorted().toList())));
            approvalSources.put(manifest.file(), Map.copyOf(related)); approvalContexts.put(manifest.file(), context);
        }
        for (BoundModule module : modules) {
            SourceFile source = module.file();
            SecurityManifest manifest = analyzed.get(source.path());
            manifests.put(source.path(), manifest);
            if (blocked(source.path())) { denied.add(source.path()); continue; }
            String key = cacheKey(manifest) + ":" + approvalContexts.get(source.path());
            Review review = cache.get(key);
            if (review == null) {
                List<SecurityFinding> findings = new ArrayList<>(manifest.findings());
                String failure = "";
                boolean approved = audit.approved(manifest.scriptId(), manifest.sha256(), approvalContexts.get(source.path()));
                // A deterministic denial already has complete evidence and does not wait on an external advisor.
                if (options.ai().enabled() && (approved || !SecurityPolicyEngine.decide(findings, options).deniesExecution())) {
                    try { findings.addAll(qwen.review(manifest, source)); }
                    catch (IOException | IllegalArgumentException e) { failure = "Qwen review unavailable or invalid; no AI vulnerability was fabricated."; }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); failure = "Qwen review cancelled; revision remains pending."; }
                }
                SecurityDecision decision = approved ? SecurityDecision.WARN
                        : SecurityPolicyEngine.decide(findings, options);
                review = new Review(manifest, findings, decision, failure);
                if (failure.isEmpty()) cache.put(key, review);
            }
            reviews.put(source.path(), review);
            if (review.decision().deniesExecution()) denied.add(source.path());
            if (!review.failure().isEmpty() && options.ai().required()) pending.add(source.path());
        }
        propagateDenial(modules, denied);
        propagateDenial(modules, pending);
        return new Batch(reviews, denied, pending, policyVersion);
    }

    private static String cacheKey(SecurityManifest manifest) {
        // Include context-sensitive flows as well as imported hashes: edits in an importer can change a callee's taint.
        return ScriptIdentity.hash(SecurityJson.write(manifest.toJson()));
    }
    private static void propagateDenial(List<BoundModule> modules, Set<String> denied) {
        Map<String, String> paths = new HashMap<>(); modules.forEach(m -> paths.put(m.name(), m.file().path()));
        boolean changed;
        do {
            changed = false;
            for (BoundModule module : modules)
                if (module.imports().stream().anyMatch(name -> denied.contains(paths.get(name)))) changed |= denied.add(module.file().path());
        } while (changed);
    }

    /** Called only after the engine verifies that every source hash in the batch is still current. */
    public synchronized void commit(Batch batch, List<BoundModule> modules) throws IOException {
        if (closed) throw new IOException("Security service closed before commit");
        if (batch.policyVersion() != policyVersion) throw new IOException("Security policy changed during review; scan again");
        Map<String, List<String>> dependents = affectedDependencies(modules, batch.denied());
        for (var entry : batch.reviews().entrySet()) {
            Review review = entry.getValue();
            SourceFile source = registeredFile(entry.getKey());
            if (!source.hash().equals(review.manifest().sha256())) throw new IOException("Stale security review cannot be committed");
            if (!review.failure().isEmpty()) failure(source, "API_FAILURE", review.failure());
            if (review.decision() == SecurityDecision.ALLOW && review.findings().isEmpty()) continue;
            String action = review.decision().deniesExecution() ? "AUTO_QUARANTINE" : review.decision() == SecurityDecision.WARN ? "WARN" : "ADVISORY";
            if (audit.incidents().stream().anyMatch(i -> i.sha256().equals(source.hash()) && i.scriptId().equals(review.manifest().scriptId())
                    && i.action().equals(action) && i.findings().equals(review.findings()))) continue;
            SecurityIncident incident = incident(source, review.decision(), action,
                    review.findings(), review.decision().deniesExecution() ? "Compiler-backed dangerous behavior blocked before activation."
                            : review.decision() == SecurityDecision.WARN ? "Security findings require administrator review."
                            : "AI advisory recorded below the configured warning confidence threshold.",
                    dependents.getOrDefault(source.path(), List.of()), "security-policy", "");
            record(incident, review.decision().deniesExecution() ? source : null);
        }
        // Dependents are logical security disables with their exact compiler-backed import span.
        for (BoundModule module : modules) {
            if (!batch.denied().contains(module.file().path()) || blocked(module.file().path())) continue;
            String dependency = module.imports().stream().filter(name -> modules.stream().anyMatch(m -> m.name().equals(name)
                    && batch.denied().contains(m.file().path()))).findFirst().orElse("");
            SourceSpan span = SecurityAnalyzer.importLocation(module, dependency);
            SecurityFinding finding = new SecurityFinding("DEP-" + ScriptIdentity.hash(module.file().hash() + dependency).substring(0,24),
                    "DEPENDENCY_DENIED", SecurityCategory.DEPENDENCY_BLOCKED, SecuritySeverity.HIGH, 1,
                    ScriptIdentity.of(module.file().path()), module.file().hash(), "", span, "", "module import",
                    SourceSnippets.context(module.file(), span, options.redactor().withSource(module.file())), dependency, "module activation", "DEPENDENCY",
                    "This script imports a security-disabled dependency: " + dependency, dependency,
                    "Inspect and restore the dependency before restoring this script.", SecurityFinding.Origin.DEPENDENCY, span.known(), List.of());
            record(incident(module.file(), SecurityDecision.DISABLE, "AUTO_DISABLE", List.of(finding),
                    "Dependency security revocation.", dependents.getOrDefault(module.file().path(), List.of()), "security-policy", ""), module.file());
        }
    }

    public static Map<String, List<String>> affectedDependencies(List<BoundModule> modules, Set<String> denied) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (BoundModule source : modules) {
            if (!denied.contains(source.file().path())) continue;
            Set<String> names = new HashSet<>(); names.add(source.name());
            boolean grew;
            do { grew = false; for (BoundModule module : modules) if (module.imports().stream().anyMatch(names::contains)) grew |= names.add(module.name()); } while (grew);
            result.put(source.file().path(), modules.stream().filter(m -> !m.name().equals(source.name()) && names.contains(m.name())).map(m -> m.file().path()).sorted().toList());
        }
        return result;
    }

    /** Immediate logical revocation; engine separately cancels runtime work on the correct platform thread. */
    public void blockNow(String path) { registeredFile(path); immediateBlocks.add(ScriptIdentity.of(path)); revocation(path).block(); cache.clear(); }

    public synchronized SecurityIncident manual(String path, SecurityDecision decision, List<String> dependencies, String actor) throws IOException {
        SourceFile source = registeredFile(path);
        SecurityManifest manifest = manifests.get(path);
        List<SecurityFinding> findings = manifest != null && manifest.sha256().equals(source.hash()) ? manifest.findings() : List.of();
        SecurityIncident incident = incident(source, decision, decision == SecurityDecision.QUARANTINE ? "QUARANTINE" : "DISABLE",
                findings, "Administrator requested security revocation.", dependencies, options.redactor().redact(actor), "");
        blockNow(path); record(incident, source); return incident;
    }

    public synchronized SecurityIncident approve(String path, String actor) throws IOException {
        SourceFile source = registeredFile(path);
        SecurityManifest manifest = manifests.get(path);
        Map<String, String> related = approvalSources.get(path);
        if (manifest == null || !manifest.sha256().equals(source.hash()) || related == null || related.entrySet().stream()
                .anyMatch(entry -> !registeredFile(entry.getKey()).hash().equals(entry.getValue())))
            throw new IOException("Scan the current source and related modules before approving this revision");
        SecurityIncident incident = new SecurityIncident(SecurityAuditStore.incidentId(), Instant.now(), ScriptIdentity.of(path), path, source.hash(),
                SecurityDecision.WARN, "APPROVE", SecuritySeverity.INFO,
                "Administrator approved this exact SHA-256 and reviewed dependency/importer context; related edits require fresh review.",
                List.of(), List.of(), options.redactor().redact(actor), "", approvalContexts.get(path));
        record(incident, null); cache.clear(); return incident;
    }

    /** Release a logical quarantine, preserving all source/report archives. A subsequent scan gates activation. */
    public synchronized SecurityIncident restore(String incidentId, String actor) throws IOException {
        SecurityIncident old = audit.incident(incidentId);
        if (old == null || !old.decision().deniesExecution()) throw new IOException("Unknown quarantine/disable incident");
        SourceFile source = registeredFile(old.file());
        if (!audit.approved(old.scriptId(), source.hash())) throw new IOException("Explicit approval of the current exact source hash is required before restoring quarantined code");
        SecurityIncident restored = incident(source, SecurityDecision.WARN, "RESTORE", List.of(),
                "Administrator restored an explicitly approved source revision; activation still requires the compiler and dependency gate.", old.dependencies(), options.redactor().redact(actor), incidentId);
        record(restored, null); immediateBlocks.remove(old.scriptId()); revocation(old.file()).restore(); cache.clear(); return restored;
    }

    public void invalidateReviews() { cache.clear(); }
    public synchronized void reloadOptions(SecurityOptions updated) throws IOException {
        if (closed) throw new IOException("Security service closed");
        QwenSecurityProvider replacementQwen = new QwenSecurityProvider(updated);
        discord.close();
        DiscordSecurityNotifier replacementDiscord;
        try { replacementDiscord = new DiscordSecurityNotifier(updated.discord(), audit.folder(), this::discordFailure, id -> audit.incident(id) != null); }
        catch (IOException | RuntimeException e) { replacementQwen.close(); throw e; }
        qwen.close(); qwen = replacementQwen; discord = replacementDiscord; options = updated;
        policyVersion++; cache.clear();
    }
    public synchronized SecurityIncident testWebhook(String path, String actor) throws IOException {
        SourceFile source = registeredFile(path);
        SecurityIncident incident = incident(source, SecurityDecision.ALLOW, "WEBHOOK_TEST", List.of(), "Administrator webhook delivery test.", List.of(), actor, "");
        record(incident, null); return incident;
    }

    private SecurityIncident incident(SourceFile source, SecurityDecision decision, String action, List<SecurityFinding> findings,
                                      String summary, List<String> dependencies, String actor, String related) {
        SecuritySeverity severity = findings.stream().map(SecurityFinding::severity).max(Enum::compareTo).orElse(SecuritySeverity.INFO);
        return new SecurityIncident(SecurityAuditStore.incidentId(), Instant.now(), ScriptIdentity.of(source.path()), source.path(), source.hash(),
                decision, action, severity, summary, findings, dependencies, actor, related);
    }
    private synchronized void record(SecurityIncident incident, SourceFile source) throws IOException {
        if (closed) throw new IOException("Security service closed; incident commit cancelled");
        if (incident.decision().deniesExecution()) { immediateBlocks.add(incident.scriptId()); revocation(incident.file()).block(); }
        // Stage only this newly generated, redacted alert. Delivery waits for its exact audit commit.
        discord.enqueue(incident);
        audit.append(incident, source);
        // Console/admin delivery is independent of webhook transport availability.
        try { notifications.accept(incident); } catch (RuntimeException e) { errors.accept("Security administrator notification failed; incident is retained in audit."); }
        discord.ready();
    }
    private void failure(SourceFile source, String action, String summary) {
        try { record(incident(source, SecurityDecision.WARN, action, List.of(), summary, List.of(), "security-service", ""), null); }
        catch (IOException e) {
            errors.accept("Security audit/notification failure; affected pending sources cannot activate.");
            throw new IllegalStateException("Security audit/notification infrastructure unavailable", e);
        }
    }
    private synchronized void discordFailure(String incidentId, String message) {
        if (closed) return;
        errors.accept(message);
        SecurityIncident original = audit.incident(incidentId);
        if (original == null || original.action().equals("DISCORD_API_FAILURE")) return;
        SecurityIncident failure = new SecurityIncident(SecurityAuditStore.incidentId(), Instant.now(), original.scriptId(),
                original.file(), original.sha256(), SecurityDecision.WARN, "DISCORD_API_FAILURE", SecuritySeverity.MEDIUM,
                "Webhook transport failed. The original alert remains queued for retry. No vulnerability location was fabricated.",
                List.of(), original.dependencies(), "security-service", incidentId);
        try { record(failure, null); }
        catch (IOException | RuntimeException e) { errors.accept("Cannot persist webhook failure notification; original alert remains in the outbox."); }
    }
    @Override public void close() {
        closed = true; qwen.close(); discord.close();
        try { audit.close(); } catch (IOException e) { errors.accept("Cannot close security audit cleanly"); }
    }
}
