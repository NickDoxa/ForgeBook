package com.forgebook.config;

/**
 * Web-search backend selector (D-01/D-02, RESEARCH §3.3).
 *
 * DUCKDUCKGO scrapes html.duckduckgo.com — no API key required (operator-cost-free).
 * BRAVE calls api.search.brave.com — requires {@code web_search_api_key}.
 *
 * Default: DUCKDUCKGO. Operators flip via {@code web_search_provider} in
 * forgebook-server.toml when DDG returns blocked or stale results.
 */
public enum WebSearchProviderKind { DUCKDUCKGO, BRAVE }
