package com.forgebook.integration.websearch;

import com.forgebook.tool.ToolException;
import com.forgebook.util.UnsafeUrlException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for DuckDuckGoHtmlAdapter (Plan 02-05, Task 2 RED).
 * Uses the HttpFetch functional interface seam — no live network, no subclassing of final SafeHttpFetcher.
 */
class DuckDuckGoHtmlAdapterTest {

    private String loadFixture(String name) {
        try (InputStream is = getClass().getResourceAsStream("/forgebook/phase2/websearch/" + name)) {
            if (is == null) throw new IllegalStateException("Fixture not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Creates an adapter that returns fixed HTML for any URL (no network). */
    private DuckDuckGoHtmlAdapter fixtureAdapter(String html) {
        return new DuckDuckGoHtmlAdapter(uri -> html);
    }

    /** Creates an adapter that throws UnsafeUrlException for any URL. */
    private DuckDuckGoHtmlAdapter throwingAdapter(UnsafeUrlException.Reason reason) {
        return new DuckDuckGoHtmlAdapter(uri -> { throw new UnsafeUrlException(reason); });
    }

    /** Test 2: 7-result DDG HTML with limit=5 returns exactly 5 results. */
    @Test
    void search_withLimit5_returns5Results() throws ToolException {
        String html = loadFixture("ddg-results.html");
        DuckDuckGoHtmlAdapter adapter = fixtureAdapter(html);

        List<SearchResult> results = adapter.search("examplemod minecraft", 5);

        assertEquals(5, results.size(), "Should return exactly 5 results when limit=5");
    }

    /** Test 3: Each result has non-empty title and url (snippet may be empty but non-null). */
    @Test
    void search_results_haveNonEmptyFields() throws ToolException {
        String html = loadFixture("ddg-results.html");
        DuckDuckGoHtmlAdapter adapter = fixtureAdapter(html);

        List<SearchResult> results = adapter.search("examplemod", 7);

        for (SearchResult result : results) {
            assertFalse(result.title().isBlank(), "Title should not be blank");
            assertFalse(result.url().isBlank(), "URL should not be blank");
            assertNotNull(result.snippet(), "Snippet should not be null");
        }
    }

    /** Test 4: DDG redirect URLs are decoded to the real target URL. */
    @Test
    void search_ddgRedirectUrls_areDecoded() throws ToolException {
        String html = loadFixture("ddg-results.html");
        DuckDuckGoHtmlAdapter adapter = fixtureAdapter(html);

        List<SearchResult> results = adapter.search("examplemod", 7);

        // Fixture has 2 DDG redirect URLs:
        //   uddg=https%3A%2F%2Fwiki.example.com%2FExampleMod
        //   uddg=https%3A%2F%2Fgithub.com%2Fexample%2Fexamplemod
        boolean hasWikiDecoded = results.stream()
            .anyMatch(r -> r.url().equals("https://wiki.example.com/ExampleMod"));
        boolean hasGithubDecoded = results.stream()
            .anyMatch(r -> r.url().equals("https://github.com/example/examplemod"));

        assertTrue(hasWikiDecoded, "DDG redirect URL for wiki should be decoded to real URL");
        assertTrue(hasGithubDecoded, "DDG redirect URL for github should be decoded to real URL");
    }

    /** Test 5: When fetch throws UnsafeUrlException, search throws ToolException(FETCH_FAILED). */
    @Test
    void search_unsafeUrl_throwsFetchFailed() {
        DuckDuckGoHtmlAdapter adapter = throwingAdapter(UnsafeUrlException.Reason.SCHEME);

        ToolException ex = assertThrows(ToolException.class,
            () -> adapter.search("test", 5));

        assertEquals(ToolException.Reason.FETCH_FAILED, ex.reason(),
            "UnsafeUrlException should map to FETCH_FAILED");
        assertTrue(ex.getMessage().contains("SCHEME") || ex.getMessage().contains("ddg"),
            "Exception message should reference the underlying reason or ddg context");
    }
}
