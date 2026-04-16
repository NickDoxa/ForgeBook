package com.forgebook.ai.dto;

import com.google.gson.annotations.SerializedName;

/** Token usage counters from Anthropic's response (RESEARCH §1.3). */
public final class Usage {
    @SerializedName("input_tokens")  public int inputTokens;
    @SerializedName("output_tokens") public int outputTokens;
}
