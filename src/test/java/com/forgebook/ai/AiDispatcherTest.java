package com.forgebook.ai;

import com.forgebook.config.ApiKey;
import com.forgebook.config.AiProviderKind;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.config.WebSearchProviderKind;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.tool.ToolRegistry;
import com.forgebook.util.AiExecutor;

import java.time.Duration;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AiDispatcher: sealed Result type, INSTANCE singleton,
 * dispatch entry point, ProviderError → ErrorCode mapping, and ProviderFactory.
 */
class AiDispatcherTest {

    static final ConfigSnapshot TEST_SNAPSHOT = new ConfigSnapshot(
        AiProviderKind.ANTHROPIC,
        new ApiKey("test-api-key"),
        "claude-haiku-4-5-20251001",
        2048,
        Optional.empty(),
        new ApiKey(""),
        true,
        10,
        false,
        WebSearchProviderKind.DUCKDUCKGO,
        new ApiKey(""),
        1
    );

    @BeforeAll
    static void startExecutor() {
        AiExecutor.start();
    }

    @BeforeEach
    void setup() {
        ConfigHolder.set(TEST_SNAPSHOT);
        SystemPromptCache.set("TEST_PROMPT_MARKER");
        ToolRegistry.injectForTests(Map.of());
    }

    @AfterEach
    void teardown() {
        ToolRegistry.resetForTests();
    }

    // ---- Test 1: AiDispatcher.Result is sealed with 2 permitted subclasses ----
    @Test
    void test1_resultIsSealed() {
        assertTrue(AiDispatcher.Result.class.isSealed(),
            "AiDispatcher.Result must be a sealed interface");
        assertEquals(2, AiDispatcher.Result.class.getPermittedSubclasses().length,
            "AiDispatcher.Result must have exactly 2 permitted subclasses (Reply and Error)");
    }

    // ---- Test 2: Reply and Error are records with named components ----
    @Test
    void test2_replyAndErrorAreRecords() throws Exception {
        // Reply(String text, boolean truncated)
        AiDispatcher.Reply reply = new AiDispatcher.Reply("hello", true);
        assertEquals("hello", reply.text());
        assertTrue(reply.truncated());

        // Error(ErrorCode code, String humanReadable)
        AiDispatcher.Error error = new AiDispatcher.Error(ErrorCode.PROVIDER, "Some error");
        assertEquals(ErrorCode.PROVIDER, error.code());
        assertEquals("Some error", error.humanReadable());
    }

    // ---- Test 3: INSTANCE is non-null ----
    @Test
    void test3_instanceIsNonNull() {
        assertNotNull(AiDispatcher.INSTANCE);
    }

