package dev.tachyonscript.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Durable outbox; acknowledgement is persisted only after Discord confirms delivery (wait=true). */
public final class DiscordSecurityNotifier implements AutoCloseable {
    private final SecurityOptions.Discord options;
    private final Path folder;
    private final java.util.function.BiConsumer<String, String> failure;
    private final java.util.function.Predicate<String> committed;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Set<String> recorded = new HashSet<>();
    private final HttpClient client;
    private final ScheduledExecutorService worker;
    private volatile String lastFailure = "";
    private volatile boolean closed;

    public DiscordSecurityNotifier(SecurityOptions.Discord options, Path folder, Consumer<String> failure) throws IOException {
        this(options, folder, failure, ignored -> true);
    }
    public DiscordSecurityNotifier(SecurityOptions.Discord options, Path folder, Consumer<String> failure,
                                   java.util.function.Predicate<String> committed) throws IOException {
        this(options, folder, (id, message) -> failure.accept(message), committed);
    }
    public DiscordSecurityNotifier(SecurityOptions.Discord options, Path folder,
                                   java.util.function.BiConsumer<String, String> failure,
                                   java.util.function.Predicate<String> committed) throws IOException {
        this.options = options; this.folder = folder; this.failure = failure;
        this.committed = committed;
        client = options.enabled() ? HttpClient.newBuilder().connectTimeout(options.timeout()).followRedirects(HttpClient.Redirect.NEVER).build() : null;
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TachyonSecurity-Discord"); thread.setDaemon(true); return thread;
        });
        if (options.enabled()) {
            load();
            worker.scheduleWithFixedDelay(this::deliver, 0, 15, TimeUnit.SECONDS);
        }
    }

    public synchronized void enqueue(SecurityIncident incident) throws IOException {
        if (!options.enabled()) return;
        if (closed) throw new IOException("Discord security notifier closed");
        int part = 0;
        for (String payload : SecurityMessages.discord(incident, options.snippets())) {
            String id = incident.id() + "." + part++;
            if (recorded.contains(id)) continue;
            if (folder != null) append("discord-outbox.jsonl", SecurityJson.write(Map.of("id", id, "payload", payload)));
            pending.put(id, payload);
            recorded.add(id);
        }
        worker.execute(this::deliver);
    }
    public synchronized int pendingCount() { return pending.size(); }
    public void ready() { if (options.enabled() && !closed) worker.execute(this::deliver); }
    public String lastFailure() { return lastFailure; }

    private void deliver() {
        if (closed || !options.enabled()) return;
        Map<String, String> batch;
        synchronized (this) { batch = new LinkedHashMap<>(pending); }
        int delivered = 0;
        for (var entry : batch.entrySet()) {
            if (closed || delivered++ >= 50) return;
            if (!committed.test(entry.getKey().substring(0, entry.getKey().lastIndexOf('.')))) continue;
            try {
                URI uri = URI.create(options.webhook());
                String query = uri.getRawQuery();
                String filtered = query == null ? "" : java.util.Arrays.stream(query.split("&"))
                        .filter(part -> !part.toLowerCase(java.util.Locale.ROOT).startsWith("wait=")).reduce((a,b) -> a + "&" + b).orElse("");
                URI confirmed = URI.create(uri.toString().split("\\?", 2)[0] + "?wait=true" + (filtered.isEmpty() ? "" : "&" + filtered));
                BoundedHttp.post(client, confirmed, Map.of(), entry.getValue(), options.timeout(), 65_536, options.maxRetries());
                synchronized (this) {
                    if (folder != null) append("discord-delivered.jsonl", SecurityJson.write(Map.of("id", entry.getKey())));
                    pending.remove(entry.getKey());
                }
                lastFailure = "";
            } catch (IOException | RuntimeException e) {
                boolean firstFailure = lastFailure.isEmpty();
                lastFailure = "Discord delivery failed (details withheld to protect webhook credentials)";
                if (firstFailure) failure.accept(entry.getKey().substring(0, entry.getKey().lastIndexOf('.')),
                        "Discord security delivery failed; incident remains in the durable outbox.");
                return;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void append(String name, String line) throws IOException {
        Path path = folder.resolve(name); SecurityAuditStore.safe(path);
        SecurityAuditStore.appendFile(path, line + "\n");
    }
    private void load() throws IOException {
        if (folder == null) return;
        Set<String> delivered = new HashSet<>();
        read("discord-delivered.jsonl", value -> delivered.add(SecurityJson.string(value, "id")));
        read("discord-outbox.jsonl", value -> {
            String id = SecurityJson.string(value, "id"), payload = SecurityJson.string(value, "payload");
            if (!id.matches("TS-SEC-[0-9]{8}-[0-9a-f]{32}\\.[0-9]+")) throw new IllegalArgumentException("Invalid security outbox identity");
            recorded.add(id);
            if (!delivered.contains(id)) pending.put(id, payload);
            if (pending.size() > 100_000) throw new IllegalArgumentException("Security outbox backlog requires administrator attention");
        });
    }
    private void read(String name, Consumer<Map<String, Object>> consumer) throws IOException {
        Path path = folder.resolve(name); SecurityAuditStore.safe(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) consumer.accept(SecurityJson.object(SecurityJson.parse(line)));
        } catch (IllegalArgumentException e) { throw new IOException("Invalid Discord security outbox"); }
    }
    @Override public void close() { closed = true; worker.shutdownNow(); if (client != null) client.shutdownNow(); }
}
