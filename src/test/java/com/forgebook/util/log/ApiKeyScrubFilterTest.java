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
}
