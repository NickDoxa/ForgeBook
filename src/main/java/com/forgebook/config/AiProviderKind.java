package com.forgebook.config;

/**
 * Enum of supported AI provider tiers. Phase 1 only needs the enum to exist
 * for config typing; provider implementations land in Phase 2.
 */
public enum AiProviderKind {
    ANTHROPIC,
    OPENAI,
    OLLAMA
}
