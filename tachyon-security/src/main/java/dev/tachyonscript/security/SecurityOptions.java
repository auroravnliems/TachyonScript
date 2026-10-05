package dev.tachyonscript.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable policy. Secret-bearing configurations deliberately have redacted toString methods. */
public record SecurityOptions(boolean enabled, Ai ai, Discord discord, Set<String> allowedNetworkHosts,
                              Map<String, Capability> additionalCapabilities, int maxNodes, int maxFindings, int maxPendingTasks) {
    public SecurityOptions {
        if (ai == null || discord == null || maxNodes < 100 || maxNodes > 100_000
                || maxFindings < 10 || maxFindings > 5_000 || maxPendingTasks < 1 || maxPendingTasks > 100_000)
            throw new IllegalArgumentException("Invalid security limits");
        allowedNetworkHosts = Set.copyOf(allowedNetworkHosts);
        additionalCapabilities = Map.copyOf(additionalCapabilities);
    }

    public SecurityOptions(boolean enabled, Ai ai, Discord discord, Set<String> allowedNetworkHosts,
                           Map<String, Capability> additionalCapabilities, int maxNodes, int maxFindings) {
        this(enabled, ai, discord, allowedNetworkHosts, additionalCapabilities, maxNodes, maxFindings, 1024);
    }

    public static SecurityOptions defaults() {
        return new SecurityOptions(true, Ai.disabled(), Discord.disabled(), Set.of(), Map.of(), 25_000, 500);
    }

    public static SecurityOptions disabled() {
        SecurityOptions defaults = defaults();
        return new SecurityOptions(false, defaults.ai(), defaults.discord(), Set.of(), Map.of(), 25_000, 500);
    }

    public SecretRedactor redactor() { return new SecretRedactor(List.of(ai.apiKey(), discord.webhook())); }

    public record Ai(boolean enabled, URI endpoint, String model, String apiKey, Duration connectTimeout,
                     Duration readTimeout, int maxRetries, double warnConfidence, double disableConfidence,
                     boolean required, int maxRequestBytes, int maxResponseBytes, int maxReviewParts,
                     int maxOutputTokens) {
        public Ai {
            if (model == null || apiKey == null || connectTimeout == null || readTimeout == null
                    || connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.toMillis() > 60_000 || readTimeout.isNegative()
                    || readTimeout.isZero() || readTimeout.toMillis() > 120_000 || maxRetries < 0 || maxRetries > 5
                    || !Double.isFinite(warnConfidence) || !Double.isFinite(disableConfidence) || warnConfidence < 0
                    || disableConfidence < warnConfidence || disableConfidence > 1
                    || maxRequestBytes < 1024 || maxRequestBytes > 4_194_304
                    || maxResponseBytes < 1024 || maxResponseBytes > 1_048_576 || maxReviewParts < 1 || maxReviewParts > 128
                    || maxOutputTokens < 128 || maxOutputTokens > 65_536)
                throw new IllegalArgumentException("Invalid AI security configuration");
            if (enabled && (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())
                    && !isLocalTestEndpoint(endpoint) || model.isBlank() || apiKey.isBlank()
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null))
                throw new IllegalArgumentException("AI requires an HTTPS endpoint, model and API key");
        }
        public Ai(boolean enabled, URI endpoint, String model, String apiKey, Duration connectTimeout,
                  Duration readTimeout, int maxRetries, double warnConfidence, double disableConfidence,
                  boolean required, int maxRequestBytes, int maxResponseBytes, int maxReviewParts) {
            this(enabled, endpoint, model, apiKey, connectTimeout, readTimeout, maxRetries, warnConfidence,
                    disableConfidence, required, maxRequestBytes, maxResponseBytes, maxReviewParts, 4096);
        }
        public Ai(boolean enabled, URI endpoint, String model, String apiKey, Duration connectTimeout,
                  Duration readTimeout, int maxRetries, double warnConfidence, double disableConfidence,
                  boolean required, int maxRequestBytes, int maxResponseBytes) {
            this(enabled, endpoint, model, apiKey, connectTimeout, readTimeout, maxRetries, warnConfidence,
                    disableConfidence, required, maxRequestBytes, maxResponseBytes, 32);
        }
        public static Ai disabled() {
            return new Ai(false, null, "", "", Duration.ofSeconds(5), Duration.ofSeconds(15), 2,
                    .80, .95, true, 1_048_576, 262_144);
        }
        @Override public String toString() { return "Ai[enabled=" + enabled + ", required=" + required + "]"; }
    }

    public record Discord(boolean enabled, String webhook, boolean snippets, Duration timeout, int maxRetries) {
        public Discord {
            if (webhook == null || timeout == null || timeout.isZero() || timeout.isNegative()
                    || timeout.toMillis() > 120_000 || maxRetries < 0 || maxRetries > 5) throw new IllegalArgumentException("Invalid Discord configuration");
            if (enabled) {
                URI uri = URI.create(webhook);
                if ((!"https".equalsIgnoreCase(uri.getScheme()) && !isLocalTestEndpoint(uri))
                        || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                    throw new IllegalArgumentException("Discord requires an HTTPS webhook");
            }
        }
        public static Discord disabled() { return new Discord(false, "", true, Duration.ofSeconds(10), 2); }
        @Override public String toString() { return "Discord[enabled=" + enabled + "]"; }
    }

    private static boolean isLocalTestEndpoint(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) && Set.of("127.0.0.1", "[::1]", "localhost").contains(uri.getHost());
    }
}
