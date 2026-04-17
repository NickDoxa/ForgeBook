package com.forgebook.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;

/**
 * SERVER-tier config (CFG-01). Materialized as config/forgebook-server.toml.
 * Contains ALL secrets and server-only behavior fields per D-12 and CLAUDE.md (g).
 *
 * SERVER tier is synced to connected clients automatically by Forge at login —
 * BUT clients never need these values (secrets stay on the server process).
 * Clients simply ignore the sync.
 *
 * Anti-pattern avoided (CLAUDE.md): no .sync() on the builder — SERVER tier
 * controls sync behavior, not a decorator method.
 */
public final class ForgebookServerConfig {

    public static final ForgeConfigSpec SPEC;
    public static final EnumValue<AiProviderKind> AI_PROVIDER;
    public static final ConfigValue<String> AI_API_KEY;
    public static final ConfigValue<String> AI_MODEL;
    public static final IntValue MAX_TOKENS;
    public static final ConfigValue<String> CURSEFORGE_MODPACK_ID;
    public static final ConfigValue<String> CURSEFORGE_API_KEY;
    public static final BooleanValue OP_ONLY;
    public static final IntValue RATE_LIMIT_PER_MINUTE;
    public static final BooleanValue ENABLE_WEB_SEARCH;
    public static final EnumValue<WebSearchProviderKind> WEB_SEARCH_PROVIDER;
    public static final ConfigValue<String> WEB_SEARCH_API_KEY;
    public static final IntValue CONFIG_VERSION;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("ForgeBook server-side configuration.",
                  "ALL API keys live here. Never edit this file on the client — the client never reads it.",
                  "Recommended: chmod 600 forgebook-server.toml on Linux hosts.").push("ai");

        AI_PROVIDER = b.comment("AI provider. One of ANTHROPIC, OPENAI, OLLAMA. Phase 1 ships no provider impl.")
                       .defineEnum("ai_provider", AiProviderKind.ANTHROPIC);
        AI_API_KEY  = b.comment("API key for the selected provider. Redacted in logs.")
                       .define("ai_api_key", "");
        AI_MODEL    = b.comment("Model ID to send to the provider. Provider-specific. Default is the Haiku 4.5 alias; pin to a snapshot like \"claude-haiku-4-5-20251001\" if you need deterministic behavior.")
                       .define("ai_model", "claude-haiku-4-5");
        MAX_TOKENS  = b.comment("Max tokens the AI provider may generate per request. Lower = cheaper + faster; higher = more detailed answers. Range: 128..8192.")
                       .defineInRange("max_tokens", 1024, 128, 8192);

        b.pop();

        b.comment("CurseForge integration (optional — leave modpack id blank to disable).").push("curseforge");
        CURSEFORGE_MODPACK_ID = b.comment("CurseForge modpack project ID (numeric string). Leave blank to skip.")
                                 .define("curseforge_modpack_id", "");
        CURSEFORGE_API_KEY    = b.comment("CurseForge REST API key. Required only if modpack ID is set. Redacted in logs.")
                                 .define("curseforge_api_key", "");
        b.pop();

        b.comment("Access control and cost guardrails.").push("access");
        OP_ONLY = b.comment("When true, only server OPs may call the AI pipeline. Default true.")
                   .define("op_only", true);
        RATE_LIMIT_PER_MINUTE = b.comment(
                                    "Per-player request budget when op_only=false. OPs always bypass.",
                                    "Default 30 = one request every 2 seconds sustained, with bucket-burst of 30.",
                                    "Chat UI is interactive and conversational — 5/min choked users after 5 messages.")
                                 .defineInRange("rate_limit_per_minute", 30, 1, 240);
        ENABLE_WEB_SEARCH = b.comment("Expose the web-search tool to the agent. Phase 2+ feature.")
                             .define("enable_web_search", false);
        b.pop();

        b.comment("Web-search tool backend (used only when enable_web_search = true).").push("websearch");
        WEB_SEARCH_PROVIDER = b.comment("Web search backend. DUCKDUCKGO requires no API key (scrapes html.duckduckgo.com). BRAVE requires web_search_api_key (Brave Search API).")
                               .defineEnum("web_search_provider", WebSearchProviderKind.DUCKDUCKGO);
        WEB_SEARCH_API_KEY  = b.comment("API key for Brave Search (required only when web_search_provider = BRAVE). Redacted in logs.")
                               .define("web_search_api_key", "");
        b.pop();

        b.push("meta");
        CONFIG_VERSION = b.comment("Schema version. Do not edit manually.")
                          .defineInRange("config_version", 1, 1, Integer.MAX_VALUE);
        b.pop();

        SPEC = b.build();
    }

    private ForgebookServerConfig() {}
}
