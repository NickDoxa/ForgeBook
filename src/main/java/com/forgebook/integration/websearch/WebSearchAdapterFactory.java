package com.forgebook.integration.websearch;

import com.forgebook.config.ConfigSnapshot;
import com.forgebook.util.SafeHttpFetcher;

/**
 * Factory that materializes the correct {@link WebSearchAdapter} for the current
 * config. Shared by {@code WebSearchTool} (agent loop) and {@code RagItemPipeline}
 * (single-shot fallback) so both honour the same operator preferences.
 *
 * <p>Returns {@code null} instead of throwing when web search is disabled —
 * callers are expected to gate on {@code snap.enableWebSearch()} first, but this
 * is defense in depth.
 */
public final class WebSearchAdapterFactory {

    private WebSearchAdapterFactory() {}

    /**
     * Materialize the configured search adapter.
     *
     * @param snap current config snapshot
     * @return an adapter, or {@code null} if web search is disabled
     */
    public static WebSearchAdapter create(ConfigSnapshot snap) {
        if (!snap.enableWebSearch()) return null;
        return switch (snap.webSearchProvider()) {
            case DUCKDUCKGO -> new DuckDuckGoHtmlAdapter(
                uri -> new SafeHttpFetcher().fetch(uri).body());
            case BRAVE -> new BraveSearchAdapter(snap.webSearchApiKey());
        };
    }
}
