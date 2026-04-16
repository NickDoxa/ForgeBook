package com.forgebook.tool;

import com.forgebook.tool.ToolException.Reason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies ToolException (TOOL-01): typed Reason enum, message formatting,
 * and checked Exception inheritance. Mirrors UnsafeUrlException test idiom
 * (one assertion per Reason value, one structural check per spec point).
 */
class ToolExceptionTest {

    @Test
    void test1_reasonPreserved() {
        ToolException ex = new ToolException(Reason.NO_DOCS_URL, "x");
        assertEquals(Reason.NO_DOCS_URL, ex.reason());
    }

    @Test
    void test2_messageContainsReasonAndDetail() {
        ToolException ex = new ToolException(Reason.UNKNOWN_TOOL, "list_mods");
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("UNKNOWN_TOOL"),
                "getMessage() must contain reason name 'UNKNOWN_TOOL', got: " + msg);
        assertTrue(msg.contains("list_mods"),
                "getMessage() must contain detail 'list_mods', got: " + msg);
    }

    @Test
    void test3_reasonHasExactly5Values() {
        Reason[] values = Reason.values();
        assertEquals(5, values.length,
                "ToolException.Reason must have exactly 5 values");

        // Verify all expected names exist
        assertNotNull(Reason.valueOf("UNKNOWN_TOOL"));
        assertNotNull(Reason.valueOf("INVALID_INPUT"));
        assertNotNull(Reason.valueOf("NO_DOCS_URL"));
        assertNotNull(Reason.valueOf("FETCH_FAILED"));
        assertNotNull(Reason.valueOf("UPSTREAM_TIMEOUT"));
    }

    @Test
    void test4_isCheckedExceptionSubclass() {
        assertEquals(Exception.class, ToolException.class.getSuperclass(),
                "ToolException must directly extend Exception (checked exception)");
    }

    @Test
    void test5_toolResultHas3Components() throws NoSuchMethodException {
        // Verify ToolResult record has exactly toolUseId, content, isError components
        ToolResult result = new ToolResult("uid1", "content text", false);
        assertEquals("uid1", result.toolUseId());
        assertEquals("content text", result.content());
        assertFalse(result.isError());

        // Also verify isError=true case
        ToolResult errResult = new ToolResult("uid2", "error detail", true);
        assertTrue(errResult.isError());
    }
}
