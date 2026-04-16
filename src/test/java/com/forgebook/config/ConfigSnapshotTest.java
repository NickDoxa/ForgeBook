package com.forgebook.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigSnapshotTest {

    /** Helper to build a fully-populated 12-field snapshot for tests. */
    private static ConfigSnapshot makeSnapshot(
            AiProviderKind provider, ApiKey aiKey, String model,
            int maxTokens, Optional<String> modpackId, ApiKey cfKey,
            boolean opOnly, int rateLimit, boolean webSearch,
            WebSearchProviderKind wsProvider, ApiKey wsKey, int version) {
        return new ConfigSnapshot(
            provider, aiKey, model, maxTokens, modpackId, cfKey,
            opOnly, rateLimit, webSearch, wsProvider, wsKey, version);
    }

    @Test
    void record_preservesAllTwelveFields() {
        ConfigSnapshot s = makeSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("sk-ant-x"),
            "claude-haiku-4-5",
            1024,
            Optional.of("modpack-123"),
            new ApiKey("cf-y"),
            true, 5, false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey("ws-key"),
            1);

        assertEquals(AiProviderKind.ANTHROPIC, s.aiProvider());
        assertEquals("sk-ant-x", s.aiApiKey().raw());
        assertEquals("claude-haiku-4-5", s.aiModel());
        assertEquals(1024, s.maxTokens());
        assertEquals(Optional.of("modpack-123"), s.curseforgeModpackId());
        assertEquals("cf-y", s.curseforgeApiKey().raw());
        assertTrue(s.opOnly());
        assertEquals(5, s.rateLimitPerMinute());
        assertFalse(s.enableWebSearch());
        assertEquals(WebSearchProviderKind.DUCKDUCKGO, s.webSearchProvider());
        assertEquals("ws-key", s.webSearchApiKey().raw());
        assertEquals(1, s.configVersion());
    }

    @Test
    void toString_doesNotLeakApiKey_becauseApiKey_toString_redacts() {
        ConfigSnapshot s = makeSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("sk-ant-LEAK"),
            "m", 1024, Optional.empty(), new ApiKey("cf-LEAK"),
            true, 5, false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey("ws-LEAK"),
            1);
        String rendered = s.toString();
        assertFalse(rendered.contains("sk-ant-LEAK"), rendered);
        assertFalse(rendered.contains("cf-LEAK"), rendered);
        assertFalse(rendered.contains("ws-LEAK"), rendered);
        assertTrue(rendered.contains("<redacted>"));
    }

    @Test
    void holder_buildFromSpec_maxTokensEqualsDefault() {
        // buildFromSpec reads from the live ForgeConfigSpec — defaults apply when no TOML loaded
        ConfigSnapshot snap = ConfigHolder.buildFromSpec();
        // MAX_TOKENS default is 1024 per D-05
        assertEquals(1024, snap.maxTokens());
    }

    @Test
    void holder_buildFromSpec_webSearchProviderIsDefault() {
        ConfigSnapshot snap = ConfigHolder.buildFromSpec();
        // WEB_SEARCH_PROVIDER default is DUCKDUCKGO per D-01/D-02
        assertEquals(WebSearchProviderKind.DUCKDUCKGO, snap.webSearchProvider());
    }

    @Test
    void holder_buildFromSpec_webSearchApiKeyIsEmpty() {
        ConfigSnapshot snap = ConfigHolder.buildFromSpec();
        // WEB_SEARCH_API_KEY default is "" per spec definition
        assertEquals("", snap.webSearchApiKey().raw());
    }

    @Test
    void holder_isVolatile_singleRefSwap_regression() {
        // Regression: the 9 original fields still populate correctly after 12-field extension
        ConfigSnapshot a = makeSnapshot(
            AiProviderKind.ANTHROPIC, new ApiKey("1"), "m",
            512, Optional.empty(), new ApiKey("2"),
            true, 5, false,
            WebSearchProviderKind.DUCKDUCKGO, new ApiKey(""), 1);
        ConfigSnapshot b = makeSnapshot(
            AiProviderKind.OPENAI, new ApiKey("3"), "m2",
            2048, Optional.empty(), new ApiKey("4"),
            false, 10, true,
            WebSearchProviderKind.BRAVE, new ApiKey("brave-key"), 1);

        ConfigHolder.set(a);
        assertSame(a, ConfigHolder.get());
        assertEquals(AiProviderKind.ANTHROPIC, ConfigHolder.get().aiProvider());
        assertEquals(512, ConfigHolder.get().maxTokens());
        assertFalse(ConfigHolder.get().enableWebSearch());

        ConfigHolder.set(b);
        assertSame(b, ConfigHolder.get());
        assertEquals(AiProviderKind.OPENAI, ConfigHolder.get().aiProvider());
        assertEquals(2048, ConfigHolder.get().maxTokens());
        assertTrue(ConfigHolder.get().enableWebSearch());
        assertEquals(WebSearchProviderKind.BRAVE, ConfigHolder.get().webSearchProvider());
    }
}
