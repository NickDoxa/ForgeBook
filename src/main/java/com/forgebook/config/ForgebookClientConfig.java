package com.forgebook.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;

/**
 * CLIENT-tier config (CFG-02). Materialized as config/forgebook-client.toml.
 * Holds exactly one field: enable_chat_interface.
 * No secrets, no server behavior. Per D-12: CLIENT tier is never synced — it's
 * local to each client.
 */
public final class ForgebookClientConfig {

    public static final ForgeConfigSpec SPEC;
    public static final BooleanValue ENABLE_CHAT_INTERFACE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("ForgeBook client-side configuration.",
                  "No secrets live here. The client never holds an API key.").push("ui");
        ENABLE_CHAT_INTERFACE = b.comment(
            "When false, the in-inventory chat button is not injected (Phase 4 UI).")
            .define("enable_chat_interface", true);
        b.pop();
        SPEC = b.build();
    }

    private ForgebookClientConfig() {}
}
