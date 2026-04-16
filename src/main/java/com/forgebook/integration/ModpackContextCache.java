package com.forgebook.integration;

import java.util.Optional;

/**
 * Volatile-holder singleton for the cached ModpackContext fetched at ServerStartedEvent.
 * CF-01 / D-18: cached at startup, refreshed on /forgebook reload, never per-user-message (CF-03).
 * Mirrors the {@link com.forgebook.config.ConfigHolder} idiom from Phase 1.
 */
public final class ModpackContextCache {
    private static volatile Optional<ModpackContext> current = Optional.empty();
    private ModpackContextCache() {}
    public static Optional<ModpackContext> get() { return current; }
    public static void set(Optional<ModpackContext> ctx) { current = ctx; }
}
