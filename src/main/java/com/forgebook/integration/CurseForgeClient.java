package com.forgebook.integration;

import com.forgebook.config.ConfigSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String SEARCH_ENDPOINT = "https://api.curseforge.com/v1/mods/search";
    /** CurseForge {@code gameId} for Minecraft. */
    private static final int MINECRAFT_GAME_ID = 432;
    /** CurseForge {@code classId} for the "Mods" category (distinct from 4471 = Modpacks). */
    private static final int MODS_CLASS_ID = 6;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int SUMMARY_CAP = 500;
    private static final Gson GSON = new Gson();

    /**
     * Matches the canonical CurseForge mod URL shape:
     *   https://www.curseforge.com/minecraft/mc-mods/<slug>[/...]
     * Captures the slug as group 1. Host match is deliberately loose (supports bare
     * curseforge.com without www) — the protocol is not captured here; host validation
     * lives in {@link #extractSlugFromUrl(URL)}.
     */
    private static final Pattern CF_MOD_PATH = Pattern.compile(
        "^/minecraft/mc-mods/([^/?#]+).*$");

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

    // ------------------------------------------------------------------
    // Mod-description lookup (used by RagItemPipeline to bypass CurseForge's
    // anti-bot 403 on the HTML pages — the REST API returns the same
    // description content over an authenticated channel).
    // ------------------------------------------------------------------

    /**
     * If {@code url} is a CurseForge mod page ({@code curseforge.com/minecraft/mc-mods/<slug>}),
     * returns the slug. Otherwise returns empty. Pure — safe to call anywhere.
     */
    public static Optional<String> extractSlugFromUrl(URL url) {
        if (url == null) return Optional.empty();
        String host = url.getHost();
        if (host == null) return Optional.empty();
        host = host.toLowerCase(Locale.ROOT);
        if (!host.equals("curseforge.com") && !host.endsWith(".curseforge.com")) {
            return Optional.empty();
        }
        String path = url.getPath();
        if (path == null) return Optional.empty();
        Matcher m = CF_MOD_PATH.matcher(path);
        if (!m.matches()) return Optional.empty();
        String slug = m.group(1);
        return slug.isBlank() ? Optional.empty() : Optional.of(slug);
    }

    /**
     * Fetches the HTML description body for a CurseForge mod, resolved by slug.
     *
     * <p>Two-step lookup:
     * <ol>
     *   <li>{@code GET /v1/mods/search?gameId=432&classId=6&slug=<slug>} → first hit's numeric id.</li>
     *   <li>{@code GET /v1/mods/{id}/description} → HTML string in the {@code data} field.</li>
     * </ol>
     *
     * <p>Returns {@link Optional#empty()} on every failure mode (missing key, non-200,
     * no search hits, parse failure, network exception). Caller (RagItemPipeline)
     * falls back to direct HTML fetch when this returns empty.
     *
     * <p>Uses the same "trusted egress" {@link HttpClient} pattern as {@link #fetch}
     * (api.curseforge.com is a fixed known host, not user-supplied — bypasses
     * {@code SafeHttpFetcher} per the same exemption called out in that method's doc).
     *
     * @param slug mod slug from the CurseForge URL path (e.g. {@code "simply-swords"})
     * @param snap current config snapshot — read for {@code curseforgeApiKey()}
     * @return HTML description (possibly multi-KB) or empty
     */
    public static Optional<String> fetchDescriptionBySlug(String slug, ConfigSnapshot snap) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        if (snap.curseforgeApiKey().raw().isBlank()) return Optional.empty();

        try {
            HttpClient http = HttpClient.newHttpClient();
            String apiKey = snap.curseforgeApiKey().raw();

            // 1. Search by slug → numeric mod id.
            String searchUrl = SEARCH_ENDPOINT
                + "?gameId=" + MINECRAFT_GAME_ID
                + "&classId=" + MODS_CLASS_ID
                + "&slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
            HttpRequest searchReq = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> searchResp = http.send(searchReq, BodyHandlers.ofString());
            if (searchResp.statusCode() != 200) {
                LOG.debug("CurseForge search returned {} for slug {}", searchResp.statusCode(), slug);
                return Optional.empty();
            }
            Optional<Integer> modId = parseSearchForFirstId(searchResp.body());
            if (modId.isEmpty()) {
                LOG.debug("CurseForge search produced no hits for slug {}", slug);
                return Optional.empty();
            }

            // 2. Fetch description HTML for the resolved mod id.
            HttpRequest descReq = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT + modId.get() + "/description"))
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> descResp = http.send(descReq, BodyHandlers.ofString());
            if (descResp.statusCode() != 200) {
                LOG.debug("CurseForge description returned {} for modId {} (slug {})",
                    descResp.statusCode(), modId.get(), slug);
                return Optional.empty();
            }
            return parseDescription(descResp.body());
        } catch (Exception e) {
            LOG.debug("CurseForge description fetch failed for slug {}: {}", slug, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Parses {@code /v1/mods/search} response, returning the first element's numeric id.
     * Package-private for testing.
     */
    static Optional<Integer> parseSearchForFirstId(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement dataEl = root.get("data");
            if (dataEl == null || !dataEl.isJsonArray()) return Optional.empty();
            JsonArray arr = dataEl.getAsJsonArray();
            if (arr.size() == 0) return Optional.empty();
            JsonObject first = arr.get(0).getAsJsonObject();
            JsonElement idEl = first.get("id");
            if (idEl == null) return Optional.empty();
            return Optional.of(idEl.getAsInt());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parses {@code /v1/mods/{id}/description} response, returning the {@code data}
     * field as a string (HTML). Package-private for testing.
     */
    static Optional<String> parseDescription(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement dataEl = root.get("data");
            if (dataEl == null || !dataEl.isJsonPrimitive()) return Optional.empty();
            String html = dataEl.getAsString();
            return (html == null || html.isBlank()) ? Optional.empty() : Optional.of(html);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
