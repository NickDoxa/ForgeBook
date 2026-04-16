package com.forgebook.integration.websearch;

import com.forgebook.tool.ToolException;
import com.forgebook.util.SafeHttpFetcher;
import com.forgebook.util.UnsafeUrlException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DuckDuckGo HTML scrape adapter for web search (D-01/D-02, RESEARCH §3.1).
 *
 * Routes through {@link SafeHttpFetcher} (SSRF-protected) by default.
 * Uses an internal {@link HttpFetch} seam for unit-test injection without
 * subclassing the final SafeHttpFetcher.
 *
 * DDG redirect cleanup: href values of the form {@code //duckduckgo.com/l/?uddg=...}
 * are decoded to recover the real target URL (RESEARCH §3.1 gotcha).
 */
public final class DuckDuckGoHtmlAdapter implements WebSearchAdapter {

    /** Functional interface for the HTTP fetch operation — enables test injection. */
    @FunctionalInterface
    public interface HttpFetch {
        /** Fetch the URL and return the raw HTML body. */
        String fetch(URI uri) throws UnsafeUrlException, IOException;
    }

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/?q=";

    private final HttpFetch httpFetch;

    /** Production constructor — routes through SafeHttpFetcher (SSRF-protected). */
    public DuckDuckGoHtmlAdapter(SafeHttpFetcher fetcher) {
        this(uri -> fetcher.fetch(uri).body());
    }

    /**
     * Test-seam constructor — accepts any {@link HttpFetch} implementation.
     * Do NOT use in production code outside of test packages.
     */
    DuckDuckGoHtmlAdapter(HttpFetch httpFetch) {
        this.httpFetch = httpFetch;
    }

    @Override
    public List<SearchResult> search(String query, int limit) throws ToolException {
        URI uri = URI.create(ENDPOINT + URLEncoder.encode(query, StandardCharsets.UTF_8));
        String html;
        try {
            html = httpFetch.fetch(uri);
        } catch (UnsafeUrlException e) {
            throw new ToolException(ToolException.Reason.FETCH_FAILED,
                "ddg: " + e.reason());
        } catch (IOException e) {
            throw new ToolException(ToolException.Reason.UPSTREAM_TIMEOUT,
                "ddg: " + e.getMessage());
        }

        Document doc = Jsoup.parse(html);
        List<SearchResult> results = new ArrayList<>();
        for (Element row : doc.select("div.results_links div.links_main")) {
            Element a = row.selectFirst("a.result__a");
            if (a == null) continue;
            Element s = row.selectFirst(".result__snippet");
            String href = cleanDdgRedirect(a.attr("href"));
            results.add(new SearchResult(a.text(), s == null ? "" : s.text(), href));
            if (results.size() >= limit) break;
        }
        return results;
    }

    /**
     * Decode DDG redirect URLs of the form {@code //duckduckgo.com/l/?uddg=<encoded>}
     * back to the real target URL. Non-redirect hrefs are returned as-is.
     */
    private static String cleanDdgRedirect(String href) {
        if (!href.contains("uddg=")) return href;
        // Parse the query string manually to avoid pulling in apache-commons-text
        // Handle protocol-relative href: //duckduckgo.com/l/?uddg=...
        String queryPart = href.contains("?") ? href.substring(href.indexOf('?') + 1) : href;
        for (String param : queryPart.split("&")) {
            if (param.startsWith("uddg=")) {
                return URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
            }
        }
        return href;
    }
}
