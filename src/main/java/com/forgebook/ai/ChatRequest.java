package com.forgebook.ai;

import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ToolDef;
import java.util.List;

/**
 * Provider-agnostic request shape (AI-01 contract for AiProvider.chat).
 * Each field maps 1:1 to Anthropic's /v1/messages body (RESEARCH §1.2).
 */
public record ChatRequest(
    String model,            // snap.aiModel()
    int maxTokens,           // snap.maxTokens()
    String system,           // pre-rendered system prompt from SystemPromptCache
    List<ClaudeMessage> messages,
    List<ToolDef> tools
) {}
