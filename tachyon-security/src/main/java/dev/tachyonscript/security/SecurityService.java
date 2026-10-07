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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Registered identities, exact-revision review, persisted policy and notifications. No Bukkit dependencies. */
public final class SecurityService implements AutoCloseable {
    public record Review(SecurityManifest manifest, List<SecurityFinding> findings, SecurityDecision decision, String failure) {
        public Review { findings = List.copyOf(findings); }
    }
    /**
     * The outcome of one review pass.
     *
     * @param awaiting revisions whose AI review is still running in the background, with the modules
     *                 importing them. They are not decided yet: they must not activate, and the service
     *                 reports their paths to {@link #onReviewed} when the review is done.
     */
    public record Batch(Map<String, Review> reviews, Set<String> denied, Set<String> pending, Set<String> awaiting,
                        long policyVersion) {
        public Batch {
            reviews = Map.copyOf(reviews); denied = Set.copyOf(denied); pending = Set.copyOf(pending);
            awaiting = Set.copyOf(awaiting);
        }
        public Batch(Map<String, Review> reviews, Set<String> denied, Set<String> pending, long policyVersion) {
            this(reviews, denied, pending, Set.of(), policyVersion);
        }
    }
    private volatile SecurityOptions options;
    private final SecurityAuditStore audit;
    private volatile QwenSecurityProvider qwen;
    private volatile DiscordSecurityNotifier discord;
    private volatile long policyVersion;
    private final Consumer<SecurityIncident> notifications;
    private final Consumer<String> errors;
    private final Map<String, SourceFile> registered = new ConcurrentHashMap<>();
    /** Decided reviews by exact input, least recently used dropped first: edits must not grow memory. */
    private final Map<String, Review> cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Review> eldest) { return size() > 256; }
    });
    private final Map<String, SecurityManifest> manifests = new ConcurrentHashMap<>();
    private final Map<String, String> approvalContexts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> approvalSources = new ConcurrentHashMap<>();
    private final Set<String> immediateBlocks = ConcurrentHashMap.newKeySet();
    private final Map<String, ScriptRevocation> revocations = new ConcurrentHashMap<>();
    /** AI results by exact input, across restarts (see {@link AiReviewCache}). */
    private final AiReviewCache aiReviews;
    /** Keys of background AI reviews that are running or queued. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private ThreadPoolExecutor reviewers;
    private volatile Consumer<Set<String>> reviewed = paths -> { };
    private volatile boolean closed;
    private final LongSupplier clock;
    private long aiRetryAt;
    private long aiFailureNoticeAt;
    private boolean aiFailureReported;
    private String aiLastFailure = "";

    public SecurityService(SecurityOptions options, SecurityAuditStore audit, Consumer<SecurityIncident> notifications,
                           Consumer<String> errors) throws IOException {
        this(options, audit, notifications, errors, System::nanoTime);
    }
    SecurityService(SecurityOptions options, SecurityAuditStore audit, Consumer<SecurityIncident> notifications,
                    Consumer<String> errors, LongSupplier clock) throws IOException {
        this.options = options; this.audit = audit; this.notifications = notifications; this.errors = errors;
        this.clock = clock;
        qwen = new QwenSecurityProvider(options);
        discord = new DiscordSecurityNotifier(options.discord(), audit.folder(), this::discordFailure, id -> audit.incident(id) != null);
        immediateBlocks.addAll(audit.blocked().keySet());
        aiReviews = new AiReviewCache(audit.folder(), errors);
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
    /** Whether {@code sha256} is the registered (latest read) revision of {@code path}. */
    public boolean current(String path, String sha256) {
        SourceFile source = registered.get(ScriptIdentity.of(path));
        return source != null && source.path().equals(path) && source.hash().equals(sha256);
    }

    /**
     * Receives the paths whose background AI review finished, successfully or not, on a review
     * thread. The engine then activates the reviewed revisions or re-checks active scripts.
     */
    public void onReviewed(Consumer<Set<String>> listener) { reviewed = java.util.Objects.requireNonNull(listener, "listener"); }
    /** Background AI reviews running or queued. */
    public int reviewsRunning() { return inFlight.size(); }
    /** AI reviews remembered across restarts. */
    public int storedReviews() { return aiReviews.size(); }

    public synchronized Batch review(List<BoundModule> modules) {
        if (closed) throw new IllegalStateException("Security service closed");
        Map<String, Review> reviews = new LinkedHashMap<>();
        Set<String> denied = new HashSet<>(), pending = new HashSet<>(), awaiting = new HashSet<>();
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
            // Kept for approvals, imports and findings; the nodes stay only with a running AI review.
            manifests.put(source.path(), manifest.withoutNodes());
            if (blocked(source.path())) { denied.add(source.path()); continue; }
            String key = cacheKey(manifest) + ":" + approvalContexts.get(source.path());
            Review review = cache.get(key);
            if (review == null) {
                List<SecurityFinding> findings = new ArrayList<>(manifest.findings());
                String failure = "";
                boolean approved = audit.approved(manifest.scriptId(), manifest.sha256(), approvalContexts.get(source.path()));
                // A deterministic denial already has complete evidence and does not wait on an external advisor.
                if (options.ai().enabled() && QwenSecurityProvider.needsReview(manifest)
                        && (approved || !SecurityPolicyEngine.decide(findings, options).deniesExecution())) {
                    String aiKey = ScriptIdentity.hash(key + "|" + QwenSecurityProvider.fingerprint(options));
                    List<SecurityFinding> stored = aiReviews.get(aiKey);
                    if (stored != null) {
                        findings.addAll(stored);
                    } else if (!aiLastFailure.isEmpty() && clock.getAsLong() - aiRetryAt < 0) {
                        failure = aiLastFailure;
                    } else if (options.ai().background()) {
                        // Decided when the review arrives; nothing is cached or reported for it yet.
                        reviewLater(aiKey, manifest, source);
                        awaiting.add(source.path());
                        continue;
                    } else {
                        try {
                            List<SecurityFinding> reviewedFindings = qwen.review(manifest, source);
                            aiReviews.put(aiKey, reviewedFindings);
                            findings.addAll(reviewedFindings);
                            aiLastFailure = "";
                        } catch (IOException | IllegalArgumentException e) {
                            failure = unavailable(e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failure = "Qwen review cancelled.";
                            aiLastFailure = failure;
                            aiRetryAt = clock.getAsLong() + TimeUnit.SECONDS.toNanos(60);
                        }
                    }
                }
                SecurityDecision decision = approved ? SecurityDecision.WARN
                        : SecurityPolicyEngine.decide(findings, options);
                review = new Review(manifest.withoutNodes(), findings, decision, failure);
                if (failure.isEmpty()) cache.put(key, review);
            }
            reviews.put(source.path(), review);
            if (review.decision().deniesExecution()) denied.add(source.path());
            if (!review.failure().isEmpty() && options.ai().required()) pending.add(source.path());
        }
        propagateDenial(modules, denied);
        propagateDenial(modules, pending);
        // A module importing a revision under review was compiled against it: it waits as well.
        propagateDenial(modules, awaiting);
        awaiting.removeAll(denied);
        return new Batch(reviews, denied, pending, awaiting, policyVersion);
    }

    /** Records a provider failure for the shared cooldown and returns its safe description. */
    private String unavailable(Exception e) {
        String failure = "Qwen review unavailable: " + (e instanceof ReviewFailure
                ? e.getMessage() : "request or response could not be validated.");
        aiLastFailure = failure;
        aiRetryAt = clock.getAsLong() + TimeUnit.SECONDS.toNanos(60);
        return failure;
    }

    /** Queues one AI review (once per exact input); the provider is called outside the service lock. */
    private void reviewLater(String aiKey, SecurityManifest manifest, SourceFile source) {
        if (closed || !inFlight.add(aiKey)) return;
        QwenSecurityProvider provider = qwen;
        try {
            reviewers().execute(() -> backgroundReview(provider, aiKey, manifest, source));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            inFlight.remove(aiKey);
        }
    }

    private void backgroundReview(QwenSecurityProvider provider, String aiKey, SecurityManifest manifest, SourceFile source) {
        try {
            List<SecurityFinding> findings;
            boolean coolingDown;
            synchronized (this) { coolingDown = !aiLastFailure.isEmpty() && clock.getAsLong() - aiRetryAt < 0; }
            try {
                // Reviews queued before an outage was noticed do not each retry the provider.
                findings = coolingDown ? null : provider.review(manifest, source);
            } catch (IOException | IllegalArgumentException e) {
                synchronized (this) { unavailable(e); }
                findings = null;
            }
            if (findings != null) {
                synchronized (this) {
                    if (closed) return;
                    aiReviews.put(aiKey, findings);
                    aiLastFailure = "";
                }
            }
        } catch (InterruptedException e) {
            // Shutting down or reconfiguring: the next load asks again.
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            errors.accept("Background security review failed unexpectedly; the script will be reviewed again on the next load.");
            synchronized (this) {
                aiLastFailure = "Qwen review failed unexpectedly.";
                aiRetryAt = clock.getAsLong() + TimeUnit.SECONDS.toNanos(60);
            }
        } finally {
            inFlight.remove(aiKey);
        }
        if (closed) return;
        try {
            reviewed.accept(Set.of(source.path()));
        } catch (RuntimeException e) {
            errors.accept("Activating a reviewed script failed; reload it to retry.");
        }
    }

    private synchronized ThreadPoolExecutor reviewers() {
        if (closed) throw new java.util.concurrent.RejectedExecutionException("Security service closed");
        if (reviewers == null) {
            int threads = options.ai().maxConcurrentReviews();
            java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
            reviewers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "TachyonSecurity-Review-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
            // An idle server keeps no review threads.
            reviewers.allowCoreThreadTimeOut(true);
        }
        return reviewers;
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
        // Check every identity before writing an aggregate provider incident.
        for (var entry : batch.reviews().entrySet()) {
            if (!registeredFile(entry.getKey()).hash().equals(entry.getValue().manifest().sha256()))
                throw new IOException("Stale security review cannot be committed");
        }
        reportAiFailure(batch);
        Map<String, List<String>> dependents = affectedDependencies(modules, batch.denied());
        for (var entry : batch.reviews().entrySet()) {
            Review review = entry.getValue();
            SourceFile source = registeredFile(entry.getKey());
            if (!source.hash().equals(review.manifest().sha256())) throw new IOException("Stale security review cannot be committed");
            if (review.decision() == SecurityDecision.ALLOW && review.findings().isEmpty()) continue;
            String action = review.decision().deniesExecution() ? "AUTO_QUARANTINE" : review.decision() == SecurityDecision.WARN ? "WARN" : "ADVISORY";
            if (audit.recorded(review.manifest().scriptId(), source.hash(), action, review.findings())) continue;
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

    private void reportAiFailure(Batch batch) {
        List<Review> unavailable = batch.reviews().values().stream().filter(r -> !r.failure().isEmpty())
                .sorted(java.util.Comparator.comparing(r -> r.manifest().file())).toList();
        long now = clock.getAsLong();
        if (unavailable.isEmpty() || aiFailureReported && now - aiFailureNoticeAt < TimeUnit.MINUTES.toNanos(5)) return;
        Review first = unavailable.getFirst();
        String policy = options.ai().required()
                ? "Explicit failure-policy=keep-pending holds new revisions; previous valid runtimes remain active."
                : "Deterministic checks remain active; this AI outage does not disable scripts.";
        failure(registeredFile(first.manifest().file()), "API_FAILURE",
                first.failure() + " Affects " + unavailable.size() + " source revisions in this scan. "
                        + policy + " Provider retries pause for 60 seconds; repeated alerts are limited to once per 5 minutes.");
        aiFailureReported = true;
        aiFailureNoticeAt = now;
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
        aiLastFailure = ""; aiFailureReported = false;
        if (reviewers != null) {
            int threads = updated.ai().maxConcurrentReviews();
            if (threads > reviewers.getMaximumPoolSize()) { reviewers.setMaximumPoolSize(threads); reviewers.setCorePoolSize(threads); }
            else { reviewers.setCorePoolSize(threads); reviewers.setMaximumPoolSize(threads); }
        }
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
        closed = true;
        synchronized (this) { if (reviewers != null) reviewers.shutdownNow(); }
        qwen.close(); discord.close();
        try { audit.close(); } catch (IOException e) { errors.accept("Cannot close security audit cleanly"); }
    }
}
