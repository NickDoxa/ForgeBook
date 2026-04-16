package com.forgebook.ai.provider;

import com.forgebook.ai.AiProvider;
import com.forgebook.config.ConfigSnapshot;

/** Selects the right AiProvider impl per ConfigSnapshot.aiProvider() (AI-03). */
public final class ProviderFactory {
    private ProviderFactory() {}

    public static AiProvider forSnapshot(ConfigSnapshot snap) {
        return switch (snap.aiProvider()) {
            case ANTHROPIC -> new ClaudeProvider();
            case OPENAI    -> new OpenAiProvider();
            case OLLAMA    -> new OllamaProvider();
        };
    }
}
