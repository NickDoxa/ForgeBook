package com.forgebook.tool;

import com.forgebook.tool.impl.FetchModDocsPageTool;
import com.forgebook.tool.impl.GetModpackContextTool;
import com.forgebook.tool.impl.ListInstalledModsTool;
import com.forgebook.tool.impl.WebSearchTool;
import com.forgebook.util.SafeHttpFetcher;
import net.minecraftforge.fml.ModList;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static registry of all available tools (TOOL-01).
 *
 * {@link #init(SafeHttpFetcher, ModList)} is called once from ForgeBookMod's
 * ServerStartedEvent listener (Plan 07). {@link #all()} provides the ordered
 * collection used to populate the Anthropic {@code tools[]} array (Plan 06 SystemPromptBuilder).
 *
 * Uses a {@link LinkedHashMap} to preserve insertion order — critical for deterministic
 * Anthropic tools[] JSON arrays.
 *
 * Analog: {@code network/ForgebookNetwork.java} (static registry populated once at lifecycle event).
 */
public final class ToolRegistry {

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    private ToolRegistry() {}

    /**
     * Populate the registry with the four standard tools.
     * Clears any existing entries first so re-init on {@code /forgebook reload} works.
     *
     * @param fetcher SafeHttpFetcher instance (shared across DDG + FetchModDocsTool)
     * @param modList ModList instance (or null to fall back to {@code ModList.get()} lazily)
     */
    public static void init(SafeHttpFetcher fetcher, ModList modList) {
        TOOLS.clear();
        TOOLS.put("list_installed_mods",  new ListInstalledModsTool(modList));
        TOOLS.put("fetch_mod_docs_page",  new FetchModDocsPageTool(fetcher));
        TOOLS.put("web_search",           new WebSearchTool(fetcher));
        TOOLS.put("get_modpack_context",  new GetModpackContextTool());
    }

    /**
     * Look up a tool by name.
     *
     * @param name the tool name (e.g., "fetch_mod_docs_page")
     * @return the registered Tool
     * @throws IllegalArgumentException if no tool with that name is registered
     */
    public static Tool get(String name) {
        Tool t = TOOLS.get(name);
        if (t == null) throw new IllegalArgumentException("Unknown tool: " + name);
        return t;
    }

    /**
     * All registered tools in insertion order (list_installed_mods → fetch_mod_docs_page →
     * web_search → get_modpack_context).
     */
    public static Collection<Tool> all() {
        return TOOLS.values();
    }

    /**
     * Replace all registered tools with the given map.
     * Package-private — for Plan 07 AgentLoopE2ETest to install stub tools without Forge.
     */
    static void injectForTests(Map<String, Tool> overrides) {
        TOOLS.clear();
        TOOLS.putAll(overrides);
    }

    /**
     * Clear all registered tools.
     * Package-private — callable in @AfterEach to restore a clean registry state.
     */
    static void resetForTests() {
        TOOLS.clear();
    }
}
