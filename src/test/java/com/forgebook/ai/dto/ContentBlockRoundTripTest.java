package com.forgebook.ai.dto;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Gson round-trip correctness for all Anthropic DTO types and
 * that @SerializedName annotations pin wire field names to Anthropic spec.
 */
class ContentBlockRoundTripTest {

    private static final Gson GSON = new Gson();

    @Test
    void test1_textContentBlockRoundTrip() {
        ContentBlock original = new ContentBlock("text", "hi", null, null, null, null, null, null);
        String json = GSON.toJson(original);
        ContentBlock restored = GSON.fromJson(json, ContentBlock.class);

        assertEquals("text", restored.type);
        assertEquals("hi", restored.text);
        assertNull(restored.name);
        assertNull(restored.input);
        assertNull(restored.id);
        assertNull(restored.toolUseId);
        assertNull(restored.content);
        assertNull(restored.isError);
    }

    @Test
    void test2_toolUseContentBlockRoundTrip() {
        String json = "{\"type\":\"tool_use\",\"id\":\"u1\",\"name\":\"fetch\",\"input\":{\"url\":\"x\"}}";
        ContentBlock block = GSON.fromJson(json, ContentBlock.class);

        assertEquals("tool_use", block.type);
        assertEquals("u1", block.id);
        assertEquals("fetch", block.name);
        assertNotNull(block.input);

        // Re-serialize and verify input is preserved
        String reJson = GSON.toJson(block);
        ContentBlock restored = GSON.fromJson(reJson, ContentBlock.class);
        assertEquals("u1", restored.id);
        assertEquals("fetch", restored.name);
        assertNotNull(restored.input);
        assertTrue(restored.input.isJsonObject());
        assertEquals("x", restored.input.getAsJsonObject().get("url").getAsString());
    }

    @Test
    void test3_toolResultContentBlockRoundTrip() {
        String json = "{\"type\":\"tool_result\",\"tool_use_id\":\"u1\",\"content\":\"done\",\"is_error\":false}";
        ContentBlock block = GSON.fromJson(json, ContentBlock.class);

        assertEquals("tool_result", block.type);
        assertEquals("u1", block.toolUseId);
        assertNotNull(block.content);
        assertEquals("done", block.content.getAsString());
        assertEquals(Boolean.FALSE, block.isError);
    }

    @Test
    void test4_deserializeRealAnthropicResponseFixture() {
        String json = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":10,"output_tokens":2}}""";

        ClaudeResponse response = GSON.fromJson(json, ClaudeResponse.class);

        assertEquals("end_turn", response.stopReason());
        assertNotNull(response.content());
        assertEquals(1, response.content().size());
        assertEquals("hello", response.content().get(0).text);
    }

    @Test
    void test5_claudeRequestSerializesWithSnakeCaseMaxTokens() {
        ClaudeRequest req = new ClaudeRequest("claude-haiku-4-5", 1024, "sys", null, null);
        String json = GSON.toJson(req);

        assertTrue(json.contains("\"max_tokens\":1024"),
                "ClaudeRequest must serialize maxTokens as \"max_tokens\" — got: " + json);
        assertFalse(json.contains("\"maxTokens\""),
                "ClaudeRequest must NOT serialize as \"maxTokens\" — got: " + json);
    }

    @Test
    void test6_deserializeClaudeErrorBody() {
        String json = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad request\"}}";
        ClaudeError err = GSON.fromJson(json, ClaudeError.class);

        assertEquals("error", err.type);
        assertNotNull(err.error);
        assertEquals("invalid_request_error", err.error.type);
        assertEquals("bad request", err.error.message);
    }
}
