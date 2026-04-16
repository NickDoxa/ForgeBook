package com.forgebook.config;

/**
 * Volatile-reference holder for the current ConfigSnapshot (D-14).
 * Reload (/forgebook reload in ForgebookReloadCommand) builds a fresh snapshot
 * and assigns it here in a single volatile store. Readers do a single volatile
 * load at request entry — consistency follows from the snapshot itself being
 * a deeply-immutable record.
 *
 * buildFromSpec() is a static helper that reads the live ForgeConfigSpec values;
 * it is called on ServerStartingEvent (wired from ForgeBookMod in Plan 03 context,
 * or from the reload command's executor).
 */
public final class ConfigHolder {
    private static volatile ConfigSnapshot current = null;

    private ConfigHolder() {}

    public static ConfigSnapshot get() {
        return current;
    }

    public static void set(ConfigSnapshot s) {
        current = s;
    }

    public static ConfigSnapshot buildFromSpec() {
        java.util.Optional<String> modpackId = java.util.Optional
            .ofNullable(ForgebookServerConfig.CURSEFORGE_MODPACK_ID.get())
            .filter(s -> !s.isBlank());
        return new ConfigSnapshot(
            ForgebookServerConfig.AI_PROVIDER.get(),
            new ApiKey(ForgebookServerConfig.AI_API_KEY.get()),
            ForgebookServerConfig.AI_MODEL.get(),
            ForgebookServerConfig.MAX_TOKENS.get(),                          // NEW (Phase 2 — D-05)
            modpackId,
            new ApiKey(ForgebookServerConfig.CURSEFORGE_API_KEY.get()),
            ForgebookServerConfig.OP_ONLY.get(),
            ForgebookServerConfig.RATE_LIMIT_PER_MINUTE.get(),
            ForgebookServerConfig.ENABLE_WEB_SEARCH.get(),
            ForgebookServerConfig.WEB_SEARCH_PROVIDER.get(),                 // NEW (Phase 2 — D-01)
            new ApiKey(ForgebookServerConfig.WEB_SEARCH_API_KEY.get()),      // NEW (Phase 2 — D-02)
            ForgebookServerConfig.CONFIG_VERSION.get()
        );
    }
}
