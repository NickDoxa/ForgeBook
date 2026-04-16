package com.forgebook.ai;

import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ToolDef;
import com.forgebook.config.ApiKey;
import com.forgebook.config.AiProviderKind;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.config.WebSearchProviderKind;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.forgebook.tool.ToolRegistry;
import com.forgebook.tool.impl.FetchModDocsPageTool;
import com.forgebook.util.AiExecutor;
import com.forgebook.util.SafeHttpFetcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end SC-5 integration test: missing-docs fallback through full AgentLoop.
 *
 * Tests the complete flow: empty URL → NO_DOCS_URL tool error → web_search fallback
 * → cited URL in final reply. Exercises Plan 05 tools + Plan 06 AgentLoop + Plan 07
 * AiDispatcher — all with ScriptedAiProvider (no network).
 *
 * TOOL-07 success criterion: SC-5 "Synthetic mod with empty getModURL() → missing-docs
 * fallback → WebSearchTool → final reply with source URL" is observably proven here.
 */
class AgentLoopE2ETest {

    private static final String FIXTURE_URL = "https://example.com/synthetic-mod/docs";

    @BeforeAll
    static void startExecutor() {
        AiExecutor.start(); // idempotent (Phase 1 invariant)
    }

    @BeforeEach
    void seedConfigAndTools() {
        ConfigHolder.set(testSnapshot(true));
        ToolRegistry.injectForTests(Map.of(
            // Real FetchModDocsPageTool — throws ToolException(NO_DOCS_URL) for empty URL
            // before any network call (safe for offline tests per Plan 05 design).
            "fetch_mod_docs_page", new FetchModDocsPageTool((SafeHttpFetcher) null),
            // Stub web_search — returns a fixed fixture URL without network.
            "web_search",          new StubWebSearchTool(FIXTURE_URL),
            // Stub list_installed_mods — returns empty JSON array.
            "list_installed_mods", new StubTool("list_installed_mods", "[]"),
            // Stub get_modpack_context — returns empty object.
            "get_modpack_context", new StubTool("get_modpack_context", "{}")
        ));
    }

    @AfterEach
    void resetConfigAndTools() {
        ToolRegistry.resetForTests();
        ConfigHolder.set(null);
    }

    // ---- Test 1 (SC-5 happy fallback path) ----

