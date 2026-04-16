package com.forgebook.integration.websearch;

/** Web search result triple — D-03 / TOOL-04. No raw page content. */
public record SearchResult(String title, String snippet, String url) {}
