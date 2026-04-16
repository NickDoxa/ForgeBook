package com.forgebook.config;

import java.util.Optional;

/**
 * Immutable, single-read view of all resolved config values (D-14).
 * Consumers read ConfigHolder.get() once at request entry, then treat the result
 * as stable for the duration of the request.
 *
 * Phase 2 additions: maxTokens (D-05), webSearchProvider (D-01/D-02),
 * webSearchApiKey (Brave fallback per D-02).
 */
public record ConfigSnapshot(
    AiProviderKind aiProvider,
    ApiKey aiApiKey,
    String aiModel,
    int maxTokens,                            // NEW (Phase 2 — D-05)
    Optional<String> curseforgeModpackId,
    ApiKey curseforgeApiKey,
    boolean opOnly,
    int rateLimitPerMinute,
    boolean enableWebSearch,
    WebSearchProviderKind webSearchProvider,  // NEW (Phase 2 — D-01)
    ApiKey webSearchApiKey,                   // NEW (Phase 2 — D-02)
    int configVersion
) {}
