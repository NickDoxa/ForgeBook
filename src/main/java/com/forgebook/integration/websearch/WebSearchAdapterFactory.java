package com.forgebook.integration.websearch;

import com.forgebook.config.ConfigSnapshot;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Factory that materializes the correct {@link WebSearchAdapter} for the current
 * config. Shared by {@code WebSearchTool} (agent loop) and {@code RagItemPipeline}
 * (single-shot fallback) so both honour the same operator preferences.
 *
 * <p>Returns {@code null} instead of throwing when web search is disabled —
 * callers are expected to gate on {@code snap.enableWebSearch()} first, but this
 * is defense in depth.
 *
 * <h2>Why DDG no longer routes through SafeHttpFetcher</h2>
 * The SafeHttpFetcher SSRF protection pins the connection to a resolved IP and
 * uses a custom {@code SSLSocketFactory}/{@code HostnameVerifier} pair to preserve
 * SNI (see JDK-8144566 workaround). That workaround was built against
 * {@code HttpsURLConnection} and is IGNORED by {@code java.net.http.HttpClient}'s
 * TLS stack — so HttpClient ends up validating the cert against the pinned IP,
 * fails hostname verification ({@code HTTPS hostname wrong: should be &lt;IP&gt;}),
 * and every DDG fetch dies at TLS handshake.
 *
 * <p>Since DDG's endpoint URL ({@code https://html.duckduckgo.com/html/?q=...}) is
 * INTERNALLY constructed — attacker-controlled input only reaches the query
 * parameter, not the host — the SSRF rationale for SafeHttpFetcher doesn't apply
 * here. We switch DDG to a plain {@code HttpClient} with a tight timeout instead.
 * Brave has always worked this way; we're just making DDG consistent.
 */
public final class WebSearchAdapterFactory {

    /** Bounded timeout so a hanging DDG response cannot stall the agent loop. */
    private static final Duration DDG_TIMEOUT = Duration.ofSeconds(10);

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
            case DUCKDUCKGO -> new DuckDuckGoHtmlAdapter(trustedHttpFetch());
            case BRAVE -> new BraveSearchAdapter(snap.webSearchApiKey());
        };
    }

    /**
     * Plain-HttpClient fetcher for trusted search endpoints. No IP pinning, no
     * custom SNI acrobatics — just a normal TLS handshake against the declared
     * hostname so cert validation works correctly.
     */
    private static DuckDuckGoHtmlAdapter.HttpFetch trustedHttpFetch() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(DDG_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        return uri -> {
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(DDG_TIMEOUT)
                // DDG's html.duckduckgo.com rejects unknown user-agents with a
                // blank body. Use a conventional browser-ish UA string.
                .header("User-Agent",
                    "Mozilla/5.0 (compatible; ForgeBook/1.0; +https://github.com/NickDoxa/ForgeBook)")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
            try {
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new java.io.IOException(
                        "DDG returned HTTP " + resp.statusCode());
                }
                return resp.body();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("DDG fetch interrupted", ie);
            }
        };
    }
}
