package com.forgebook.ai;

import com.forgebook.ai.dto.ContentBlock;
import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ToolDef;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.forgebook.tool.ToolRegistry;
import com.forgebook.util.AiExecutor;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AgentLoop unit tests (AI-05: 6-iteration cap, D-11: parallel, D-12: error-tolerant).
 * All tests run on the JVM — no Minecraft harness.
 * Uses ScriptedAiProvider as the seam; no Mockito.
 */
class AgentLoopTest {

    // Minimal initial request used by most tests.
    private static ChatRequest initialRequest;

    @BeforeAll
    static void startExecutor() {
        AiExecutor.start(); // idempotent
        initialRequest = new ChatRequest(
            "claude-haiku-4-5",
            1024,
            "You are ForgeBook.",
            List.of(ClaudeMessage.userText("hello")),
            List.of()
        );
        // Reset registry before all tests
        ToolRegistry.injectForTests(new LinkedHashMap<>());
    }

    @AfterEach
    void resetRegistry() {
        ToolRegistry.injectForTests(new LinkedHashMap<>());
    }

    // ─── Tests 1–3: ScriptedAiProvider ───────────────────────────────────────

    @Test
    void test1_scriptedProviderDeliversTurnsInFifoOrder() {
        var turns = new LinkedList<AiTurn>(List.of(
            new AiTurn.FinalReply("first", false),
            new AiTurn.FinalReply("second", false)
        ));
        var sap = new ScriptedAiProvider(turns);
        var req = initialRequest;

        AiTurn t1 = sap.chat(req).join();
        AiTurn t2 = sap.chat(req).join();

        assertInstanceOf(AiTurn.FinalReply.class, t1);
        assertEquals("first", ((AiTurn.FinalReply) t1).text());
        assertInstanceOf(AiTurn.FinalReply.class, t2);
        assertEquals("second", ((AiTurn.FinalReply) t2).text());
    }

    @Test
    void test2_scriptedProviderExhaustedReturnsProviderError() {
        var sap = new ScriptedAiProvider(new LinkedList<>());
        AiTurn result = sap.chat(initialRequest).join();
        assertInstanceOf(AiTurn.ProviderError.class, result);
        assertEquals(AiTurn.ProviderError.Kind.PROVIDER, ((AiTurn.ProviderError) result).kind());
        assertTrue(((AiTurn.ProviderError) result).message().contains("exhausted"));
    }

    @Test
    void test3_scriptedProviderCallCountAndRequestAccessors() {
        var turns = new LinkedList<AiTurn>(List.of(
            new AiTurn.FinalReply("a", false),
            new AiTurn.FinalReply("b", false)
        ));
        var sap = new ScriptedAiProvider(turns);
        assertNull(sap.lastRequest());
        assertEquals(0, sap.callCount());
        assertEquals(0, sap.requestCount());

        sap.chat(initialRequest).join();
        assertEquals(1, sap.callCount());
        assertEquals(1, sap.requestCount());
        assertEquals(initialRequest, sap.lastRequest());
        assertEquals(initialRequest, sap.requestAt(0));

        ChatRequest req2 = new ChatRequest("m", 512, "s",
            List.of(ClaudeMessage.userText("q2")), List.of());
        sap.chat(req2).join();
        assertEquals(2, sap.callCount());
        assertEquals(req2, sap.requestAt(1));
        assertEquals(req2, sap.lastRequest());
    }

    // ─── Tests 4–12: AgentLoop ───────────────────────────────────────────────

