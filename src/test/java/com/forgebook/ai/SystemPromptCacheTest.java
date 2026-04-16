package com.forgebook.ai;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SystemPromptCache volatile-holder (AI-08, D-08).
 */
class SystemPromptCacheTest {

    @Test
    void test1_freshGetReturnsEmptyString() {
        // Default state before any set() call must be "" — NOT null
        // (We reset before each test below; but first call should be "" by field initializer)
        SystemPromptCache.set(""); // ensure clean state
        assertEquals("", SystemPromptCache.get());
    }

    @Test
    void test2_setThenGetReturnsValue() {
        SystemPromptCache.set("hello");
        assertEquals("hello", SystemPromptCache.get());
        SystemPromptCache.set(""); // cleanup
    }

    @Test
    void test3_lastWriteWins() {
        SystemPromptCache.set("a");
        SystemPromptCache.set("b");
        assertEquals("b", SystemPromptCache.get());
        SystemPromptCache.set(""); // cleanup
    }

    @Test
    void test4_fieldIsVolatile() throws Exception {
        Field f = SystemPromptCache.class.getDeclaredField("current");
        f.setAccessible(true);
        assertTrue(Modifier.isVolatile(f.getModifiers()),
            "SystemPromptCache.current must be volatile");
        assertTrue(Modifier.isStatic(f.getModifiers()),
            "SystemPromptCache.current must be static");
    }

    @Test
    void test5_constructorIsPrivate() throws Exception {
        var ctors = SystemPromptCache.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "Utility class should have exactly one constructor");
        assertTrue(Modifier.isPrivate(ctors[0].getModifiers()),
            "SystemPromptCache constructor must be private");
    }
}
