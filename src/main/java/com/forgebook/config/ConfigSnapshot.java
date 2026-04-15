package com.forgebook.config;

import java.util.Optional;

/**
 * Immutable, single-read view of all resolved config values (D-14).
 * Consumers read ConfigHolder.get() once at request entry, then treat the result
 * as stable for the duration of the request.
 */
public record ConfigSnapshot(
    AiProviderKind aiProvider,
    ApiKey aiApiKey,
    String aiModel,
    Optional<String> curseforgeModpackId,
    ApiKey curseforgeApiKey,
    boolean opOnly,
    int rateLimitPerMinute,
    boolean enableWebSearch,
    int configVersion
) {}
