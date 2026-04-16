package com.forgebook.integration.scraper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for ModDocsScraper (Plan 02-05, Task 1 RED).
 * Tests all 7 behaviors from the plan.
 */
class ModDocsScraperTest {

    private String loadFixture(String name) {
        try (InputStream is = getClass().getResourceAsStream(
                "/forgebook/phase2/scraper/" + name)) {
            if (is == null) throw new IllegalStateException("Fixture not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Test 1: CurseForge page uses div.project-description selector. */
    @Test
    void extractReadable_curseforge_returnsProjectDescription() {
        String html = loadFixture("curseforge-page.html");
        String result = ModDocsScraper.extractReadable(html, "https://example.com");

        assertTrue(result.contains("GreatMod adds a sprawling tech tree"),
                "Should contain CurseForge project description text");
        assertFalse(result.contains("Main Menu"), "Should not contain nav text");
        assertFalse(result.contains("Buy Gold"), "Should not contain advertisement text");
        assertFalse(result.contains("All rights reserved"), "Should not contain footer text");
    }

    /** Test 2: Fandom page uses div#mw-content-text selector. */
    @Test
    void extractReadable_fandom_returnsMwContentText() {
        String html = loadFixture("fandom-page.html");
        String result = ModDocsScraper.extractReadable(html, "https://example.com");

        assertTrue(result.contains("Diamond Pickaxe is crafted from"),
                "Should contain Fandom wiki content text");
        assertFalse(result.contains("Navigation Menu"), "Should not contain nav text");
        assertFalse(result.contains("tracking code"), "Should not contain script content");
        assertFalse(result.contains("Wiki navigation sidebar"), "Should not contain sidebar text");
    }

    /** Test 3: Read the Docs page uses article selector. */
    @Test
    void extractReadable_readTheDocs_returnsArticleContent() {
        String html = loadFixture("rtd-page.html");
        String result = ModDocsScraper.extractReadable(html, "https://example.com");

        assertTrue(result.contains("Configuration is read from"),
                "Should contain RTD article text");
        assertFalse(result.contains("Read the Docs Navigation"), "Should not contain nav text");
        assertFalse(result.contains("built with Sphinx"), "Should not contain footer text");
    }

    /** Test 4: Minimal body page falls back to body selector. */
    @Test
    void extractReadable_minimalBody_returnsBodyText() {
        String html = loadFixture("minimal-body.html");
        String result = ModDocsScraper.extractReadable(html, "https://example.com");

        assertTrue(result.contains("Bare body content"),
                "Should contain minimal body text via body fallback");
    }

    /** Test 5: Largest-text div fallback for unrecognized structure. */
    @Test
    void extractReadable_largestDivFallback_returnsLargestDiv() {
        // HTML with no semantic wrappers but a large div with 250+ chars
        String largeText = "A".repeat(260);
        String smallText = "B".repeat(50);
        String html = "<html><body><div>" + smallText + "</div><div>" + largeText +
                "</div></body></html>";

        String result = ModDocsScraper.extractReadable(html, "https://example.com");

        // The result should contain the large text content
        assertTrue(result.contains("AAAA"), "Should return the largest div content");
    }

    /** Test 6: Denoised content excludes script, style, nav, footer, aside, .advertisement. */
    @Test
    void extractReadable_denoising_excludesNoisyElements() {
        String html = "<html><body>" +
                "<nav>nav content</nav>" +
                "<footer>footer content</footer>" +
                "<aside>aside content</aside>" +
                "<script>script content alert</script>" +
                "<style>.ad { color: red; }</style>" +
                "<div class=\"advertisement\">ad content</div>" +
                "<article>Main article content with more than two hundred characters of actual useful " +
                "information about what this article is about and what it contains.</article>" +
                "</body></html>";

        String result = ModDocsScraper.extractReadable(html, "https://example.com");

        assertFalse(result.contains("nav content"), "Should not contain nav text");
        assertFalse(result.contains("footer content"), "Should not contain footer text");
        assertFalse(result.contains("aside content"), "Should not contain aside text");
        assertFalse(result.contains("script content"), "Should not contain script text");
        assertFalse(result.contains(".ad"), "Should not contain style text");
        assertFalse(result.contains("ad content"), "Should not contain advertisement text");
        assertTrue(result.contains("Main article content"), "Should contain article text");
    }

    /** Test 7: Empty HTML returns empty string with no NPE. */
    @Test
    void extractReadable_emptyHtml_returnsEmpty() {
        String result = ModDocsScraper.extractReadable("", "https://example.com");
        assertNotNull(result, "Should not return null");
        // empty or near-empty is acceptable — just no exception
    }
}
