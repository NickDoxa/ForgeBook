package com.forgebook.ai.provider;

import com.forgebook.ai.AiProvider;
import com.forgebook.config.ConfigSnapshot;

/**
 * Selects the right AiProvider impl per ConfigSnapshot.aiProvider() (AI-03, D-17).
 *
 * The switch expression is exhaustive over AiProviderKind — no default branch.
 * If a new AiProviderKind is added without a corresponding case, the compiler
 * will emit an error at build time (Java 17 exhaustive switch).
 */
public final class ProviderFactory {
    private ProviderFactory() {}

    /**
     * Create the correct AiProvider for the given snapshot's aiProvider kind.
     * Plan 07 entry point — called by AiDispatcher.dispatch per request so
     * operator config reloads (via /forgebook reload) take effect immediately.
     */
    public static AiProvider create(ConfigSnapshot snap) {
        return switch (snap.aiProvider()) {
            case ANTHROPIC -> new ClaudeProvider();
            case OPENAI    -> new OpenAiProvider();
            case OLLAMA    -> new OllamaProvider();
        };
    }

    /**
     * Alias retained for Plan 03 callers. Delegates to create().
     *
     * @deprecated Use {@link #create(ConfigSnapshot)} directly.
     */
    public static AiProvider forSnapshot(ConfigSnapshot snap) {
        return create(snap);
    }
}
