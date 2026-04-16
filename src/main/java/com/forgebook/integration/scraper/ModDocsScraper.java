package com.forgebook.integration.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Comparator;
import java.util.List;

/**
 * Readability extractor for mod documentation pages (D-16, RESEARCH §4).
 * Uses a 9-step selector chain with denoise pre-processing.
 *
 * All methods are static — utility class, not instantiable.
 */
public final class ModDocsScraper {

    /**
     * Ordered CSS selectors tried in sequence (RESEARCH §4.1 + §4.4 D-16 verbatim).
     * First non-empty match with > 200 chars wins.
     * Largest-text-div fallback is handled inline between markdown-body and div.content.
     */
    private static final List<String> READABILITY_SELECTORS = List.of(
        "article",               // 1. Semantic <article> (Read the Docs, modern wikis)
        "main",                  // 2. Semantic <main>
        "div.project-description", // 3. CurseForge project-page specific
        "div#mw-content-text",   // 4. Fandom / MediaWiki wikis
        "div#wiki-body",         // 5. GitHub wikis
        "div.markdown-body",     // 6. GitHub rendered markdown
        // Step 7: largest-text div fallback (handled inline — see extractReadable)
        "div.content",           // 8. Generic content div
        "body"                   // 9. Last-resort full body
    );

    private ModDocsScraper() {}

    /**
     * Extract the most readable text from the given HTML page.
     * Applies denoise first, then tries each selector in order.
     * Falls back to the largest-text div if no selector matches > 200 chars,
     * then to body, then to empty string.
     *
     * @param html      raw HTML string (may be empty or malformed)
     * @param sourceUrl source URL for logging context (not used in extraction)
     * @return extracted readable text, whitespace-collapsed; empty string if nothing found
     */
    public static String extractReadable(String html, String sourceUrl) {
        if (html == null || html.isBlank()) return "";

        Document doc = Jsoup.parse(html);
        denoise(doc);

        // Try selectors 1-6 (article through markdown-body)
        List<String> prelargestSelectors = READABILITY_SELECTORS.subList(0, 6);
        for (String selector : prelargestSelectors) {
            Element e = doc.selectFirst(selector);
            if (e != null && e.text().length() > 200) {
                return e.text();
            }
        }

        // Step 7: largest-text div fallback (D-16 verbatim)
        Element largest = doc.select("div").stream()
            .max(Comparator.comparingInt(d -> d.text().length()))
            .orElse(null);
        if (largest != null && largest.text().length() > 200) {
            return largest.text();
        }

        // Selectors 8-9 (div.content and body)
        List<String> postlargestSelectors = READABILITY_SELECTORS.subList(6, READABILITY_SELECTORS.size());
        for (String selector : postlargestSelectors) {
            Element e = doc.selectFirst(selector);
            if (e != null && e.text().length() > 200) {
                return e.text();
            }
        }

        // Final fallback
        return doc.body() == null ? "" : doc.body().text();
    }

    /**
     * Remove noisy elements from the document before text extraction (RESEARCH §4.2).
     */
    private static void denoise(Document doc) {
        doc.select("nav, footer, aside, script, style, noscript, " +
                   ".sidebar, .navigation, .advertisement, .ad, .cookie-banner, " +
                   "form, header[role=banner]").remove();
    }
}
