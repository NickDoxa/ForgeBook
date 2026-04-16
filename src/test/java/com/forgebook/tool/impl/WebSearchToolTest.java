package com.forgebook.tool.impl;

import com.forgebook.config.*;
import com.forgebook.tool.ToolException;
import com.forgebook.integration.websearch.SearchResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WebSearchTool (Plan 02-05, Task 3 RED).
 * Uses ConfigHolder.set() to inject test snapshots without Forge.
 */
class WebSearchToolTest {

    private ConfigSnapshot savedSnap;

    @BeforeEach
    void saveConfig() {
        savedSnap = ConfigHolder.get();
    }

    @AfterEach
    void restoreConfig() {
        ConfigHolder.set(savedSnap);
    }

    private ConfigSnapshot snapWith(boolean enableWebSearch, WebSearchProviderKind provider) {
        return new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("test-key"),
            "claude-haiku-4-5",
            1024,
            Optional.empty(),
            new ApiKey(""),
            false,
            10,
            enableWebSearch,
            provider,
            new ApiKey("brave-key"),
            1
        );
    }

    /** Test 15: name() == "web_search". */
    @Test
    void name_isCorrect() {
        assertEquals("web_search", new WebSearchTool(null).name());
    }

    /** Test 16: schema() requires {query: string}, optional {limit: integer}. */
    @Test
    void schema_hasExpectedShape() {
        JsonObject schema = new WebSearchTool(null).schema();
        assertEquals("object", schema.get("type").getAsString());
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("query"), "Should have query property");
        assertEquals("string", props.getAsJsonObject("query").get("type").getAsString());
        assertTrue(props.has("limit"), "Should have limit property");
        assertEquals("integer", props.getAsJsonObject("limit").get("type").getAsString());
        // query is required
        var required = schema.getAsJsonArray("required");
        boolean queryRequired = false;
        for (var el : required) { if ("query".equals(el.getAsString())) queryRequired = true; }
        assertTrue(queryRequired, "query should be in required");
    }

    /** Test 17: When enableWebSearch==false, invoke throws ToolException(INVALID_INPUT). */
    @Test
    void invoke_webSearchDisabled_throwsInvalidInput() {
        ConfigHolder.set(snapWith(false, WebSearchProviderKind.DUCKDUCKGO));
        JsonObject input = new JsonObject();
        input.addProperty("query", "test query");

        ToolException ex = assertThrows(ToolException.class,
            () -> new WebSearchTool(null).invoke(input));
        assertEquals(ToolException.Reason.INVALID_INPUT, ex.reason());
        assertTrue(ex.getMessage().contains("disabled"), "Should mention 'disabled'");
    }

    /** Test 18: When provider=DUCKDUCKGO, tool delegates to DuckDuckGoHtmlAdapter. */
    @Test
    void invoke_duckduckgoProvider_usesCorrectAdapter() throws ToolException {
        // Stub fetcher that returns known HTML with 3 results
        String html = "<html><body><div class=\"results\">" +
            "<div class=\"results_links\"><div class=\"links_main\">" +
            "<h2><a class=\"result__a\" href=\"https://r1.example.com\">Result 1</a></h2>" +
            "<a class=\"result__snippet\">Snippet 1</a></div></div>" +
            "<div class=\"results_links\"><div class=\"links_main\">" +
            "<h2><a class=\"result__a\" href=\"https://r2.example.com\">Result 2</a></h2>" +
            "<a class=\"result__snippet\">Snippet 2</a></div></div>" +
            "</div></body></html>";

        // Use the HttpFetch seam of DuckDuckGoHtmlAdapter via WebSearchTool's fetcher seam
        // WebSearchTool constructs DuckDuckGoHtmlAdapter with the fetcher — we inject a stub fetcher
        ConfigHolder.set(snapWith(true, WebSearchProviderKind.DUCKDUCKGO));

        // Create a WebSearchTool with a stub DDG fetch (via the HttpFetch seam)
        WebSearchTool tool = WebSearchTool.withDdgFetch(uri -> html);
        JsonObject input = new JsonObject();
        input.addProperty("query", "test query");
        input.addProperty("limit", 2);

        String result = tool.invoke(input);
        JsonArray arr = JsonParser.parseString(result).getAsJsonArray();
        assertEquals(2, arr.size(), "Should return 2 results from DDG");
        assertTrue(arr.get(0).getAsJsonObject().has("title"), "Results should have title");
        assertTrue(arr.get(0).getAsJsonObject().has("url"), "Results should have url");
    }

    /** Test 19: When provider=BRAVE, tool uses BraveSearchAdapter. */
    @Test
    void invoke_braveProvider_usesCorrectAdapter() throws ToolException {
        ConfigHolder.set(snapWith(true, WebSearchProviderKind.BRAVE));
        // Use parseBody seam — inject a stub Brave adapter
        WebSearchTool tool = WebSearchTool.withBraveParseBody(
            (body, limit) -> List.of(
                new SearchResult("Brave Result 1", "Snippet 1", "https://br1.example.com"),
                new SearchResult("Brave Result 2", "Snippet 2", "https://br2.example.com")
            )
        );
        JsonObject input = new JsonObject();
        input.addProperty("query", "test query");

        String result = tool.invoke(input);
        JsonArray arr = JsonParser.parseString(result).getAsJsonArray();
        assertEquals(2, arr.size(), "Should return 2 results from Brave stub");
    }

    /** Test 20: Result is a JSON array with title/snippet/url keys. */
    @Test
    void invoke_result_isJsonArrayWithCorrectKeys() throws ToolException {
        ConfigHolder.set(snapWith(true, WebSearchProviderKind.DUCKDUCKGO));
        String html = "<html><body><div class=\"results\">" +
            "<div class=\"results_links\"><div class=\"links_main\">" +
            "<h2><a class=\"result__a\" href=\"https://r1.example.com\">Result Title</a></h2>" +
            "<a class=\"result__snippet\">Result Snippet</a></div></div>" +
            "</div></body></html>";

        WebSearchTool tool = WebSearchTool.withDdgFetch(uri -> html);
        JsonObject input = new JsonObject();
        input.addProperty("query", "test");

        String result = tool.invoke(input);
        JsonArray arr = JsonParser.parseString(result).getAsJsonArray();
        assertFalse(arr.isEmpty(), "Should have at least one result");
        JsonObject entry = arr.get(0).getAsJsonObject();
        assertTrue(entry.has("title"), "Result should have title key");
        assertTrue(entry.has("snippet"), "Result should have snippet key");
        assertTrue(entry.has("url"), "Result should have url key");
    }
}
