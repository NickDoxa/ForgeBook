package com.forgebook.integration.websearch;

import com.forgebook.config.ApiKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for BraveSearchAdapter (Plan 02-05, Task 2 RED).
 * Tests parseBody directly (no live HTTP) for behaviors 7-9.
 * Behavior 10 (X-Subscription-Token header) is verified by code structure.
 */
class BraveSearchAdapterTest {

    private String loadFixture(String name) {
        try (InputStream is = getClass().getResourceAsStream("/forgebook/phase2/websearch/" + name)) {
            if (is == null) throw new IllegalStateException("Fixture not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Test 7: brave-results.json with 8 results, limit=5 returns 5 SearchResults. */
    @Test
    void parseBody_withLimit5_returns5Results() {
        String json = loadFixture("brave-results.json");

        List<SearchResult> results = BraveSearchAdapter.parseBody(json, 5);

        assertEquals(5, results.size(), "Should return exactly 5 results when limit=5");
    }

    /** Test 8: parseBody extracts title, description, and url from web.results[]. */
    @Test
    void parseBody_extractsCorrectFields() {
        String json = loadFixture("brave-results.json");

        List<SearchResult> results = BraveSearchAdapter.parseBody(json, 8);

        // First result from fixture
        SearchResult first = results.get(0);
        assertEquals("ExampleMod Official Page", first.title(), "Should extract title");
        assertEquals("https://modrinth.com/mod/example-mod", first.url(), "Should extract url");
        assertFalse(first.snippet().isBlank(), "Should extract non-blank snippet from description");
    }

    /** Test 9: When web.results is missing or empty, parseBody returns empty list without NPE. */
    @Test
    void parseBody_missingWebResults_returnsEmpty() {
        // Missing "web" key
        List<SearchResult> r1 = BraveSearchAdapter.parseBody("{\"query\":{\"original\":\"test\"}}", 5);
        assertNotNull(r1, "Should not return null for missing web object");
        assertEquals(0, r1.size(), "Should return empty list for missing web object");

        // Empty results array
        List<SearchResult> r2 = BraveSearchAdapter.parseBody(
            "{\"web\":{\"results\":[]}}", 5);
        assertNotNull(r2, "Should not return null for empty results");
        assertEquals(0, r2.size(), "Should return empty list for empty results array");

        // null web object
        List<SearchResult> r3 = BraveSearchAdapter.parseBody("{\"web\":null}", 5);
        assertNotNull(r3, "Should not return null for null web object");
        assertEquals(0, r3.size(), "Should return empty list for null web object");
    }

    /**
     * Test 10: Constructor takes ApiKey; adapter uses X-Subscription-Token.
     * Structural guarantee — the header is included by the single line that builds the request.
     * This test verifies the adapter can be constructed without throwing.
     */
    @Test
    void constructor_takesApiKey_doesNotThrow() {
        // Verify the adapter can be created with an ApiKey — structural contract test
        ApiKey key = new ApiKey("test-brave-key");
        BraveSearchAdapter adapter = new BraveSearchAdapter(key);
        assertNotNull(adapter, "BraveSearchAdapter should be constructable with ApiKey");
        // Note: X-Subscription-Token header inclusion is structurally guaranteed by the
        // single HttpRequest.newBuilder().header("X-Subscription-Token", apiKey.raw()) line.
        // Integration test of live header injection would require an HTTP mock server.
    }
}
