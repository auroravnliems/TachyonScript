package dev.tachyonscript.plugin;

import dev.tachyonscript.security.SecurityJson;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Read-only validation of the installed config with the real plugin settings loader. */
public final class SecurityConfigurationProbe {
    private SecurityConfigurationProbe() { }
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 2) throw new IllegalArgumentException("Expected config path and optional report path");
        var config = new YamlConfiguration();
        config.load(new File(arguments[0]));
        var options = SecuritySettings.from(config);
        if (!options.enabled() || !options.ai().enabled() || !options.ai().required() || !options.discord().enabled()
                || !options.ai().model().equals("qwen/qwen3-coder")
                || !options.ai().apiKey().equals(System.getenv("OPENROUTER_API_KEY"))
                || !options.discord().webhook().equals(System.getenv("TACHYON_SECURITY_DISCORD_WEBHOOK"))) {
            throw new IllegalStateException("Installed security configuration did not load as expected; values withheld");
        }
        String report = SecurityJson.write(Map.of("configurationValidated", true, "qwenEnabled", true,
                "discordEnabled", true, "model", options.ai().model(), "required", options.ai().required(),
                "maxOutputTokens", options.ai().maxOutputTokens(), "readTimeoutMs", options.ai().readTimeout().toMillis(),
                "configPath", Path.of(arguments[0]).toAbsolutePath().toString(),
                "configSha256", hash(Path.of(arguments[0])),
                "pluginSha256", hash(Path.of(SecuritySettings.class.getProtectionDomain().getCodeSource().getLocation().toURI()))));
        if (arguments.length == 2) {
            Path destination = Path.of(arguments[1]);
            Files.createDirectories(destination.toAbsolutePath().getParent());
            Files.writeString(destination, report, StandardCharsets.UTF_8);
        }
        System.out.println(report);
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
