package com.forgebook.tool;

/**
 * Paired response block for a Claude tool_use (RESEARCH §1.4).
 * toolUseId MUST exactly match the tool_use.id from the assistant turn.
 * Order-preserving: AgentLoop must emit results in the same order as uses (RESEARCH §7.2).
 */
public record ToolResult(String toolUseId, String content, boolean isError) {}
