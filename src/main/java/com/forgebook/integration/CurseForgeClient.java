package com.forgebook.integration;

import com.forgebook.config.ConfigSnapshot;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Optional;

/**
 * One-shot CurseForge REST API v1 client. Fetches modpack metadata at ServerStartedEvent
 * and caches the result in {@link ModpackContextCache}.
 *
 * <p>CF-01: fetches {@code /v1/mods/{modpack_id}} with {@code x-api-key} header.<br>
 * CF-02: strictly optional — every failure mode returns {@code Optional.empty()} without
 *         throwing or logging at ERROR.<br>
 * CF-03: HTTP call only originates here — never from per-message paths (AgentLoop / tools).
 *
 * <p>Uses {@code java.net.http.HttpClient} directly (NOT SafeHttpFetcher) because
 * api.curseforge.com is a fixed, trusted egress — same exemption as ClaudeProvider
 * per RESEARCH §"Phase 2 clients of SafeHttpFetcher".
 *
 * <p>{@code .raw()} calls are intentional — {@code com.forgebook.integration.*} is on
 * the grep-lint package allowlist (per PATTERNS §"ApiKey.raw()").
 */
public final class CurseForgeClient {

    private static final Logger LOG = LogManager.getLogger(CurseForgeClient.class);
    private static final String ENDPOINT = "https://api.curseforge.com/v1/mods/";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int SUMMARY_CAP = 500;
    private static final Gson GSON = new Gson();

    private CurseForgeClient() {}

    /**
     * Fetches CurseForge modpack metadata for the configured modpack ID.
     *
     * <p>Returns {@code Optional.empty()} if:
     * <ul>
     *   <li>{@code snap.curseforgeModpackId()} is absent (CF-02 silent skip)</li>
     *   <li>{@code snap.curseforgeApiKey().raw()} is blank (CF-02 silent skip)</li>
     *   <li>The HTTP response status is not 200 (WARN logged)</li>
     *   <li>Any exception occurs during the HTTP call or JSON parse (WARN logged)</li>
     * </ul>
     *
     * @param snap current config snapshot (read once at call site per D-14)
     * @return {@code Optional<ModpackContext>} or empty on any failure
     */
    public static Optional<ModpackContext> fetch(ConfigSnapshot snap) {
        // CF-02: silent skip when not configured — no logging, no exception
        if (snap.curseforgeModpackId().isEmpty() || snap.curseforgeApiKey().raw().isBlank()) {
            return Optional.empty();
        }

        String modpackId = snap.curseforgeModpackId().get();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + modpackId))
                .header("x-api-key", snap.curseforgeApiKey().raw())
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                LOG.warn("CurseForge returned {} for modpack {}; skipping modpack context",
                    resp.statusCode(), modpackId);
                return Optional.empty();
            }

            return Optional.of(parseResponse(resp.body()));

        } catch (Exception e) {
            LOG.warn("CurseForge fetch failed; skipping modpack context", e);
            return Optional.empty();
        }
    }

    /**
     * Parses the CurseForge {@code /v1/mods/{id}} JSON response body into a
     * {@link ModpackContext}.
     *
     * <p>Package-private for unit testing. Throws a {@link RuntimeException}
     * (typically {@link com.google.gson.JsonSyntaxException} or
     * {@link NullPointerException}) on malformed input — the caller ({@link #fetch})
     * catches all exceptions per CF-02.
     *
     * @param body raw JSON response body
     * @return parsed {@link ModpackContext} with name and (truncated) summary
     * @throws RuntimeException on malformed/missing JSON structure
     */
    static ModpackContext parseResponse(String body) {
        CurseForgeResponse parsed = GSON.fromJson(body, CurseForgeResponse.class);

        // Defend against null data or null fields — throws NPE on null data (CF-02 caller catches)
        String name = parsed.data().name() != null ? parsed.data().name() : "";
        String summary = parsed.data().summary() != null ? parsed.data().summary() : "";

        // Defensive truncation per RESEARCH §"Open Question 2" and T-02-04-06
        if (summary.length() > SUMMARY_CAP) {
            summary = summary.substring(0, SUMMARY_CAP);
        }

        return new ModpackContext(name, summary);
    }

    // ----- Gson DTOs (inner records — Gson ignores unknown fields by default) -----

    private record CurseForgeResponse(ModData data) {}

    private record ModData(int id, String name, String summary) {}
}
