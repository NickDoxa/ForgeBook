package com.forgebook.ai.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** Response body from /v1/messages (RESEARCH §1.3). Only fields we consume are mapped. */
public final class ClaudeResponse {
    public String id;
    public String type;
    public String role;
    public String model;
    public List<ContentBlock> content;
    @SerializedName("stop_reason") public String stopReason;
    @SerializedName("stop_sequence") public String stopSequence;
    public Usage usage;

    public String stopReason() { return stopReason; }
    public List<ContentBlock> content() { return content; }
}