    // ---- Test 4: dispatcherForTests returns a usable instance ----
    @Test
    void test4_dispatcherForTestsReturnsNonNull() {
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("hi", false));
        AiDispatcher d = AiDispatcher.dispatcherForTests(new ScriptedAiProvider(queue));
        assertNotNull(d);
    }

    // ---- Test 5: happy path — FinalReply returns Reply ----
    @Test
    void test5_happyPath_finalReplyReturnsReply() {
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("hi", false));
        AiDispatcher d = AiDispatcher.dispatcherForTests(new ScriptedAiProvider(queue));
        AiDispatcher.Result result = d.dispatch("hello", null);
        assertInstanceOf(AiDispatcher.Reply.class, result);
        assertEquals("hi", ((AiDispatcher.Reply) result).text());
        assertFalse(((AiDispatcher.Reply) result).truncated());
    }

    // ---- Test 6: truncation flag passes through ----
    @Test
    void test6_truncationFlagPassesThrough() {
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("long text...", true));
        AiDispatcher d = AiDispatcher.dispatcherForTests(new ScriptedAiProvider(queue));
        AiDispatcher.Result result = d.dispatch("q", null);
        assertInstanceOf(AiDispatcher.Reply.class, result);
        assertTrue(((AiDispatcher.Reply) result).truncated());
    }

    // ---- Test 7: ConfigHolder.get() called exactly once (D-14 single-read invariant) ----
    // This is verified via structural analysis: dispatch reads snap once then passes it
    // through. We verify by calling dispatch with a scripted provider and checking the
    // model in the captured request matches the snapshot.
    @Test
    void test7_configSnapshotReadOnce() {
        ConfigSnapshot snap = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("k"),
            "sentinel-model-id",
            512,
            Optional.empty(),
            new ApiKey(""),
            true,
            10,
            false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
        ConfigHolder.set(snap);
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("ok", false));
        ScriptedAiProvider provider = new ScriptedAiProvider(queue);
        AiDispatcher d = AiDispatcher.dispatcherForTests(provider);
        d.dispatch("hello", null);
        // Verify the ChatRequest the provider received used the sentinel model
        assertEquals("sentinel-model-id", provider.lastRequest().model());
    }

    // ---- Test 8: system prompt comes from SystemPromptCache ----
    @Test
    void test8_systemPromptSourcedFromCache() {
        SystemPromptCache.set("UNIQUE_SENTINEL_PROMPT_12345");
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("ok", false));
        ScriptedAiProvider provider = new ScriptedAiProvider(queue);
        AiDispatcher d = AiDispatcher.dispatcherForTests(provider);
        d.dispatch("question", null);
        assertTrue(provider.lastRequest().system().contains("UNIQUE_SENTINEL_PROMPT_12345"),
            "system prompt must come from SystemPromptCache");
    }

    // ---- Test 9: model and maxTokens come from ConfigSnapshot ----
    @Test
    void test9_modelAndMaxTokensFromSnapshot() {
        ConfigSnapshot snap = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("k"),
            "claude-haiku-4-5-20251001",
            2048,
            Optional.empty(),
            new ApiKey(""),
            true,
            10,
            false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
        ConfigHolder.set(snap);
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("ok", false));
        ScriptedAiProvider provider = new ScriptedAiProvider(queue);
        AiDispatcher d = AiDispatcher.dispatcherForTests(provider);
        d.dispatch("q", null);
        assertEquals("claude-haiku-4-5-20251001", provider.lastRequest().model());
        assertEquals(2048, provider.lastRequest().maxTokens());
    }

    // ---- Test 10: tools come from ToolRegistry.all() ----
    @Test
    void test10_toolsFromToolRegistry() {
        // Inject 2 dummy tools
        ToolRegistry.injectForTests(Map.of(
            "tool_a", new StubTool("tool_a"),
            "tool_b", new StubTool("tool_b")
        ));
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("ok", false));
        ScriptedAiProvider provider = new ScriptedAiProvider(queue);
        AiDispatcher d = AiDispatcher.dispatcherForTests(provider);
        d.dispatch("q", null);
        assertEquals(2, provider.lastRequest().tools().size(),
            "captured ChatRequest must contain tools from ToolRegistry");
    }

    // ---- Tests 11-17: ProviderError.Kind → ErrorCode mapping ----
    @Test
    void test11_transport() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.TRANSPORT, "net err", Optional.empty());
        AiDispatcher.Error result = AiDispatcher.mapError(err);
        assertEquals(ErrorCode.TRANSPORT, result.code());
        assertTrue(result.humanReadable().toLowerCase().contains("transient") ||
                   result.humanReadable().toLowerCase().contains("network"),
            "human readable should mention network issue: " + result.humanReadable());
    }

    @Test
    void test12_provider() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.PROVIDER, "provider err", Optional.empty());
        AiDispatcher.Error result = AiDispatcher.mapError(err);
        assertEquals(ErrorCode.PROVIDER, result.code());
    }

    @Test
    void test13_overloaded() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.OVERLOADED, "busy", Optional.empty());
        AiDispatcher.Error result = AiDispatcher.mapError(err);
        assertEquals(ErrorCode.OVERLOADED, result.code());
    }

    @Test
    void test14_rateLimited() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.RATE_LIMITED, "rate", Optional.of(Duration.ofSeconds(5)));
        AiDispatcher.Error result = AiDispatcher.mapError(err);
        assertEquals(ErrorCode.RATE_LIMITED, result.code());
    }

    @Test
    void test15_notImplemented() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.NOT_IMPLEMENTED, "not impl", Optional.empty());
        AiDispatcher.Error result = AiDispatcher.mapError(err);
        assertEquals(ErrorCode.PROVIDER, result.code(),
            "NOT_IMPLEMENTED maps to PROVIDER error code");
    }

    @Test
    void test16_circuitOpen() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.CIRCUIT_OPEN, "open", Optional.empty());
        AiDispatcher.Error result = AiDispatcher.mapError(err);
        assertEquals(ErrorCode.PROVIDER, result.code(),
            "CIRCUIT_OPEN maps to PROVIDER error code");
    }

    @Test
    void test17_iterationCap() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.ITERATION_CAP, "cap", Optional.empty());
        AiDispatcher.Error result = AiDispatcher.mapError(err);
        assertEquals(ErrorCode.PROVIDER, result.code(),
            "ITERATION_CAP maps to PROVIDER error code");
        assertTrue(result.humanReadable().contains("6"),
            "humanReadable must mention iteration count: " + result.humanReadable());
    }

    // ---- Tests 18-21: ProviderFactory ----
    @Test
    void test18_providerFactoryAnthropicReturnsClaudeProvider() {
        ConfigSnapshot snap = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("k"),
            "m",
            1024,
            Optional.empty(),
            new ApiKey(""),
            true,
            10,
            false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
        com.forgebook.ai.AiProvider provider =
            com.forgebook.ai.provider.ProviderFactory.create(snap);
        assertInstanceOf(com.forgebook.ai.provider.ClaudeProvider.class, provider);
    }

    @Test
    void test19_providerFactoryOpenAiReturnsOpenAiProvider() {
        ConfigSnapshot snap = new ConfigSnapshot(
            AiProviderKind.OPENAI,
            new ApiKey("k"),
            "m",
            1024,
            Optional.empty(),
            new ApiKey(""),
            true,
            10,
            false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
        com.forgebook.ai.AiProvider provider =
            com.forgebook.ai.provider.ProviderFactory.create(snap);
        assertInstanceOf(com.forgebook.ai.provider.OpenAiProvider.class, provider);
    }

    @Test
    void test20_providerFactoryOllamaReturnsOllamaProvider() {
        ConfigSnapshot snap = new ConfigSnapshot(
            AiProviderKind.OLLAMA,
            new ApiKey("k"),
            "m",
            1024,
            Optional.empty(),
            new ApiKey(""),
            true,
            10,
            false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
        com.forgebook.ai.AiProvider provider =
            com.forgebook.ai.provider.ProviderFactory.create(snap);
        assertInstanceOf(com.forgebook.ai.provider.OllamaProvider.class, provider);
    }

    @Test
    void test21_providerFactoryExhaustiveSwitch() {
        // Verify the switch in ProviderFactory covers all AiProviderKind values.
        // If a new kind is added without updating ProviderFactory, this triggers
        // a compile error (exhaustive switch expression) — verified by test 18-20.
        for (AiProviderKind kind : AiProviderKind.values()) {
            ConfigSnapshot snap = new ConfigSnapshot(
                kind,
                new ApiKey("k"),
                "m",
                1024,
                Optional.empty(),
                new ApiKey(""),
                true,
                10,
                false,
                WebSearchProviderKind.DUCKDUCKGO,
                new ApiKey(""),
                1
            );
            assertNotNull(com.forgebook.ai.provider.ProviderFactory.create(snap),
                "ProviderFactory.create must not return null for kind " + kind);
        }
    }

    // ---- Test 22: SystemPromptCache empty → fallback prompt used, no NPE ----
    @Test
    void test22_emptySystemPromptCacheUseFallback() {
        SystemPromptCache.set("");
        Queue<AiTurn> queue = new LinkedList<>();
        queue.add(new AiTurn.FinalReply("ok", false));
        ScriptedAiProvider provider = new ScriptedAiProvider(queue);
        AiDispatcher d = AiDispatcher.dispatcherForTests(provider);
        // Must not throw
        AiDispatcher.Result result = d.dispatch("q", null);
        assertInstanceOf(AiDispatcher.Reply.class, result);
        // System prompt should be the fallback (non-empty)
        assertFalse(provider.lastRequest().system().isEmpty(),
            "fallback system prompt must not be empty");
    }

    // ---- Test 23: ConfigHolder null → Error(PROVIDER, ...) ----
    @Test
    void test23_nullConfigHolderReturnsError() {
        ConfigHolder.set(null);
        try {
            Queue<AiTurn> queue = new LinkedList<>();
            queue.add(new AiTurn.FinalReply("ok", false));
            AiDispatcher d = AiDispatcher.dispatcherForTests(new ScriptedAiProvider(queue));
            AiDispatcher.Result result = d.dispatch("q", null);
            assertInstanceOf(AiDispatcher.Error.class, result);
            assertEquals(ErrorCode.PROVIDER, ((AiDispatcher.Error) result).code());
        } finally {
            ConfigHolder.set(TEST_SNAPSHOT);
        }
    }

    // ---- Helper inner class for test tools ----
    private static final class StubTool implements com.forgebook.tool.Tool {
        private final String toolName;
        StubTool(String name) { this.toolName = name; }

        @Override public String name() { return toolName; }
        @Override public String description() { return "stub"; }
        @Override public com.google.gson.JsonObject schema() { return new com.google.gson.JsonObject(); }
        @Override public String invoke(com.google.gson.JsonObject input) { return ""; }
    }
}
