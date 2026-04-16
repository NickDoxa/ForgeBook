package com.forgebook.util.log;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ApiKeyScrubFilterTest {

    @Test void redacts_Authorization_header() {
        assertEquals("Authorization: <redacted>",
            ApiKeyScrubFilter.scrub("Authorization: Bearer abc.def.ghi"));
    }

    @Test void redacts_x_api_key_header() {
        assertEquals("x-api-key: <redacted>",
            ApiKeyScrubFilter.scrub("x-api-key: sk-ant-real-value"));
    }

    @Test void redacts_sk_ant_prefix_anywhere_in_string() {
        assertEquals("token=sk-ant-<redacted> extra",
            ApiKeyScrubFilter.scrub("token=sk-ant-abcDEF_123-xyz extra"));
    }

    @Test void redacts_sk_proj_prefix_anywhere_in_string() {
        assertEquals("see sk-proj-<redacted> here",
            ApiKeyScrubFilter.scrub("see sk-proj-X9_y-zZ here"));
    }

    @Test void redacts_api_key_query_param() {
        assertEquals("https://host/path?api_key=<redacted>&other=1",
            ApiKeyScrubFilter.scrub("https://host/path?api_key=real-key-here&other=1"));
    }

    @Test void nonMatchingMessage_isUnchanged() {
        String in = "nothing sensitive here";
        assertEquals(in, ApiKeyScrubFilter.scrub(in));
    }

    @Test void null_returnsNull() {
        assertNull(ApiKeyScrubFilter.scrub(null));
    }

    @Test void combinedMessage_redactsAllDistinctPatterns() {
        String in = "call with Authorization: Bearer X and body api_key=Y to https sk-ant-Z1 also sk-proj-W2";
        String out = ApiKeyScrubFilter.scrub(in);
        assertFalse(out.contains("Bearer X"), out);
        assertFalse(out.contains("api_key=Y"), out);
        assertFalse(out.contains("sk-ant-Z1"), out);
        assertFalse(out.contains("sk-proj-W2"), out);
        assertTrue(out.contains("<redacted>"));
    }

    // --- Phase 2: X-Subscription-Token (Brave Search API) scrub rules ---

    @Test void redacts_X_Subscription_Token_header_value() {
        // Test 1: Brave token in canonical header form is redacted
        String result = ApiKeyScrubFilter.scrub("X-Subscription-Token: BSAabc123def456");
        assertFalse(result.contains("BSAabc123def456"), result);
        assertTrue(result.contains("<redacted>"), result);
    }

    @Test void regression_Authorization_still_scrubbed() {
        // Test 2: existing Authorization rule not weakened
        assertEquals("Authorization: <redacted>",
            ApiKeyScrubFilter.scrub("Authorization: Bearer abc.def.ghi"));
    }

    @Test void regression_x_api_key_still_scrubbed() {
        // Test 3: existing x-api-key rule not weakened
        assertEquals("x-api-key: <redacted>",
            ApiKeyScrubFilter.scrub("x-api-key: sk-ant-xxx"));
    }

    @Test void regression_sk_ant_prefix_still_scrubbed() {
        // Test 4: existing sk-ant-* substring rule not weakened
        assertEquals("token=sk-ant-<redacted> extra",
            ApiKeyScrubFilter.scrub("token=sk-ant-abcDEF_123-xyz extra"));
    }

    @Test void redacts_x_subscription_token_lowercase() {
        // Test 5: case-insensitive — lowercase header name also scrubbed
        String result = ApiKeyScrubFilter.scrub("x-subscription-token: some-brave-token-value");
        assertFalse(result.contains("some-brave-token-value"), result);
        assertTrue(result.contains("<redacted>"), result);
    }
}
