package com.forgebook.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for ToolRegistry (Plan 02-05, Task 3 RED).
 */
class ToolRegistryTest {

    @AfterEach
    void reset() {
        ToolRegistry.resetForTests();
    }

    /** Test 25: After init, get(name) returns a tool instance for each of the 4 expected names. */
    @Test
    void init_andGet_returnsToolsForAllFourNames() {
        // init with null SafeHttpFetcher and null modList — tools use lazy/supplier defaults
        ToolRegistry.init(null, null);

        assertNotNull(ToolRegistry.get("list_installed_mods"), "Should have list_installed_mods");
        assertNotNull(ToolRegistry.get("fetch_mod_docs_page"), "Should have fetch_mod_docs_page");
        assertNotNull(ToolRegistry.get("web_search"), "Should have web_search");
        assertNotNull(ToolRegistry.get("get_modpack_context"), "Should have get_modpack_context");
    }

    /** Test 26: get("nonexistent_tool") throws IllegalArgumentException mentioning the bad name. */
    @Test
    void get_unknownTool_throwsWithBadNameInMessage() {
        ToolRegistry.init(null, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ToolRegistry.get("nonexistent_tool"));
        assertTrue(ex.getMessage().contains("nonexistent_tool"),
            "Exception message should mention the bad tool name");
    }

    /** Test 27: all() returns 4 tools in insertion order. */
    @Test
    void all_returnsToolsInInsertionOrder() {
        ToolRegistry.init(null, null);
        Collection<Tool> all = ToolRegistry.all();
        assertEquals(4, all.size(), "Should have exactly 4 tools");

        List<String> names = all.stream().map(Tool::name).toList();
        assertEquals("list_installed_mods", names.get(0), "First tool should be list_installed_mods");
        assertEquals("fetch_mod_docs_page", names.get(1), "Second tool should be fetch_mod_docs_page");
        assertEquals("web_search", names.get(2), "Third tool should be web_search");
        assertEquals("get_modpack_context", names.get(3), "Fourth tool should be get_modpack_context");
    }

    /** Test 28: ToolRegistry class has private constructor (utility-class invariant). */
    @Test
    void class_hasPrivateConstructor() throws NoSuchMethodException {
        var ctor = ToolRegistry.class.getDeclaredConstructor();
        assertFalse(java.lang.reflect.Modifier.isPublic(ctor.getModifiers()),
            "Constructor should not be public");
    }
}
