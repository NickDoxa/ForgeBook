package com.forgebook.integration.websearch;

import com.forgebook.config.ApiKey;
import com.forgebook.tool.ToolException;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Brave Search API adapter (D-01/D-02, RESEARCH §3.2).
 *
 * Uses raw {@link HttpClient} with {@code X-Subscription-Token} header.
 * api.search.brave.com is a trusted fixed egress — same exemption as
 * {@code ClaudeProvider} and {@code CurseForgeClient} (RESEARCH "Phase 2 clients of SafeHttpFetcher").
 *
 * {@code ApiKey.raw()} is allowed here — this class is in {@code com.forgebook.integration.*}
 * which is on the CI grep-lint allowlist.
 */
public final class BraveSearchAdapter implements WebSearchAdapter {

    private static final String ENDPOINT_BASE =
        "https://api.search.brave.com/res/v1/web/search?count=10&q=";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Gson GSON = new Gson();

    private final ApiKey apiKey;

    public BraveSearchAdapter(ApiKey apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public List<SearchResult> search(String query, int limit) throws ToolException {
        URI uri = URI.create(ENDPOINT_BASE + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("X-Subscription-Token", apiKey.raw())  // .raw() allowed in com.forgebook.integration.*
            .header("Accept", "application/json")
            .timeout(TIMEOUT)
            .GET()
            .build();
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ToolException(ToolException.Reason.FETCH_FAILED,
                    "brave HTTP " + resp.statusCode());
            }
            return parseBody(resp.body(), limit);
        } catch (ToolException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new ToolException(ToolException.Reason.UPSTREAM_TIMEOUT,
                "brave: " + e.getMessage());
        }
    }

    /**
     * Parse the Brave Search API JSON response body.
     * Package-private for direct unit testing without live HTTP (BraveSearchAdapterTest).
     *
     * @param body  JSON response body
     * @param limit maximum number of results to return
     * @return list of up to {@code limit} SearchResults; empty list on missing/null fields
     */
    static List<SearchResult> parseBody(String body, int limit) {
        BraveResp p = GSON.fromJson(body, BraveResp.class);
        if (p == null || p.web == null || p.web.results == null) return List.of();
        return p.web.results.stream()
            .limit(limit)
            .map(item -> new SearchResult(
                item.title != null ? item.title : "",
                item.description != null ? item.description : "",
                item.url != null ? item.url : ""))
            .toList();
    }

    // Inner Gson DTOs — unknown fields silently ignored by default (no custom adapter needed)
    private static class BraveResp { BraveWeb web; }
    private static class BraveWeb { List<BraveItem> results; }
    private static class BraveItem { String title; String url; String description; }
}
