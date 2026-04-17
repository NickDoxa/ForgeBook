package com.forgebook.integration;

import com.forgebook.config.AiProviderKind;
import com.forgebook.config.ApiKey;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.config.WebSearchProviderKind;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 unit tests for CurseForgeClient.parseResponse and config-gating short-circuits.
 * HTTP-status-code paths (200/401/404/5xx) and live network are NOT tested here
 * (no real socket needed for the pure-logic paths).
 *
 * Phase 2 / Plan 04.
 */
class CurseForgeClientTest {

    // ----- Fixture loading helper -----

    private String loadFixture(String name) throws Exception {
        InputStream in = getClass().getResourceAsStream(
            "/forgebook/phase2/curseforge/" + name);
        assertNotNull(in, "Fixture not found on classpath: " + name);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    // ----- ConfigSnapshot helper -----

    /**
     * Builds a minimal ConfigSnapshot with all 12 fields; irrelevant ones use safe defaults.
     * Matches the 12-field record order from Phase 2 Plan 01:
     * (aiProvider, aiApiKey, aiModel, maxTokens, curseforgeModpackId, curseforgeApiKey,
     *  opOnly, rateLimitPerMinute, enableWebSearch, webSearchProvider, webSearchApiKey, configVersion)
     */
    private static ConfigSnapshot snapshot(Optional<String> modpackId, String apiKey) {
        return new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("test-ai-key"),
            "claude-haiku-4-5",
            1024,
            modpackId,
            new ApiKey(apiKey),
            true,
            5,
            false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
    }

    // ----- parseResponse tests -----

    @Test
    void parseResponse_extractsNameAndSummary() throws Exception {
        String body = loadFixture("atm9-200.json");
        ModpackContext ctx = CurseForgeClient.parseResponse(body);
        assertEquals("All the Mods 9", ctx.name());
        assertTrue(ctx.summary().startsWith("All the Mods 9 is a CurseForge modpack"),
            "Expected summary to start with 'All the Mods 9 is a CurseForge modpack' but was: "
                + ctx.summary());
    }

    @Test
    void parseResponse_truncatesSummaryAt500Chars() {
        // Build a JSON with an 800-char summary
        String longSummary = "A".repeat(800);
        String body = "{\"data\":{\"id\":1,\"name\":\"Test Pack\",\"summary\":\""
            + longSummary + "\"}}";
        ModpackContext ctx = CurseForgeClient.parseResponse(body);
        assertEquals(500, ctx.summary().length(),
            "Summary must be truncated to 500 chars when input exceeds cap");
    }

    @Test
    void parseResponse_handlesNullSummary() {
        String body = "{\"data\":{\"id\":1,\"name\":\"X\",\"summary\":null}}";
        ModpackContext ctx = CurseForgeClient.parseResponse(body);
        assertEquals("", ctx.summary(), "Null summary must be coerced to empty string");
    }

    @Test
    void parseResponse_throwsOnMalformedJson() throws Exception {
        String body = loadFixture("atm9-malformed.json");
        // atm9-malformed.json has {"name":"orphan"} — missing the "data" wrapper
        // parseResponse should throw a RuntimeException (Gson JsonSyntaxException or NPE wrapped)
        assertThrows(RuntimeException.class, () -> CurseForgeClient.parseResponse(body),
            "parseResponse must throw on malformed JSON (missing data wrapper)");
    }

    // ----- fetch short-circuit tests -----

    @Test
    void fetch_returnsEmptyWhenModpackIdMissing() {
        ConfigSnapshot snap = snapshot(Optional.empty(), "valid-api-key");
        Optional<ModpackContext> result = CurseForgeClient.fetch(snap);
        assertTrue(result.isEmpty(),
            "fetch must return Optional.empty() when curseforgeModpackId is absent");
    }

    @Test
    void fetch_returnsEmptyWhenApiKeyBlank() {
        ConfigSnapshot snap = snapshot(Optional.of("520914"), "");
        Optional<ModpackContext> result = CurseForgeClient.fetch(snap);
        assertTrue(result.isEmpty(),
            "fetch must return Optional.empty() when curseforgeApiKey is blank");
    }

    // ----- extractSlugFromUrl tests -----

    @Test
    void extractSlug_fromWwwCurseforgeModUrl() throws Exception {
        Optional<String> slug = CurseForgeClient.extractSlugFromUrl(
            new URL("https://www.curseforge.com/minecraft/mc-mods/simply-swords"));
        assertEquals(Optional.of("simply-swords"), slug);
    }

    @Test
    void extractSlug_fromBareHostAndTrailingPath() throws Exception {
        Optional<String> slug = CurseForgeClient.extractSlugFromUrl(
            new URL("https://curseforge.com/minecraft/mc-mods/create/files"));
        assertEquals(Optional.of("create"), slug);
    }

    @Test
    void extractSlug_rejectsNonCurseforgeHost() throws Exception {
        assertTrue(CurseForgeClient.extractSlugFromUrl(
            new URL("https://github.com/foo/bar")).isEmpty());
        assertTrue(CurseForgeClient.extractSlugFromUrl(
            new URL("https://example.com/minecraft/mc-mods/simply-swords")).isEmpty());
    }

    @Test
    void extractSlug_rejectsNonModPath() throws Exception {
        assertTrue(CurseForgeClient.extractSlugFromUrl(
            new URL("https://www.curseforge.com/minecraft/modpacks/all-the-mods-9")).isEmpty());
        assertTrue(CurseForgeClient.extractSlugFromUrl(
            new URL("https://www.curseforge.com/")).isEmpty());
    }

    // ----- parseSearchForFirstId tests -----

    @Test
    void parseSearchForFirstId_extractsFirstHitId() {
        String body = "{\"data\":[{\"id\":241920,\"slug\":\"create\"},{\"id\":99,\"slug\":\"x\"}]}";
        assertEquals(Optional.of(241920), CurseForgeClient.parseSearchForFirstId(body));
    }

    @Test
    void parseSearchForFirstId_emptyArray_returnsEmpty() {
        assertTrue(CurseForgeClient.parseSearchForFirstId("{\"data\":[]}").isEmpty());
    }

    @Test
    void parseSearchForFirstId_malformed_returnsEmpty() {
        assertTrue(CurseForgeClient.parseSearchForFirstId("not json").isEmpty());
        assertTrue(CurseForgeClient.parseSearchForFirstId("{\"data\":null}").isEmpty());
    }

    // ----- parseDescription tests -----

    @Test
    void parseDescription_extractsHtmlString() {
        String body = "{\"data\":\"<html><body><p>Hello</p></body></html>\"}";
        assertEquals(Optional.of("<html><body><p>Hello</p></body></html>"),
            CurseForgeClient.parseDescription(body));
    }

    @Test
    void parseDescription_blankOrMissing_returnsEmpty() {
        assertTrue(CurseForgeClient.parseDescription("{\"data\":\"\"}").isEmpty());
        assertTrue(CurseForgeClient.parseDescription("{}").isEmpty());
        assertTrue(CurseForgeClient.parseDescription("not json").isEmpty());
    }

    // ----- fetchDescriptionBySlug short-circuit -----

    @Test
    void fetchDescription_returnsEmptyWhenApiKeyBlank() {
        ConfigSnapshot snap = snapshot(Optional.empty(), "");
        assertTrue(CurseForgeClient.fetchDescriptionBySlug("simply-swords", snap).isEmpty(),
            "fetchDescriptionBySlug must short-circuit when key is blank — no network call");
    }

    @Test
    void fetchDescription_returnsEmptyOnBlankSlug() {
        ConfigSnapshot snap = snapshot(Optional.empty(), "some-key");
        assertTrue(CurseForgeClient.fetchDescriptionBySlug("", snap).isEmpty());
        assertTrue(CurseForgeClient.fetchDescriptionBySlug(null, snap).isEmpty());
    }
}
