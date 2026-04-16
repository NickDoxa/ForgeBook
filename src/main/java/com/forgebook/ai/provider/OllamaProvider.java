package com.forgebook.ai.provider;

import com.forgebook.ai.AiProvider;
import com.forgebook.ai.AiTurn;
import com.forgebook.ai.ChatRequest;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** D-17, AI-03: stub. Constructor does NOT throw. chat() returns NOT_IMPLEMENTED. */
public final class OllamaProvider implements AiProvider {
    public OllamaProvider() {}

    @Override
    public CompletableFuture<AiTurn> chat(ChatRequest req) {
        return CompletableFuture.completedFuture(new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.NOT_IMPLEMENTED,
            "Ollama provider is not implemented in v1. Set ai_provider = ANTHROPIC in forgebook-server.toml.",
            Optional.empty()));
    }
}
