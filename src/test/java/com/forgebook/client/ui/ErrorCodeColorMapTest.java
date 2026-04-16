package com.forgebook.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * Locks the UI-SPEC §"Phase-3 Error Taxonomy ⇢ UI Mapping" table as source of truth.
 * Pattern mirrors AuthorizerTest.java:36-80 — exhaustive enum-branch coverage.
 */
class ErrorCodeColorMapTest {

    @Test
    void transport_amber() { assertEquals(0xFFF5A623, ErrorCard.stripeColor(ErrorCode.TRANSPORT)); }

    @Test
    void rate_limited_blue() { assertEquals(0xFF4A90E2, ErrorCard.stripeColor(ErrorCode.RATE_LIMITED)); }

    @Test
    void forbidden_red() { assertEquals(0xFFE74C3C, ErrorCard.stripeColor(ErrorCode.FORBIDDEN)); }

    @Test
    void provider_red() { assertEquals(0xFFE74C3C, ErrorCard.stripeColor(ErrorCode.PROVIDER)); }

    @Test
    void disabled_gray() { assertEquals(0xFF808080, ErrorCard.stripeColor(ErrorCode.DISABLED)); }

    @Test
    void overloaded_amber() { assertEquals(0xFFF5A623, ErrorCard.stripeColor(ErrorCode.OVERLOADED)); }

    @Test
    void everyErrorCode_hasNonZeroColor_andStructuredI18nKeys() {
        for (ErrorCode code : ErrorCode.values()) {
            assertNotEquals(0, ErrorCard.stripeColor(code), "ErrorCode " + code + " must have non-zero ARGB stripe");
            assertTrue(ErrorCard.headingKey(code).startsWith("forgebook.error."),
                "Heading key for " + code + " must start with forgebook.error.");
            assertTrue(ErrorCard.bodyKey(code).startsWith("forgebook.error."),
                "Body key for " + code + " must start with forgebook.error.");
        }
    }
}
