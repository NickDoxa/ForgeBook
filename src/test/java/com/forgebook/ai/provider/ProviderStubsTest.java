package com.forgebook.ai.provider;

import com.forgebook.ai.AiTurn;
import com.forgebook.ai.ChatRequest;
import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.config.AiProviderKind;
import com.forgebook.config.ApiKey;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.config.WebSearchProviderKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProviderStubsTest {

    private static final ChatRequest DUMMY_REQ = new ChatRequest(
        "claude-haiku-4-5", 1024, "system",
        List.of(ClaudeMessage.userText("hi")), List.of());

    private static ConfigSnapshot snapFor(AiProviderKind kind) {
        return new ConfigSnapshot(
            kind, new ApiKey(""), "model", 1024,
            Optional.empty(), new ApiKey(""), true, 5, false,
            WebSearchProviderKind.DUCKDUCKGO, new ApiKey(""), 1);
    }

    @Test
    void test1_openAiProviderReturnsNotImplemented() throws Exception {
        AiTurn result = new OpenAiProvider().chat(DUMMY_REQ).get();
        assertInstanceOf(AiTurn.ProviderError.class, result);
        AiTurn.ProviderError err = (AiTurn.ProviderError) result;
        assertEquals(AiTurn.ProviderError.Kind.NOT_IMPLEMENTED, err.kind());
        assertTrue(err.message().contains("OpenAI"),
            "Error message must mention OpenAI, got: " + err.message());
        assertEquals(Optional.empty(), err.retryAfter());
    }

    @Test
    void test2_ollamaProviderReturnsNotImplemented() throws Exception {
        AiTurn result = new OllamaProvider().chat(DUMMY_REQ).get();
        assertInstanceOf(AiTurn.ProviderError.class, result);
        AiTurn.ProviderError err = (AiTurn.ProviderError) result;
        assertEquals(AiTurn.ProviderError.Kind.NOT_IMPLEMENTED, err.kind());
        assertTrue(err.message().contains("Ollama"),
            "Error message must mention Ollama, got: " + err.message());
        assertEquals(Optional.empty(), err.retryAfter());
    }

    @Test
    void test3_constructorsDoNotThrow() {
        assertDoesNotThrow(() -> new OpenAiProvider(),
            "OpenAiProvider constructor must not throw");
        assertDoesNotThrow(() -> new OllamaProvider(),
            "OllamaProvider constructor must not throw");
    }

    @Test
    void test4_providerFactoryReturnsClaudeProviderForAnthropic() {
        var provider = ProviderFactory.forSnapshot(snapFor(AiProviderKind.ANTHROPIC));
        assertInstanceOf(ClaudeProvider.class, provider,
            "ProviderFactory must return ClaudeProvider for ANTHROPIC");
    }

    @Test
    void test5_providerFactoryReturnsOpenAiProviderForOpenAi() {
        var provider = ProviderFactory.forSnapshot(snapFor(AiProviderKind.OPENAI));
        assertInstanceOf(OpenAiProvider.class, provider,
            "ProviderFactory must return OpenAiProvider for OPENAI");
    }

    @Test
    void test6_providerFactoryReturnsOllamaProviderForOllama() {
        var provider = ProviderFactory.forSnapshot(snapFor(AiProviderKind.OLLAMA));
        assertInstanceOf(OllamaProvider.class, provider,
            "ProviderFactory must return OllamaProvider for OLLAMA");
    }
}
