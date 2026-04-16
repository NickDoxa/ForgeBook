package com.forgebook.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ForgebookServerConfig Phase 2 additions.
 * These tests trigger the static initializer by referencing ForgebookServerConfig fields
 * and verify default values and enum structure.
 *
 * Note: ForgeConfigSpec defaults are accessible via getDefault() without a loaded config file.
 * The .get() calls rely on ForgeConfigSpec returning defaults when no TOML is loaded.
 */
class ForgebookServerConfigTest {

    // Touch the class to ensure static initializer has run.
    private static final Object INIT = ForgebookServerConfig.SPEC;

    @Test
    void maxTokens_defaultIs1024() {
        // Verify default value - accessible without a loaded config file
        assertEquals(1024, ForgebookServerConfig.MAX_TOKENS.getDefault());
    }

    @Test
    void maxTokens_rangeEnforcedByDefineInRange() {
        // The defineInRange call with (1024, 128, 8192) ensures this is configured
        // We verify the default is within range
        int def = ForgebookServerConfig.MAX_TOKENS.getDefault();
        assertTrue(def >= 128, "max_tokens default must be >= 128, got: " + def);
        assertTrue(def <= 8192, "max_tokens default must be <= 8192, got: " + def);
    }

    @Test
    void webSearchProvider_defaultIsDuckDuckGo() {
        assertEquals(WebSearchProviderKind.DUCKDUCKGO,
            ForgebookServerConfig.WEB_SEARCH_PROVIDER.getDefault());
    }

    @Test
    void webSearchApiKey_defaultIsEmpty() {
        assertEquals("", ForgebookServerConfig.WEB_SEARCH_API_KEY.getDefault());
    }

    @Test
    void aiModel_defaultIsClaude_haiku_4_5() {
        assertEquals("claude-haiku-4-5", ForgebookServerConfig.AI_MODEL.getDefault());
    }

    @Test
    void webSearchProviderKind_valuesAreExactlyDuckDuckGoAndBrave() {
        WebSearchProviderKind[] values = WebSearchProviderKind.values();
        assertEquals(2, values.length, "WebSearchProviderKind must have exactly 2 values");
        assertEquals(WebSearchProviderKind.DUCKDUCKGO, values[0]);
        assertEquals(WebSearchProviderKind.BRAVE, values[1]);
    }
}
