package com.forgebook.tool.impl;

import com.forgebook.integration.scraper.PromptFraming;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Tool: list_installed_mods (TOOL-02).
 *
 * Projects ModList.getMods() to a JSON array of {modId, displayName, version, modURL} entries.
 * Accepts an optional {@code filter} substring for modId (case-insensitive).
 * Applies the 8,000-char tool output cap (TOOL-06).
 *
 * Uses a {@link Supplier} seam for testability without Forge runtime.
 * Threading: stateless. Safe to call from AiExecutor.
 */
public final class ListInstalledModsTool implements Tool {

    private static final Gson GSON = new Gson();

    private final Supplier<List<? extends IModInfo>> modSupplier;

    /**
     * Production constructor — reads from live ModList lazily on each invocation.
     * Called by ToolRegistry.init(SafeHttpFetcher, ModList).
     */
    public ListInstalledModsTool(ModList modList) {
        this(modList != null
            ? () -> modList.getMods()
            : () -> ModList.get().getMods());
    }

    /**
     * Test-seam constructor — accepts any supplier of IModInfo list.
     * Package-private to keep the seam out of the public API.
     */
    ListInstalledModsTool(Supplier<List<? extends IModInfo>> modSupplier) {
        this.modSupplier = modSupplier;
    }

    @Override
    public String name() { return "list_installed_mods"; }

    @Override
    public String description() {
        return "Detailed modId/name/version/docsURL for installed mods. Optional modId substring filter.";
    }

    @Override
    public JsonObject schema() {
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");

        JsonObject properties = new JsonObject();
        properties.add("filter", filter);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", new com.google.gson.JsonArray());
        return schema;
    }

    @Override
    public String invoke(JsonObject input) throws ToolException {
        String filter = input.has("filter")
            ? input.get("filter").getAsString().toLowerCase(Locale.ROOT)
            : null;

        JsonArray arr = new JsonArray();
        for (IModInfo info : modSupplier.get()) {
            String modId = info.getModId();
            if (filter != null && !modId.toLowerCase(Locale.ROOT).contains(filter)) continue;

            String modURL = info.getModURL()
                .map(java.net.URL::toString)
                .orElse("");

            JsonObject entry = new JsonObject();
            entry.addProperty("modId", modId);
            entry.addProperty("displayName", info.getDisplayName());
            entry.addProperty("version", info.getVersion().toString());
            entry.addProperty("modURL", modURL);
            arr.add(entry);
        }

        String body = GSON.toJson(arr);

        // Apply 8,000-char tool output cap (TOOL-06, D-14)
        if (body.length() > PromptFraming.TOOL_OUTPUT_CAP) {
            body = body.substring(0, PromptFraming.TOOL_OUTPUT_CAP)
                + "\n[... truncated at 8,000 chars — call list_installed_mods with a tighter filter]";
        }
        return body;
    }
}
