package dev.tachyonscript.plugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecuritySettingsTest {
    @Test void defaultsEnableDeterministicGateWithoutInventingAiCredentials() {
        var options = SecuritySettings.from(new YamlConfiguration(), ignored -> null);
        assertTrue(options.enabled()); assertFalse(options.ai().enabled()); assertFalse(options.discord().enabled());
        assertEquals(4096, options.ai().maxOutputTokens());
        assertFalse(options.ai().required());
    }
    @Test void legacyRequiredDoesNotTurnProviderOutagesIntoAServerWideStop() {
        var config = new YamlConfiguration();
        config.set("security.ai.required", true);
        assertFalse(SecuritySettings.from(config).ai().required());
        config.set("security.ai.failure-policy", "keep-pending");
        assertTrue(SecuritySettings.from(config).ai().required());
        config.set("security.ai.failure-policy", "warn");
        assertFalse(SecuritySettings.from(config).ai().required());
        config.set("security.ai.failure-policy", "typo");
        assertThrows(IllegalArgumentException.class, () -> SecuritySettings.from(config));
    }
    @Test void configuredProviderExpandsEnvironmentAndRedactsAllSecretBearingViews() {
        var config = new YamlConfiguration();
        config.set("security.ai.enabled", true); config.set("security.ai.endpoint", "https://provider.example/chat/completions");
        config.set("security.ai.model", "configured-model"); config.set("security.ai.api-key", "${QWEN_API_KEY}");
        config.set("security.ai.max-output-tokens", 2048);
        var options = SecuritySettings.from(config, name -> "fake-secret-KeyOnlyForTests");
        assertEquals("fake-secret-KeyOnlyForTests", options.ai().apiKey());
        assertEquals(2048, options.ai().maxOutputTokens());
        assertFalse(options.toString().contains("fake-secret-KeyOnlyForTests"));
        assertFalse(options.redactor().redact("fake-secret-KeyOnlyForTests").contains("fake-secret-KeyOnlyForTests"));
        assertThrows(IllegalArgumentException.class, () -> SecuritySettings.from(config, ignored -> null));
    }
    @Test void invalidProviderConfigurationFailsClosedWithoutEchoingSecretValues() {
        var config = new YamlConfiguration(); config.set("security.ai.enabled", true);
        config.set("security.ai.endpoint", "https://key-is-secret@provider.example/chat"); config.set("security.ai.api-key", "secret-key");
        config.set("security.ai.model", "configured-model");
        var exception = assertThrows(IllegalArgumentException.class, () -> SecuritySettings.from(config));
        assertFalse(exception.getMessage().contains("secret-key")); assertFalse(exception.getMessage().contains("key-is-secret"));
    }
    @Test void reviewsRunInTheBackgroundUnlessBlockingIsChosen() {
        var config = new YamlConfiguration();
        var defaults = SecuritySettings.from(config, ignored -> null).ai();
        assertTrue(defaults.background()); assertEquals(2, defaults.maxConcurrentReviews());
        config.set("security.ai.review-mode", "blocking"); config.set("security.ai.max-concurrent-reviews", 4);
        var blocking = SecuritySettings.from(config, ignored -> null).ai();
        assertFalse(blocking.background()); assertEquals(4, blocking.maxConcurrentReviews());
        config.set("security.ai.max-concurrent-reviews", 9);
        assertThrows(IllegalArgumentException.class, () -> SecuritySettings.from(config, ignored -> null));
        config.set("security.ai.max-concurrent-reviews", 2); config.set("security.ai.review-mode", "eventually");
        assertThrows(IllegalArgumentException.class, () -> SecuritySettings.from(config, ignored -> null));
    }
    @Test void invalidCompletionBudgetsCannotBecomeProviderRequests() {
        var config = new YamlConfiguration();
        config.set("security.ai.max-output-tokens", 0);
        assertThrows(IllegalArgumentException.class, () -> SecuritySettings.from(config));
        config.set("security.ai.max-output-tokens", 65_537);
        assertThrows(IllegalArgumentException.class, () -> SecuritySettings.from(config));
    }
}
