package com.forgebook.tool.impl;

import com.forgebook.integration.scraper.ModDocsScraper;
import com.forgebook.integration.scraper.PromptFraming;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.forgebook.util.SafeHttpFetcher;
import com.forgebook.util.UnsafeUrlException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;

/**
 * Tool: fetch_mod_docs_page (TOOL-03).
 *
 * Fetches a mod documentation/wiki URL via SafeHttpFetcher (SSRF-protected),
 * extracts readable text via ModDocsScraper, and frames it with PromptFraming
 * in a {@code <mod_doc trust="untrusted">} wrapper (D-10).
 *
 * Throws ToolException(NO_DOCS_URL) for empty/blank URLs — the agent's signal
 * to fall back to web_search (TOOL-07 / Success Criterion 5).
 *
 * Uses a {@link FetchFn} functional seam for testability without network.
 * Threading: network I/O via the seam. Safe to call from AiExecutor.
 */
public final class FetchModDocsPageTool implements Tool {

    /**
     * Functional interface for the HTTP fetch operation — enables test injection
     * without subclassing the final SafeHttpFetcher.
     */
    @FunctionalInterface
    public interface FetchFn {
        SafeHttpFetcher.Result fetch(URI uri) throws UnsafeUrlException, IOException;
    }

    private final FetchFn fetchFn;

    /**
     * Production constructor — routes through SafeHttpFetcher (SSRF-protected).
     * When {@code fetcher} is null (e.g. ToolRegistry test init), stores a lazy seam
     * that creates a new SafeHttpFetcher on first use.
     */
    public FetchModDocsPageTool(SafeHttpFetcher fetcher) {
        this(fetcher != null
            ? fetcher::fetch
            : uri -> new SafeHttpFetcher().fetch(uri));
    }

    /**
     * Test-seam constructor — accepts any FetchFn implementation.
     * Package-private to keep the seam out of the public API.
     */
    FetchModDocsPageTool(FetchFn fetchFn) {
        this.fetchFn = fetchFn;
    }

    @Override
    public String name() { return "fetch_mod_docs_page"; }

    @Override
    public String description() {
        return "Fetch a mod's docs page. Returns NO_DOCS_URL if missing — fall back to web_search.";
    }

    @Override
    public JsonObject schema() {
        JsonObject urlProp = new JsonObject();
        urlProp.addProperty("type", "string");

        JsonObject properties = new JsonObject();
        properties.add("url", urlProp);

        JsonArray required = new JsonArray();
        required.add("url");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    @Override
    public String invoke(JsonObject input) throws ToolException {
        String url = input.has("url") ? input.get("url").getAsString() : null;

        // TOOL-07: empty or blank URL is the "no docs" signal
        if (url == null || url.isBlank()) {
            throw new ToolException(ToolException.Reason.NO_DOCS_URL,
                "mod has no documentation url; consider web_search");
        }

        try {
            SafeHttpFetcher.Result r = fetchFn.fetch(URI.create(url));
            String readable = ModDocsScraper.extractReadable(r.body(), url);
            return PromptFraming.wrap(readable, url);
        } catch (UnsafeUrlException e) {
            throw new ToolException(ToolException.Reason.FETCH_FAILED,
                "url=" + url + " reason=" + e.reason());
        } catch (IOException e) {
            throw new ToolException(ToolException.Reason.UPSTREAM_TIMEOUT,
                "io: " + e.getMessage());
        }
    }
}
