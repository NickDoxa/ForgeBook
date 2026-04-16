package com.forgebook.integration.websearch;

import com.forgebook.tool.ToolException;

import java.util.List;

/**
 * Common interface for web-search backends (D-01/D-02, RESEARCH §3.4).
 *
 * Two implementations ship in v1:
 *   - {@link DuckDuckGoHtmlAdapter} — free, no key required (default)
 *   - {@link BraveSearchAdapter}   — paid, requires {@code web_search_api_key}
 *
 * Implementations MUST route HTTP off the server thread (called from AgentLoop / AiExecutor).
 * Results carry only title/snippet/url triples — NO raw page content (D-03 / TOOL-04).
 */
public interface WebSearchAdapter {
    /**
     * Returns up to {@code limit} results for the given query.
     *
     * @param query search query string
     * @param limit maximum number of results to return
     * @return list of up to {@code limit} search results
     * @throws ToolException if the search fails (FETCH_FAILED, UPSTREAM_TIMEOUT, etc.)
     */
    List<SearchResult> search(String query, int limit) throws ToolException;
}
