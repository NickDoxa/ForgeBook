package com.forgebook.ai.dto;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

/**
 * Unified Gson DTO for every Anthropic content block (text / tool_use / tool_result).
 * Fields are nullable — each block type populates its relevant subset per RESEARCH §1.3.
 *
 *   type="text"        → text populated
 *   type="tool_use"    → id, name, input populated
 *   type="tool_result" → tool_use_id, content, is_error populated
 *
 * Using a class (not record) so Gson can deserialize via reflection with nullable fields.
 */
public final class ContentBlock {
    public String type;
    public String text;
    public String name;
    public JsonElement input;
    public String id;
    @SerializedName("tool_use_id") public String toolUseId;
    public JsonElement content;
    @SerializedName("is_error") public Boolean isError;

    public ContentBlock() {}

    public ContentBlock(String type, String text, String name, JsonElement input,
                        String id, String toolUseId, JsonElement content, Boolean isError) {
        this.type = type;
        this.text = text;
        this.name = name;
        this.input = input;
        this.id = id;
        this.toolUseId = toolUseId;
        this.content = content;
        this.isError = isError;
    }

    public static ContentBlock text(String t) {
        return new ContentBlock("text", t, null, null, null, null, null, null);
    }

    public static ContentBlock toolResult(String toolUseId, String content, boolean isError) {
        JsonElement c = new com.google.gson.JsonPrimitive(content);
        return new ContentBlock("tool_result", null, null, null, null, toolUseId, c, isError);
    }
}
