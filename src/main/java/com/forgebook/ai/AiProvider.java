package com.forgebook.ai;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable AI provider (AI-01). Implementations:
 *   - ClaudeProvider (v1 default)
 *   - OpenAiProvider, OllamaProvider (stubs throwing NOT_IMPLEMENTED per D-17)
 *   - ScriptedAiProvider (test-only, RESEARCH §9.2)
 *
 * Threading: MUST be invoked from AiExecutor or AgentLoop's submission; never from
 * the server main thread or Forge network thread.
 */
public interface AiProvider {
    CompletableFuture<AiTurn> chat(ChatRequest req);
}
