package com.forgebook.tool.impl;

import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.integration.websearch.BraveSearchAdapter;
import com.forgebook.integration.websearch.DuckDuckGoHtmlAdapter;
import com.forgebook.integration.websearch.SearchResult;
import com.forgebook.integration.websearch.WebSearchAdapter;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.forgebook.util.SafeHttpFetcher;
import com.forgebook.util.UnsafeUrlException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Tool: web_search (TOOL-04).
 *
 * Gated by {@code enable_web_search} config flag (TOOL-04 / D-01).
 * Selects the appropriate {@link WebSearchAdapter} based on {@code web_search_provider}
 * in the current ConfigSnapshot (single-read at invoke entry, D-14).
 * Returns Gson-serialized list of (title, snippet, url) triples — no raw page content (D-03).
 *
 * Uses seams for test injection without live network or Forge.
 * Threading: HTTP via the adapter. Safe to call from AiExecutor.
 */
public final class WebSearchTool implements Tool {

    private static final Gson GSON = new Gson();
    // Lowered default 5→3 and max 10→5: each result is ~400-600 chars of JSON, and
    // tool_result bodies get replayed in every subsequent turn of the agent loop.
    // A 3-result default cuts ~1kB off the turn-2 request — non-trivial against
    // Anthropic's 10k input-tokens-per-minute org limit.
    private static final int DEFAULT_LIMIT = 3;
    private static final int MAX_LIMIT = 5;
    /** Hard cap on tool_result body size to keep subsequent agent turns under the token limit. */
    static final int RESULT_BODY_CAP = 2_000;

    /** Seam for the DDG HTTP fetch (used in tests via withDdgFetch factory). */
    private final DuckDuckGoHtmlAdapter.HttpFetch ddgFetch;

    /**
     * Seam for the Brave response parser (used in tests via withBraveParseBody factory).
     * When non-null, overrides the live BraveSearchAdapter.parseBody call.
     */
    private final BiFunction<String, Integer, List<SearchResult>> braveParseBodyOverride;

    /**
     * Production constructor — takes a SafeHttpFetcher for DDG routing.
     * Called by ToolRegistry.init().
     */
    public WebSearchTool(SafeHttpFetcher fetcher) {
        // Adapt SafeHttpFetcher.fetch (returns Result) to HttpFetch (returns String body)
        this(fetcher != null ? uri -> fetcher.fetch(uri).body() : null, null);
    }

    private WebSearchTool(DuckDuckGoHtmlAdapter.HttpFetch ddgFetch,
                          BiFunction<String, Integer, List<SearchResult>> braveParseBodyOverride) {
        this.ddgFetch = ddgFetch;
        this.braveParseBodyOverride = braveParseBodyOverride;
    }

    // ---- Test seam factory methods ----

    /**
     * Creates a WebSearchTool that uses the given DDG fetch function instead of SafeHttpFetcher.
     * Package-private — test use only.
     */
    static WebSearchTool withDdgFetch(DuckDuckGoHtmlAdapter.HttpFetch ddgFetch) {
        return new WebSearchTool(ddgFetch, null);
    }

    /**
     * Creates a WebSearchTool that uses the given function as the Brave response parser.
     * Package-private — test use only.
     */
    static WebSearchTool withBraveParseBody(BiFunction<String, Integer, List<SearchResult>> parser) {
        return new WebSearchTool(null, parser);
    }

    // ---- Tool interface ----

    @Override
    public String name() { return "web_search"; }

    @Override
    public String description() {
        return "Web search. Returns title/snippet/url triples.";
    }

    @Override
    public JsonObject schema() {
        JsonObject queryProp = new JsonObject();
        queryProp.addProperty("type", "string");

        JsonObject limitProp = new JsonObject();
        limitProp.addProperty("type", "integer");
        limitProp.addProperty("description", "default 3, max 5");

        JsonObject properties = new JsonObject();
        properties.add("query", queryProp);
        properties.add("limit", limitProp);

        JsonArray required = new JsonArray();
        required.add("query");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    @Override
    public String invoke(JsonObject input) throws ToolException {
        // Single-read of config at invoke entry (Phase 1 D-14 pattern)
        ConfigSnapshot snap = ConfigHolder.get();

        if (snap == null || !snap.enableWebSearch()) {
            throw new ToolException(ToolException.Reason.INVALID_INPUT,
                "web_search disabled by operator");
        }

        String query = input.has("query") ? input.get("query").getAsString() : "";
        if (query.isBlank()) {
            throw new ToolException(ToolException.Reason.INVALID_INPUT, "query required");
        }

        int limit = DEFAULT_LIMIT;
        if (input.has("limit")) {
            limit = Math.min(input.get("limit").getAsInt(), MAX_LIMIT);
        }

        List<SearchResult> results = selectAdapter(snap).search(query, limit);
        String body = GSON.toJson(results);
        // Fix #1c (1.0.4): cap tool_result body size. This body is echoed back in
        // every subsequent agent-loop turn, so uncapped search output can easily
        // tip a 2-turn dispatch over Anthropic's 10k input-tokens-per-minute org limit.
        if (body.length() > RESULT_BODY_CAP) {
            body = body.substring(0, RESULT_BODY_CAP) + "\"...truncated\"]";
        }
        return body;
    }

    /** Select and construct the appropriate adapter based on config. */
    private WebSearchAdapter selectAdapter(ConfigSnapshot snap) throws ToolException {
        return switch (snap.webSearchProvider()) {
            case DUCKDUCKGO -> {
                DuckDuckGoHtmlAdapter.HttpFetch fetch = ddgFetch != null
                    ? ddgFetch
                    : uri -> new SafeHttpFetcher().fetch(uri).body();
                yield new DuckDuckGoHtmlAdapter(fetch);
            }
            case BRAVE -> {
                if (braveParseBodyOverride != null) {
                    // Test seam — wrap the parse override in an adapter
                    BiFunction<String, Integer, List<SearchResult>> parser = braveParseBodyOverride;
                    yield (query, limit) -> parser.apply("", limit);
                }
                yield new BraveSearchAdapter(snap.webSearchApiKey());
            }
        };
    }
}