    @Test
    void test4_immediateFinalReplyReturnedDirectly() {
        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(
            new AiTurn.FinalReply("hi", false)
        )));
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);
        assertInstanceOf(AiTurn.FinalReply.class, result);
        assertEquals("hi", ((AiTurn.FinalReply) result).text());
        assertEquals(1, sap.callCount());
    }

    @Test
    void test5_immediateProviderErrorPassThrough() {
        var err = new AiTurn.ProviderError(AiTurn.ProviderError.Kind.OVERLOADED, "529", Optional.empty());
        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(err)));
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);
        assertInstanceOf(AiTurn.ProviderError.class, result);
        assertEquals(AiTurn.ProviderError.Kind.OVERLOADED, ((AiTurn.ProviderError) result).kind());
        assertEquals(1, sap.callCount());
    }

    @Test
    void test6_singleToolRoundTrip() {
        // Register a stub tool that returns "mods: forge"
        String toolName = "list_installed_mods";
        ToolRegistry.injectForTests(new LinkedHashMap<>(Map.of(
            toolName, new StubTool(toolName, "stub", 0, null)
        )));

        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("u1", toolName, Map.of())
            )),
            new AiTurn.FinalReply("done", false)
        )));
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);

        assertInstanceOf(AiTurn.FinalReply.class, result);
        assertEquals("done", ((AiTurn.FinalReply) result).text());
        assertEquals(2, sap.callCount());

        // The second request's last message must be a user message with tool_result for u1
        ChatRequest secondCall = sap.requestAt(1);
        ClaudeMessage lastMsg = secondCall.messages().get(secondCall.messages().size() - 1);
        assertEquals("user", lastMsg.role);
        // Content must be a JsonArray containing a tool_result block
        assertTrue(lastMsg.content.isJsonArray(), "Last user message must be a JsonArray of tool_results");
        var arr = lastMsg.content.getAsJsonArray();
        assertEquals(1, arr.size());
        var block = arr.get(0).getAsJsonObject();
        assertEquals("tool_result", block.get("type").getAsString());
        assertEquals("u1", block.get("tool_use_id").getAsString());
    }

    @Test
    void test7_parallelToolExecutionPreservesOrder() throws Exception {
        // SlowOrderedTool: sleeps 'sleepMs' ms from the input
        String toolName = "slow";
        ToolRegistry.injectForTests(new LinkedHashMap<>(Map.of(
            toolName, new SlowOrderedTool(toolName)
        )));

        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("a", toolName, Map.of("sleepMs", 30.0)),
                new AiTurn.ToolUseBlock("b", toolName, Map.of("sleepMs", 20.0)),
                new AiTurn.ToolUseBlock("c", toolName, Map.of("sleepMs", 10.0))
            )),
            new AiTurn.FinalReply("done", false)
        )));
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);

        assertInstanceOf(AiTurn.FinalReply.class, result);
        assertEquals(2, sap.callCount());

        ChatRequest secondCall = sap.lastRequest();
        ClaudeMessage userMsg = secondCall.messages().get(secondCall.messages().size() - 1);
        assertTrue(userMsg.content.isJsonArray());
        var arr = userMsg.content.getAsJsonArray();
        assertEquals(3, arr.size());

        // Order must be a, b, c (input order preserved despite different sleep times)
        assertEquals("a", arr.get(0).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals("b", arr.get(1).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals("c", arr.get(2).getAsJsonObject().get("tool_use_id").getAsString());
    }

    @Test
    void test8_toolThrowIsErrorTrueLoopContinues() {
        String toolName = "error_tool";
        ToolRegistry.injectForTests(new LinkedHashMap<>(Map.of(
            toolName, new StubTool(toolName, "throws", 0,
                new ToolException(ToolException.Reason.FETCH_FAILED, "boom"))
        )));

        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("x", toolName, Map.of())
            )),
            new AiTurn.FinalReply("ok", false)
        )));
        var loop = new AgentLoop(sap);
        // Must NOT throw
        AiTurn result = assertDoesNotThrow(() -> loop.run(initialRequest));
        assertInstanceOf(AiTurn.FinalReply.class, result);
        assertEquals("ok", ((AiTurn.FinalReply) result).text());

        // The tool_result block for "x" must have is_error=true and content containing FETCH_FAILED
        ChatRequest secondCall = sap.lastRequest();
        ClaudeMessage userMsg = secondCall.messages().get(secondCall.messages().size() - 1);
        var arr = userMsg.content.getAsJsonArray();
        var block = arr.get(0).getAsJsonObject();
        assertEquals("tool_result", block.get("type").getAsString());
        assertEquals("x", block.get("tool_use_id").getAsString());
        assertTrue(block.has("is_error") && block.get("is_error").getAsBoolean(),
            "Expected is_error=true");
        // Content should contain FETCH_FAILED
        String contentStr = block.get("content").toString();
        assertTrue(contentStr.contains("FETCH_FAILED"), "Expected FETCH_FAILED in content, got: " + contentStr);
    }

    @Test
    void test9_unknownToolIsErrorTrueLoopContinues() {
        // ToolRegistry has no "nonexistent_tool"
        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("y", "nonexistent_tool", Map.of())
            )),
            new AiTurn.FinalReply("ok", false)
        )));
        var loop = new AgentLoop(sap);
        AiTurn result = assertDoesNotThrow(() -> loop.run(initialRequest));
        assertInstanceOf(AiTurn.FinalReply.class, result);

        ChatRequest secondCall = sap.lastRequest();
        ClaudeMessage userMsg = secondCall.messages().get(secondCall.messages().size() - 1);
        var arr = userMsg.content.getAsJsonArray();
        var block = arr.get(0).getAsJsonObject();
        assertEquals("y", block.get("tool_use_id").getAsString());
        assertTrue(block.has("is_error") && block.get("is_error").getAsBoolean(),
            "Expected is_error=true for unknown tool");
        String contentStr = block.get("content").toString();
        assertTrue(contentStr.contains("UNKNOWN_TOOL"), "Expected UNKNOWN_TOOL in content, got: " + contentStr);
    }

    @Test
    void test10_iterationCapSix() {
        // Provider returns ToolUses on iters 0..5 (six consecutive tool uses)
        String toolName = "cap_tool";
        ToolRegistry.injectForTests(new LinkedHashMap<>(Map.of(
            toolName, new StubTool(toolName, "ok", 0, null)
        )));

        var turns = new LinkedList<AiTurn>();
        for (int i = 0; i < 6; i++) {
            turns.add(new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("id" + i, toolName, Map.of())
            )));
        }
        var sap = new ScriptedAiProvider(turns);
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);

        // After 6 provider calls all returning ToolUses, must return ITERATION_CAP
        assertInstanceOf(AiTurn.ProviderError.class, result);
        assertEquals(AiTurn.ProviderError.Kind.ITERATION_CAP, ((AiTurn.ProviderError) result).kind());
        assertEquals(6, sap.callCount(), "Provider must be called exactly 6 times");
    }

    @Test
    void test11_iterationCapAllows5ToolsThenFinalReply() {
        String toolName = "t";
        ToolRegistry.injectForTests(new LinkedHashMap<>(Map.of(
            toolName, new StubTool(toolName, "ok", 0, null)
        )));

        var turns = new LinkedList<AiTurn>();
        for (int i = 0; i < 5; i++) {
            turns.add(new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("id" + i, toolName, Map.of())
            )));
        }
        turns.add(new AiTurn.FinalReply("done", false));

        var sap = new ScriptedAiProvider(turns);
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);

        assertInstanceOf(AiTurn.FinalReply.class, result);
        assertEquals("done", ((AiTurn.FinalReply) result).text());
        assertEquals(6, sap.callCount(), "Provider must be called exactly 6 times");
    }

    @Test
    void test12_providerErrorMidLoopPassThrough() {
        String toolName = "m";
        ToolRegistry.injectForTests(new LinkedHashMap<>(Map.of(
            toolName, new StubTool(toolName, "ok", 0, null)
        )));

        var err = new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.RATE_LIMITED,
            "429",
            Optional.of(Duration.ofSeconds(2))
        );
        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(new AiTurn.ToolUseBlock("m1", toolName, Map.of()))),
            err
        )));
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);

        assertInstanceOf(AiTurn.ProviderError.class, result);
        var pe = (AiTurn.ProviderError) result;
        assertEquals(AiTurn.ProviderError.Kind.RATE_LIMITED, pe.kind());
        assertEquals("429", pe.message());
        assertEquals(Optional.of(Duration.ofSeconds(2)), pe.retryAfter());
        assertEquals(2, sap.callCount());
    }

    @Test
    void test13_toolRegistrySeamLooksUpRegisteredTool() {
        // Register a sentinel tool that returns a specific value
        String toolName = "sentinel";
        String sentinelValue = "SENTINEL_OUTPUT_12345";
        ToolRegistry.injectForTests(new LinkedHashMap<>(Map.of(
            toolName, new StubTool(toolName, "sentinel", 0, null, sentinelValue)
        )));

        var sap = new ScriptedAiProvider(new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("s1", toolName, Map.of())
            )),
            new AiTurn.FinalReply("found it", false)
        )));
        var loop = new AgentLoop(sap);
        AiTurn result = loop.run(initialRequest);
        assertInstanceOf(AiTurn.FinalReply.class, result);

        // Verify the content of the tool_result contains the sentinel value
        ChatRequest secondCall = sap.lastRequest();
        ClaudeMessage userMsg = secondCall.messages().get(secondCall.messages().size() - 1);
        var arr = userMsg.content.getAsJsonArray();
        var block = arr.get(0).getAsJsonObject();
        String contentStr = block.get("content").getAsString();
        assertTrue(contentStr.contains(sentinelValue),
            "Expected sentinel value in content, got: " + contentStr);
    }

    // ─── Stub helpers ─────────────────────────────────────────────────────────

    /** Simple tool stub that returns a fixed string or throws a fixed ToolException. */
    private static final class StubTool implements Tool {
        private final String name;
        private final String description;
        private final int sleepMs;
        private final ToolException toThrow;
        private final String returnValue;

        StubTool(String name, String description, int sleepMs, ToolException toThrow) {
            this(name, description, sleepMs, toThrow, "stub result");
        }

        StubTool(String name, String description, int sleepMs, ToolException toThrow, String returnValue) {
            this.name = name;
            this.description = description;
            this.sleepMs = sleepMs;
            this.toThrow = toThrow;
            this.returnValue = returnValue;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public JsonObject schema() { return new JsonObject(); }

        @Override
        public String invoke(JsonObject input) throws ToolException {
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (toThrow != null) throw toThrow;
            return returnValue;
        }
    }

    /** Tool that sleeps for 'sleepMs' from the input map — used for Test 7 ordering. */
    private static final class SlowOrderedTool implements Tool {
        private final String name;
        SlowOrderedTool(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public String description() { return "slow ordered tool"; }
        @Override public JsonObject schema() { return new JsonObject(); }

        @Override
        public String invoke(JsonObject input) throws ToolException {
            int ms = input.has("sleepMs") ? (int) input.get("sleepMs").getAsDouble() : 0;
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "done";
        }
    }
}