    /**
     * SC-5: empty URL → NO_DOCS_URL error → web_search fallback → cited URL in final reply.
     *
     * Queue: ToolUses(fetch_mod_docs_page, url="") → ToolUses(web_search) → FinalReply(cited URL)
     */
    @Test
    void sc5_missingDocsFallbackToWebSearchProducesCitedReply() {
        Queue<AiTurn> queue = new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("t1", "fetch_mod_docs_page", Map.of("url", "")))),
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("t2", "web_search", Map.of("query", "synthetic mod usage")))),
            new AiTurn.FinalReply("To use this item, see " + FIXTURE_URL, false)
        ));
        ScriptedAiProvider provider = new ScriptedAiProvider(queue);
        AgentLoop loop = new AgentLoop(provider);

        AiTurn outcome = loop.run(initialRequest());

        // Outcome must be FinalReply
        assertInstanceOf(AiTurn.FinalReply.class, outcome,
            "expected FinalReply, got " + outcome);
        AiTurn.FinalReply reply = (AiTurn.FinalReply) outcome;
        assertTrue(reply.text().contains(FIXTURE_URL),
            "reply must cite the source URL; got: " + reply.text());

        // Exactly 3 provider calls (fetch fail → web_search → final)
        assertEquals(3, provider.callCount(),
            "expected 3 provider calls (fetch fail → web_search → final)");

        // Validate fallback signal: 2nd ChatRequest's last message has is_error=true
        // tool_result for t1 with NO_DOCS_URL content
        ChatRequest secondCall = provider.requestAt(1); // 0-indexed
        ClaudeMessage lastMsgAfterFetch = lastMessage(secondCall);
        assertToolResultInMessage(lastMsgAfterFetch, "t1", true, "NO_DOCS_URL");

        // Validate search result: 3rd ChatRequest's last message has tool_result for t2
        // with the fixture URL and is_error=false
        ChatRequest thirdCall = provider.requestAt(2);
        ClaudeMessage lastMsgAfterSearch = lastMessage(thirdCall);
        assertToolResultInMessage(lastMsgAfterSearch, "t2", false, FIXTURE_URL);
    }

    // ---- Test 2 (SC-5 through AiDispatcher) ----

    /**
     * Same SC-5 flow driven via AiDispatcher.dispatcherForTests — proves the wiring
     * from AiDispatcher through AgentLoop to tools is live.
     */
    @Test
    void sc5_throughAiDispatcherProducesReply() {
        Queue<AiTurn> queue = new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("t1", "fetch_mod_docs_page", Map.of("url", "")))),
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("t2", "web_search", Map.of("query", "x")))),
            new AiTurn.FinalReply("Cited: " + FIXTURE_URL, false)
        ));
        AiDispatcher dispatcher = AiDispatcher.dispatcherForTests(new ScriptedAiProvider(queue));
        AiDispatcher.Result result = dispatcher.dispatch(
            new DispatchContext("How do I use this item?", null, RequestKind.CHAT_UI));

        assertInstanceOf(AiDispatcher.Reply.class, result,
            "expected Reply, got " + result);
        AiDispatcher.Reply reply = (AiDispatcher.Reply) result;
        assertTrue(reply.text().contains(FIXTURE_URL),
            "reply must contain the cited URL: " + reply.text());
    }

    // ---- Test 3 (TOOL-07 + AI-05: 6 consecutive fetch failures → ITERATION_CAP) ----

    /**
     * Queue 6 ToolUses with empty URL (fetch_mod_docs_page). AgentLoop exhausts 6 iterations
     * and returns ITERATION_CAP. AiDispatcher maps to Error(PROVIDER, "...6 iterations...").
     */
    @Test
    void iterationCap_sixConsecutiveFetchFailsTriggersIterationCap() {
        Queue<AiTurn> queue = new LinkedList<>();
        for (int i = 0; i < 6; i++) {
            queue.add(new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("t" + i, "fetch_mod_docs_page", Map.of("url", "")))));
        }
        AiDispatcher dispatcher = AiDispatcher.dispatcherForTests(new ScriptedAiProvider(queue));
        AiDispatcher.Result result = dispatcher.dispatch(
            new DispatchContext("X", null, RequestKind.CHAT_UI));

        assertInstanceOf(AiDispatcher.Error.class, result,
            "expected Error after iteration cap, got " + result);
        AiDispatcher.Error err = (AiDispatcher.Error) result;
        assertEquals(com.forgebook.network.packet.ChatErrorPacket.ErrorCode.PROVIDER, err.code());
        assertTrue(err.humanReadable().contains("6"),
            "humanReadable must mention the iteration cap: " + err.humanReadable());
    }

    // ---- Test 4 (web_search disabled → tool fails gracefully, loop continues) ----

    /**
     * D-12 graceful failure: when web_search is disabled, the tool throws ToolException
     * (INVALID_INPUT — "web_search disabled by operator"). AgentLoop wraps it in
     * is_error=true tool_result and continues to next iteration, which returns FinalReply.
     */
    @Test
    void webSearchDisabled_toolFailsGracefully_loopContinues() {
        ConfigHolder.set(testSnapshot(false)); // enableWebSearch = false
        Queue<AiTurn> queue = new LinkedList<>(List.of(
            new AiTurn.ToolUses(List.of(
                new AiTurn.ToolUseBlock("t1", "web_search", Map.of("query", "x")))),
            new AiTurn.FinalReply("Done despite disabled search", false)
        ));
        AgentLoop loop = new AgentLoop(new ScriptedAiProvider(queue));
        AiTurn outcome = loop.run(initialRequest());

        // Loop must continue past tool failure and return FinalReply
        assertInstanceOf(AiTurn.FinalReply.class, outcome,
            "expected FinalReply after graceful web_search failure, got " + outcome);
        assertEquals("Done despite disabled search", ((AiTurn.FinalReply) outcome).text());
    }

    // ---- Helpers ----

    private ChatRequest initialRequest() {
        return new ChatRequest(
            "claude-haiku-4-5",
            1024,
            "test system prompt",
            new ArrayList<>(List.of(ClaudeMessage.userText("test question"))),
            List.of() // ToolDefs not exercised by ScriptedAiProvider
        );
    }

    private static ConfigSnapshot testSnapshot(boolean enableWebSearch) {
        return new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("test-key"),
            "claude-haiku-4-5",
            1024,
            Optional.empty(),
            new ApiKey(""),
            true,
            10,
            enableWebSearch,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
    }

    private static ClaudeMessage lastMessage(ChatRequest req) {
        List<ClaudeMessage> msgs = req.messages();
        return msgs.get(msgs.size() - 1);
    }

    /**
     * Assert that the given ClaudeMessage (expected to be a user message with tool_result
     * blocks in its JsonArray content) contains a tool_result block matching the given
     * tool_use_id, is_error flag, and contentSubstring.
     */
    private static void assertToolResultInMessage(
            ClaudeMessage msg, String useId, boolean expectedIsError, String contentSubstring) {
        assertTrue(msg.content instanceof JsonArray,
            "expected JsonArray content in tool_result message, got " +
            (msg.content == null ? "null" : msg.content.getClass().getSimpleName()));

        JsonArray blocks = (JsonArray) msg.content;
        boolean found = false;
        for (JsonElement el : blocks) {
            if (!(el instanceof JsonObject block)) continue;
            if (!block.has("tool_use_id")) continue;
            if (!useId.equals(block.get("tool_use_id").getAsString())) continue;

            found = true;
            // Check is_error flag
            if (expectedIsError) {
                assertTrue(block.has("is_error") && block.get("is_error").getAsBoolean(),
                    "tool_result for " + useId + " must have is_error=true; block=" + block);
            } else {
                // Success: is_error absent or false
                assertFalse(block.has("is_error") && block.get("is_error").getAsBoolean(),
                    "tool_result for " + useId + " must NOT have is_error=true; block=" + block);
            }
            // Check content substring
            String content = block.has("content") ? block.get("content").toString() : "";
            assertTrue(content.contains(contentSubstring),
                "tool_result content for " + useId + " must contain '" + contentSubstring +
                "'; content=" + content);
            break;
        }
        assertTrue(found, "no tool_result block found for tool_use_id=" + useId +
            " in message content: " + blocks);
    }

    // ---- Stub tool implementations ----

    /**
     * Stub web_search tool returning a single hardcoded search result with the fixture URL.
     * Does not make any network calls.
     */
    private static final class StubWebSearchTool implements Tool {
        private final String fixtureUrl;
        StubWebSearchTool(String fixtureUrl) { this.fixtureUrl = fixtureUrl; }

        @Override public String name() { return "web_search"; }
        @Override public String description() { return "stub web search"; }
        @Override public JsonObject schema() { return new JsonObject(); }

        @Override
        public String invoke(JsonObject input) {
            // Return a JSON array with one SearchResult containing the fixture URL.
            // Matches the format WebSearchTool would return: [{title, snippet, url}]
            return "[{\"title\":\"Synthetic Mod Docs\",\"snippet\":\"Usage guide\"," +
                   "\"url\":\"" + fixtureUrl + "\"}]";
        }
    }

    /**
     * Minimal stub tool returning a fixed JSON string.
     */
    private static final class StubTool implements Tool {
        private final String toolName;
        private final String response;
        StubTool(String name, String response) {
            this.toolName = name;
            this.response = response;
        }
        @Override public String name() { return toolName; }
        @Override public String description() { return "stub: " + toolName; }
        @Override public JsonObject schema() { return new JsonObject(); }
        @Override public String invoke(JsonObject input) { return response; }
    }
}
