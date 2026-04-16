package com.forgebook.ai.dto;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

/** One entry in {@code tools[]}. {@code input_schema} is a JSON Schema object (RESEARCH §1.2). */
public final class ToolDef {
    public String name;
    public String description;
    @SerializedName("input_schema") public JsonObject inputSchema;

    public ToolDef() {}

    public ToolDef(String name, String description, JsonObject inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }
}
