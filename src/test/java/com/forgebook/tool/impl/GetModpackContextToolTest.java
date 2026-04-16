package com.forgebook.tool.impl;

import com.forgebook.integration.ModpackContext;
import com.forgebook.integration.ModpackContextCache;
import com.forgebook.tool.ToolException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for GetModpackContextTool (Plan 02-05, Task 3 RED).
 * Uses ModpackContextCache.set() to inject test state.
 */
class GetModpackContextToolTest {

    @BeforeEach
    void clearCache() {
        ModpackContextCache.set(Optional.empty());
    }

    @AfterEach
    void resetCache() {
        ModpackContextCache.set(Optional.empty());
    }

    /** Test 21: name() == "get_modpack_context". */
    @Test
    void name_isCorrect() {
        assertEquals("get_modpack_context", new GetModpackContextTool().name());
    }

    /** Test 22: schema() is empty object {type:object, properties:{}, required:[]}. */
    @Test
    void schema_isEmptyObject() {
        JsonObject schema = new GetModpackContextTool().schema();
        assertEquals("object", schema.get("type").getAsString());
        assertTrue(schema.has("properties"), "Should have properties");
        assertEquals(0, schema.getAsJsonObject("properties").size(),
            "Properties should be empty");
        assertTrue(schema.has("required"), "Should have required array");
        assertEquals(0, schema.getAsJsonArray("required").size(),
            "Required should be empty array");
    }

    /** Test 23: When cache is empty, invoke returns {"modpack":"<not configured>"}. */
    @Test
    void invoke_emptyCacheReturnsNotConfigured() throws ToolException {
        ModpackContextCache.set(Optional.empty());
        String result = new GetModpackContextTool().invoke(new JsonObject());
        assertEquals("{\"modpack\":\"<not configured>\"}", result,
            "Empty cache should return not-configured sentinel");
    }

    /** Test 24: When cache has context, invoke returns JSON with name and summary. */
    @Test
    void invoke_populatedCache_returnsModpackData() throws ToolException {
        ModpackContextCache.set(Optional.of(new ModpackContext("ATM9", "All the Mods 9 summary text")));
        String result = new GetModpackContextTool().invoke(new JsonObject());
        JsonObject obj = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("ATM9", obj.get("name").getAsString(), "Should contain modpack name");
        assertEquals("All the Mods 9 summary text", obj.get("summary").getAsString(),
            "Should contain modpack summary");
    }
}
