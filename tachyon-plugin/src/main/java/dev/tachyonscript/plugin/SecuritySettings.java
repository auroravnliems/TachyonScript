package dev.tachyonscript.plugin;

import dev.tachyonscript.security.Capability;
import dev.tachyonscript.security.SecurityOptions;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Invalid security configuration fails closed; secrets and endpoints never appear in exceptions. */
final class SecuritySettings {
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private SecuritySettings() { }
    static SecurityOptions from(ConfigurationSection config) { return from(config, System::getenv); }
    static SecurityOptions from(ConfigurationSection config, Function<String, String> environment) {
        try {
            String provider = config.getString("security.provider", "qwen");
            if (!provider.equalsIgnoreCase("qwen")) throw new IllegalArgumentException("Unsupported security provider");
            boolean aiEnabled = config.getBoolean("security.ai.enabled", false);
            String failurePolicy = config.getString("security.ai.failure-policy", "warn").toLowerCase(Locale.ROOT);
            if (!failurePolicy.equals("warn") && !failurePolicy.equals("keep-pending"))
                throw new IllegalArgumentException("Unknown AI failure policy");
            String reviewMode = config.getString("security.ai.review-mode", "background").toLowerCase(Locale.ROOT);
            if (!reviewMode.equals("background") && !reviewMode.equals("blocking"))
                throw new IllegalArgumentException("Unknown AI review mode");
            var ai = new SecurityOptions.Ai(aiEnabled, aiEnabled ? URI.create(config.getString("security.ai.endpoint", "")) : null,
                    aiEnabled ? config.getString("security.ai.model", "") : "",
                    aiEnabled ? secret(config.getString("security.ai.api-key", "${QWEN_API_KEY}"), environment) : "",
                    Duration.ofMillis(config.getLong("security.ai.connect-timeout-ms", 5000)),
                    Duration.ofMillis(config.getLong("security.ai.read-timeout-ms", 15000)),
                    config.getInt("security.ai.max-retries", 2), config.getDouble("security.ai.confidence.warn", .80),
                    config.getDouble("security.ai.confidence.auto-disable", .95), failurePolicy.equals("keep-pending"),
                    config.getInt("security.ai.max-request-bytes", 1_048_576), config.getInt("security.ai.max-response-bytes", 262_144),
                    config.getInt("security.ai.max-review-parts", 32), config.getInt("security.ai.max-output-tokens", 4096),
                    reviewMode.equals("background"), config.getInt("security.ai.max-concurrent-reviews", 2));
            boolean discordEnabled = config.getBoolean("security.discord.enabled", false);
            var discord = new SecurityOptions.Discord(discordEnabled,
                    discordEnabled ? secret(config.getString("security.discord.webhook", "${TACHYON_SECURITY_DISCORD_WEBHOOK}"), environment) : "",
                    config.getBoolean("security.discord.include-source-snippets", true),
                    Duration.ofMillis(config.getLong("security.discord.timeout-ms", 10_000)), config.getInt("security.discord.max-retries", 2));
            Map<String, Capability> additional = new HashMap<>();
            ConfigurationSection natives = config.getConfigurationSection("security.native-capabilities");
            if (natives != null) for (var entry : natives.getValues(false).entrySet())
                additional.put(entry.getKey(), Capability.valueOf(String.valueOf(entry.getValue()).toUpperCase(Locale.ROOT)));
            var hosts = new HashSet<String>();
            for (String host : config.getStringList("security.allowed-network-hosts")) {
                String normalized = host.toLowerCase(Locale.ROOT);
                if (normalized.isBlank() || normalized.contains("/") || normalized.contains("*") || normalized.contains("@"))
                    throw new IllegalArgumentException("Network exceptions require exact host names");
                hosts.add(normalized);
            }
            return new SecurityOptions(config.getBoolean("security.enabled", true), ai, discord, hosts, additional,
                    config.getInt("security.max-source-nodes", 25_000), config.getInt("security.max-findings", 500),
                    config.getInt("security.max-pending-tasks", 1024));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid security configuration; check provider, HTTPS endpoints, environment secrets, thresholds and limits. Values withheld.");
        }
    }
    static String migrationNotice(ConfigurationSection config) {
        return config.getBoolean("security.ai.enabled", false) && config.isSet("security.ai.required")
                && !config.isSet("security.ai.failure-policy")
                ? "TachyonSecurity: legacy ai.required is replaced by ai.failure-policy (default: warn). "
                    + "AI outages no longer block scripts that pass deterministic checks. "
                    + "Set security.ai.failure-policy: keep-pending to require a successful AI review."
                : "";
    }
    private static String secret(String value, Function<String, String> environment) {
        Matcher matcher = ENV.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String expanded = environment.apply(matcher.group(1));
            if (expanded == null || expanded.isBlank()) throw new IllegalArgumentException("Required security environment variable is absent");
            matcher.appendReplacement(out, Matcher.quoteReplacement(expanded));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
