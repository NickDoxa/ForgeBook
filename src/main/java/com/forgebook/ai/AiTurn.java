package com.forgebook.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sealed result type for a single provider turn (AI-01, D-13).
 *
 * Variants:
 *   FinalReply    — model returned terminal text; caller renders it.
 *   ToolUses      — model asked to run one or more tools; AgentLoop executes them in parallel (D-11)
 *                   and loops with tool_result blocks in the next turn.
 *   ProviderError — typed error; AgentLoop surfaces to AiDispatcher which maps to ChatErrorPacket.
 *
 * Java 17 sealed interface — exhaustive pattern matching in AgentLoop.switch(turn).
 */
public sealed interface AiTurn
        permits AiTurn.FinalReply, AiTurn.ToolUses, AiTurn.ProviderError {

    /** Terminal text reply. truncated=true when Anthropic stop_reason == "max_tokens". */
    record FinalReply(String text, boolean truncated) implements AiTurn {}

    /** Model requested one or more tools; execute in parallel per D-11. */
    record ToolUses(List<ToolUseBlock> uses) implements AiTurn {}

    /** Typed provider error. retryAfter is set for 429 per RESEARCH §1.5. */
    record ProviderError(Kind kind, String message, Optional<Duration> retryAfter) implements AiTurn {
        public enum Kind {
            TRANSPORT,        // network/DNS/TLS/5xx
            PROVIDER,         // 4xx terminal (400/401/402/403/404/413)
            OVERLOADED,       // 529 from Anthropic, or AiExecutor queue full
            RATE_LIMITED,     // 429
            NOT_IMPLEMENTED,  // OpenAI/Ollama stub (D-17)
            CIRCUIT_OPEN,     // CircuitBreaker tripped (AI-07)
            ITERATION_CAP     // AgentLoop exceeded 6 iterations (AI-05)
        }
    }

    /** Single tool_use block from Anthropic's response content[]. */
    record ToolUseBlock(String id, String name, Map<String, Object> input) {}
}
