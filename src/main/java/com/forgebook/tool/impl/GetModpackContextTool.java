package com.forgebook.tool.impl;

import com.forgebook.integration.ModpackContext;
import com.forgebook.integration.ModpackContextCache;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Optional;

/**
 * Tool: get_modpack_context (TOOL-05).
 *
 * Reads the cached ModpackContext set by Plan 07's ServerStartedEvent listener.
 * Returns a JSON-serialized ModpackContext when configured, or a "not configured"
 * sentinel when the cache is empty (CurseForge integration not set up).
 *
 * Threading: stateless — reads volatile ModpackContextCache. Safe to call from AiExecutor.
 */
public final class GetModpackContextTool implements Tool {

    private static final Gson GSON = new Gson();
    private static final String NOT_CONFIGURED = "{\"modpack\":\"<not configured>\"}";

    @Override
    public String name() { return "get_modpack_context"; }

    @Override
    public String description() {
        return "Modpack name + summary (CurseForge). Empty marker if not configured.";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.add("required", new com.google.gson.JsonArray());
        return schema;
    }

    @Override
    public String invoke(JsonObject input) throws ToolException {
        Optional<ModpackContext> ctx = ModpackContextCache.get();
        if (ctx.isEmpty()) return NOT_CONFIGURED;
        return GSON.toJson(ctx.get());
    }
}
