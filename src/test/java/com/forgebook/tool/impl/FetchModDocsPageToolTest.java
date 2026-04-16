package com.forgebook.tool.impl;

import com.forgebook.integration.scraper.PromptFraming;
import com.forgebook.tool.ToolException;
import com.forgebook.util.SafeHttpFetcher;
import com.forgebook.util.UnsafeUrlException;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for FetchModDocsPageTool (Plan 02-05, Task 3 RED).
 * Uses the FetchModDocsPageTool.FetchFn functional seam — no live network.
 */
class FetchModDocsPageToolTest {

    private static final String TEST_URL = "https://wiki.example.com/mod";

    /** Creates a tool that returns fixed body HTML. */
    private FetchModDocsPageTool toolReturning(String htmlBody) {
        return new FetchModDocsPageTool(uri -> new SafeHttpFetcher.Result(htmlBody, "text/html", uri));
    }

    /** Creates a tool that throws UnsafeUrlException. */
    private FetchModDocsPageTool toolThrowingUnsafe(UnsafeUrlException.Reason reason) {
        return new FetchModDocsPageTool(uri -> { throw new UnsafeUrlException(reason); });
    }

    /** Creates a tool that throws IOException. */
    private FetchModDocsPageTool toolThrowingIO() {
        return new FetchModDocsPageTool(uri -> { throw new IOException("connection reset"); });
    }

    /** Test 7: name() == "fetch_mod_docs_page". */
    @Test
    void name_isCorrect() {
        assertEquals("fetch_mod_docs_page", toolReturning("").name());
    }

    /** Test 8: schema() requires {url: string}. */
    @Test
    void schema_requiresUrl() {
        JsonObject schema = toolReturning("").schema();
        assertEquals("object", schema.get("type").getAsString());
        assertTrue(schema.has("properties"));
        assertTrue(schema.getAsJsonObject("properties").has("url"));
        var required = schema.getAsJsonArray("required");
        boolean urlRequired = false;
        for (var el : required) { if ("url".equals(el.getAsString())) urlRequired = true; }
        assertTrue(urlRequired, "url should be in required array");
    }

    /** Test 9: invoke with valid URL and HTML returns framed mod_doc string. */
    @Test
    void invoke_validUrl_returnsFramedOutput() throws ToolException {
        // HTML with an article element containing readable text
        String html = "<html><body><article>" +
            "This mod adds many useful features for automation and tech progression " +
            "in Minecraft. Players can build complex machines that interact with the " +
            "environment in interesting ways, providing new gameplay possibilities.</article></body></html>";

        JsonObject input = new JsonObject();
        input.addProperty("url", TEST_URL);

        String result = toolReturning(html).invoke(input);
        assertTrue(result.startsWith("<mod_doc trust=\"untrusted\" source=\"" + TEST_URL + "\" tag=\""),
            "Result should start with mod_doc framing: " + result.substring(0, Math.min(result.length(), 80)));
    }

    /** Test 10: invoke with empty URL throws ToolException(NO_DOCS_URL). */
    @Test
    void invoke_emptyUrl_throwsNoDocsUrl() {
        JsonObject input = new JsonObject();
        input.addProperty("url", "");

        ToolException ex = assertThrows(ToolException.class,
            () -> toolReturning("").invoke(input));
        assertEquals(ToolException.Reason.NO_DOCS_URL, ex.reason());
    }

    /** Test 11: invoke with blank URL ("   ") throws ToolException(NO_DOCS_URL). */
    @Test
    void invoke_blankUrl_throwsNoDocsUrl() {
        JsonObject input = new JsonObject();
        input.addProperty("url", "   ");

        ToolException ex = assertThrows(ToolException.class,
            () -> toolReturning("").invoke(input));
        assertEquals(ToolException.Reason.NO_DOCS_URL, ex.reason());
    }

    /** Test 12: When fetcher throws UnsafeUrlException, throws ToolException(FETCH_FAILED). */
    @Test
    void invoke_unsafeUrl_throwsFetchFailed() {
        JsonObject input = new JsonObject();
        input.addProperty("url", TEST_URL);

        ToolException ex = assertThrows(ToolException.class,
            () -> toolThrowingUnsafe(UnsafeUrlException.Reason.PRIVATE_IP).invoke(input));
        assertEquals(ToolException.Reason.FETCH_FAILED, ex.reason());
        assertTrue(ex.getMessage().contains("PRIVATE_IP") || ex.getMessage().contains("url="),
            "Message should mention reason or url");
    }

    /** Test 13: When fetcher throws IOException, throws ToolException(UPSTREAM_TIMEOUT). */
    @Test
    void invoke_ioException_throwsUpstreamTimeout() {
        JsonObject input = new JsonObject();
        input.addProperty("url", TEST_URL);

        ToolException ex = assertThrows(ToolException.class,
            () -> toolThrowingIO().invoke(input));
        assertEquals(ToolException.Reason.UPSTREAM_TIMEOUT, ex.reason());
    }

    /** Test 14: 9000-char page is truncated to 8000 chars + marker via PromptFraming. */
    @Test
    void invoke_longPage_isTruncated() throws ToolException {
        // Large body that, after extraction, will be > 8000 chars
        // Use minimal-body pattern so the full body text is > 8000 chars
        String longText = "T".repeat(9_000);
        String html = "<html><body>" + longText + "</body></html>";

        JsonObject input = new JsonObject();
        input.addProperty("url", TEST_URL);

        String result = toolReturning(html).invoke(input);
        // Result is framed so it's longer than raw text, but the inner body should be capped
        assertTrue(result.contains("[... truncated at 8,000 chars — full document at " + TEST_URL + "]"),
            "Long pages should include truncation marker");
        // The inner content should not exceed TOOL_OUTPUT_CAP substantially
        int capIdx = result.indexOf("[... truncated");
        assertTrue(capIdx > 0, "Should have truncation marker");
    }
}
