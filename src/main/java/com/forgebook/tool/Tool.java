package com.forgebook.tool;

import com.google.gson.JsonObject;

/**
 * Tool contract (TOOL-01). Implementations are stateless and registered once in
 * ToolRegistry at ServerStartedEvent (D-08, RESEARCH §6.3).
 *
 * Threading: invoke() is called from AgentLoop's parallel tool execution on
 * AiExecutor (D-11, RESEARCH §7.2). MUST NOT block the main thread.
 *
 * Error protocol (D-12): on failure, throw ToolException; AgentLoop catches and
 * emits a structured tool_result with is_error=true so the model can continue
 * (e.g. TOOL-07: NO_DOCS_URL → agent falls back to web_search).
 */
public interface Tool {
    /** Unique tool name as used in Anthropic tool_use blocks (e.g. "fetch_mod_docs_page"). */
    String name();

    /** Human description for the tool — surfaced to the model via tools[].description. */
    String description();

    /** Anthropic input_schema (JSON Schema shape per RESEARCH §1.2). */
    JsonObject schema();

    /**
     * Execute the tool and return the content string for the next turn's tool_result block.
     * The returned string is what the model sees as "content" — it SHOULD be framed (per D-10,
     * PromptFraming.wrap) when the tool emits untrusted data.
     *
     * @throws ToolException structured failure; AgentLoop converts to is_error=true tool_result.
     */
    String invoke(JsonObject input) throws ToolException;
}
