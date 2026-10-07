import dev.tachyonscript.security.SecurityJson;
import dev.tachyonscript.security.SecurityOptions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.zip.ZipFile;

/** Local archive migration; never prints credential values or calls external services. */
public class ConfigFromArchive {
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception exception) {
            // YAML and URI exception messages can embed secrets from their input.
            System.err.println("Config check failed (details withheld): " + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        String original;
        try (var archive = new ZipFile(args[0])) {
            var entry = archive.getEntry("config.yml");
            if (entry == null) throw new IllegalArgumentException();
            try (var input = archive.getInputStream(entry)) {
                original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        var old = new YamlConfiguration();
        old.loadFromString(original);
        var summary = new LinkedHashMap<String, Object>();
        String key = old.getString("security.ai.api-key", "");
        String webhook = old.getString("security.discord.webhook", "");
        String endpoint = old.getString("security.ai.endpoint", "");
        summary.put("aiEnabled", old.getBoolean("security.ai.enabled"));
        summary.put("discordEnabled", old.getBoolean("security.discord.enabled"));
        summary.put("endpointMatchesOpenRouter", endpoint.equals("https://openrouter.ai/api/v1/chat/completions"));
        summary.put("modelMatchesQwenCoder", old.getString("security.ai.model", "").equals("qwen/qwen3-coder"));
        summary.put("apiKeyPresent", !key.isBlank());
        summary.put("apiKeyEnvironmentReference", key.contains("${"));
        summary.put("apiKeyIsValidEnvReference", key.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}"));
        summary.put("apiKeyIsWrappedLiteralToken", key.matches("\\$\\{sk-or-v1-[A-Za-z0-9_-]+}"));
        summary.put("apiKeyLooksLikeOpenRouter", key.matches("sk-or-v1-[A-Za-z0-9_-]+"));
        summary.put("apiKeyHasBearerPrefix", key.startsWith("Bearer "));
        summary.put("apiKeyHasWhitespace", key.chars().anyMatch(Character::isWhitespace));
        summary.put("webhookPresent", !webhook.isBlank());
        summary.put("webhookEnvironmentReference", webhook.contains("${"));
        summary.put("webhookHasWhitespace", webhook.chars().anyMatch(Character::isWhitespace));
        boolean webhookFormat = false;
        try {
            URI uri = URI.create(webhook);
            webhookFormat = "https".equals(uri.getScheme())
                    && java.util.Set.of("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com").contains(uri.getHost())
                    && uri.getPath().matches("/api/(v[0-9]+/)?webhooks/[0-9]+/[A-Za-z0-9_-]+")
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (RuntimeException ignored) { }
        summary.put("webhookFormatValid", webhookFormat);
        if (args.length == 1) {
            System.out.println(SecurityJson.write(summary));
            return;
        }

        String migrated = original.replaceFirst("(?m)^    required:.*$",
                "    # AI failures warn; deterministic checks and quarantines remain active.\n"
                + "    failure-policy: warn");
        if (migrated.equals(original)) throw new IllegalArgumentException();
        boolean wrappedKey = key.matches("\\$\\{sk-or-v1-[A-Za-z0-9_-]+}");
        String actualKey = wrappedKey ? key.substring(2, key.length() - 1) : key;
        if (wrappedKey) migrated = migrated.replace(key, actualKey);
        var config = new YamlConfiguration();
        config.loadFromString(migrated);
        if (!"warn".equals(config.getString("security.ai.failure-policy"))) throw new IllegalStateException();
        for (String field : old.getKeys(true)) {
            if (old.isConfigurationSection(field) || field.equals("security.ai.required")
                    || field.equals("security.ai.api-key")) continue;
            if (!Objects.equals(old.get(field), config.get(field))) throw new IllegalStateException();
        }
        if (!actualKey.equals(config.getString("security.ai.api-key"))) throw new IllegalStateException();
        var parse = Class.forName("dev.tachyonscript.plugin.SecuritySettings")
                .getDeclaredMethod("from", ConfigurationSection.class);
        parse.setAccessible(true);
        var options = (SecurityOptions) parse.invoke(null, config);
        if (options.ai().required()) throw new IllegalStateException();
        Path target = Path.of(args[1]);
        if (Files.exists(target)) throw new IllegalStateException();
        Files.writeString(target, migrated, StandardCharsets.UTF_8);
        summary.put("runtimeConfigValidation", "passed");
        summary.put("unrelatedSettingsAndSecretsPreserved", true);
        summary.put("apiKeyLiteralWrapperRemoved", wrappedKey);
        summary.put("failurePolicy", "warn");
        if (args.length > 2 && args[2].equals("verify-read-only")) {
            if (!endpoint.equals("https://openrouter.ai/api/v1/chat/completions")
                    || !actualKey.matches("sk-or-v1-[A-Za-z0-9_-]+") || !webhookFormat)
                throw new IllegalArgumentException();
            summary.put("openRouterKeyHttpStatus", getStatus(URI.create("https://openrouter.ai/api/v1/key"), actualKey));
            summary.put("discordWebhookHttpStatus", getStatus(URI.create(webhook), null));
            summary.put("externalCheck", "GET metadata only; no completions or Discord messages");
        }
        System.out.println(SecurityJson.write(summary));
    }

    private static int getStatus(URI uri, String key) {
        try (var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()) {
            var request = java.net.http.HttpRequest.newBuilder(uri).GET()
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", "TachyonScript-config-validation/0.5.1");
            if (key != null) request.header("Authorization", "Bearer " + key);
            var response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception ignored) {
            return -1;
        }
    }
}
