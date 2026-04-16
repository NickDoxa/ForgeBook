package com.forgebook.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 unit tests for ModpackContextCache volatile-holder semantics.
 *
 * Phase 2 / Plan 04.
 */
class ModpackContextCacheTest {

    /** Reset cache after each test to avoid cross-test pollution. */
    @AfterEach
    void reset() {
        ModpackContextCache.set(Optional.empty());
    }

    @Test
    void defaultStateIsEmpty() {
        // Explicitly set to empty first to ensure deterministic state
        ModpackContextCache.set(Optional.empty());
        assertTrue(ModpackContextCache.get().isEmpty(),
            "Default cache state must be Optional.empty()");
    }

    @Test
    void setThenGetReturnsValue() {
        ModpackContext ctx = new ModpackContext("ATM9", "summary text");
        ModpackContextCache.set(Optional.of(ctx));

        assertTrue(ModpackContextCache.get().isPresent(), "Cache must be present after set");
        ModpackContext stored = ModpackContextCache.get().get();
        assertEquals("ATM9", stored.name());
        assertEquals("summary text", stored.summary());
    }

    @Test
    void setEmptyClearsValue() {
        // First populate
        ModpackContextCache.set(Optional.of(new ModpackContext("ATM9", "summary text")));
        assertTrue(ModpackContextCache.get().isPresent(), "Pre-condition: cache is populated");

        // Then clear
        ModpackContextCache.set(Optional.empty());
        assertTrue(ModpackContextCache.get().isEmpty(),
            "Cache must return Optional.empty() after set(Optional.empty())");
    }

    @Test
    void classIsFinalAndConstructorPrivate() throws Exception {
        assertTrue(Modifier.isFinal(ModpackContextCache.class.getModifiers()),
            "ModpackContextCache must be declared final (utility-class invariant)");
        assertTrue(
            Modifier.isPrivate(ModpackContextCache.class.getDeclaredConstructor().getModifiers()),
            "ModpackContextCache constructor must be private (utility-class invariant)");
    }
}
