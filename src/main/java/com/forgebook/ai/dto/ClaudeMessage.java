package com.forgebook.ai.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * One message in the {@code messages[]} array. {@code content} is String (simple case) OR
 * a JsonArray of ContentBlocks (tool_use / tool_result multi-block case — RESEARCH §1.4).
 * Gson serializes whichever underlying type is set.
 */
public final class ClaudeMessage {
    public String role;       // "user" or "assistant"
    public JsonElement content;

    public ClaudeMessage() {}

    public ClaudeMessage(String role, JsonElement content) {
        this.role = role;
        this.content = content;
    }

    public static ClaudeMessage userText(String text) {
        return new ClaudeMessage("user", new JsonPrimitive(text));
    }

    public static ClaudeMessage assistantBlocks(JsonArray blocks) {
        return new ClaudeMessage("assistant", blocks);
    }

    public static ClaudeMessage userBlocks(JsonArray blocks) {
        return new ClaudeMessage("user", blocks);
    }
}
