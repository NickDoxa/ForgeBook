package com.forgebook.ai.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** POST body for https://api.anthropic.com/v1/messages (RESEARCH §1.2). */
public final class ClaudeRequest {
    public String model;
    @SerializedName("max_tokens") public int maxTokens;
    public String system;
    public List<ClaudeMessage> messages;
    public List<ToolDef> tools;  // nullable — omitted when empty

    public ClaudeRequest() {}

    public ClaudeRequest(String model, int maxTokens, String system,
                         List<ClaudeMessage> messages, List<ToolDef> tools) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.system = system;
        this.messages = messages;
        this.tools = tools;
    }
}
