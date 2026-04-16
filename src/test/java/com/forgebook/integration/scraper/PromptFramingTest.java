package com.forgebook.integration.scraper;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for PromptFraming (Plan 02-05, Task 1 RED).
 * Tests all 5 behaviors from the plan.
 */
class PromptFramingTest {

    private static final String SOURCE_URL = "https://example.com";
    private static final Pattern NONCE_PATTERN = Pattern.compile("tag=\"([a-f0-9]{8})\"");

    /** Test 8: Opening tag has correct structure with 8-char nonce. */
    @Test
    void wrap_openingTagStructure_correctFormat() {
        String result = PromptFraming.wrap("some text", SOURCE_URL);

        assertTrue(result.startsWith("<mod_doc trust=\"untrusted\" source=\"https://example.com\" tag=\""),
                "Should start with correct mod_doc opening tag");

        Matcher m = NONCE_PATTERN.matcher(result);
        assertTrue(m.find(), "Should contain an 8-char hex nonce");
        String nonce = m.group(1);
        assertEquals(8, nonce.length(), "Nonce should be 8 characters");
        assertTrue(nonce.matches("[a-f0-9]{8}"), "Nonce should be 8 hex chars: " + nonce);
    }

    /** Test 9: Closing tag contains same nonce as opening tag. */
    @Test
    void wrap_closingTagSameNonce_closureProtection() {
        String result = PromptFraming.wrap("some text content", SOURCE_URL);

        Matcher m = NONCE_PATTERN.matcher(result);
        assertTrue(m.find(), "Should find nonce in opening tag");
        String openNonce = m.group(1);

        assertTrue(m.find(), "Should find nonce in closing tag");
        String closeNonce = m.group(1);

        assertEquals(openNonce, closeNonce,
                "Opening and closing nonces should match for closure protection");

        assertTrue(result.contains("</mod_doc tag=\"" + openNonce + "\">"),
                "Closing tag should use the same nonce");
    }

    /** Test 10: Short text (<=8000 chars) is not truncated. */
    @Test
    void wrap_shortText_noTruncation() {
        String text = "Short text content that is well within the 8000 char limit.";
        String result = PromptFraming.wrap(text, SOURCE_URL);

        assertTrue(result.contains(text), "Short text should appear verbatim in output");
        assertFalse(result.contains("truncated"), "Short text should not have truncation marker");
    }

    /** Test 11: Long text (>8000 chars) is truncated with the canonical marker. */
    @Test
    void wrap_longText_truncatedWithMarker() {
        String text = "X".repeat(10_000);
        String result = PromptFraming.wrap(text, SOURCE_URL);

        assertTrue(result.contains("X".repeat(8_000)), "First 8000 chars should be present");
        assertTrue(result.contains("[... truncated at 8,000 chars — full document at " + SOURCE_URL + "]"),
                "Should contain canonical truncation marker");
        // Ensure character 8001+ is not there (no extra X beyond what's allowed)
        assertFalse(result.contains("X".repeat(8_001)),
                "Should not contain more than 8000 X chars");
    }

    /** Test 12: TOOL_OUTPUT_CAP constant equals 8000. */
    @Test
    void toolOutputCap_equals8000() {
        assertEquals(8_000, PromptFraming.TOOL_OUTPUT_CAP,
                "TOOL_OUTPUT_CAP must equal 8000 (D-14/D-15)");
    }
}
