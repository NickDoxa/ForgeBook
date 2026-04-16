package com.forgebook.ai;

import com.forgebook.ai.AiTurn.ProviderError.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the sealed AiTurn type (AI-01): sealed exhaustiveness, all three
 * permitted record variants, and the 7-value ProviderError.Kind enum.
 *
 * Note: Java 17 pattern matching in switch is a preview feature; we prove
 * sealed exhaustiveness via reflection (getPermittedSubclasses length == 3)
 * and via compile-time dispatch using instanceof chains instead.
 */
class AiTurnTest {

    // Helper using explicit instanceof dispatch - equivalent correctness proof
    // that all three variants are handled. In Java 21 this would be a sealed switch.
    private static String render(AiTurn t) {
        if (t instanceof AiTurn.FinalReply r) {
            return "final:" + r.text();
        } else if (t instanceof AiTurn.ToolUses u) {
            return "uses:" + u.uses().size();
        } else if (t instanceof AiTurn.ProviderError e) {
            return "err:" + e.kind();
        } else {
            // Unreachable because AiTurn is sealed — if this branch is hit, a new
            // subtype was added without updating this method (mirrors exhaustiveness check).
            throw new AssertionError("Unexpected AiTurn subtype: " + t.getClass());
        }
    }

    @Test
    void test1_aiTurnIsSealed() {
        assertTrue(AiTurn.class.isSealed(), "AiTurn must be a sealed interface");
        assertEquals(3, AiTurn.class.getPermittedSubclasses().length,
                "AiTurn must have exactly 3 permitted subclasses");

        // Verify the permitted subclasses are the three expected records
        Set<String> permittedNames = Arrays.stream(AiTurn.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
        assertTrue(permittedNames.contains("FinalReply"), "FinalReply must be permitted");
        assertTrue(permittedNames.contains("ToolUses"), "ToolUses must be permitted");
        assertTrue(permittedNames.contains("ProviderError"), "ProviderError must be permitted");
    }

    @Test
    void test2_finalReply() {
        AiTurn.FinalReply reply = new AiTurn.FinalReply("hello", false);
        assertEquals("hello", reply.text());
        assertFalse(reply.truncated());
        assertEquals("final:hello", render(reply));
    }

    @Test
    void test3_toolUses() {
        AiTurn.ToolUseBlock block = new AiTurn.ToolUseBlock("u1", "t", Map.of());
        AiTurn.ToolUses uses = new AiTurn.ToolUses(List.of(block));
        assertEquals(1, uses.uses().size());
        assertEquals("u1", uses.uses().get(0).id());
        assertEquals("uses:1", render(uses));
    }

    @Test
    void test4_providerError() {
        AiTurn.ProviderError err = new AiTurn.ProviderError(
                Kind.TRANSPORT, "boom", Optional.empty());
        assertEquals(Kind.TRANSPORT, err.kind());
        assertEquals("boom", err.message());
        assertTrue(err.retryAfter().isEmpty());
        assertEquals("err:TRANSPORT", render(err));
    }

    @Test
    void test4b_providerErrorWithRetryAfter() {
        Duration delay = Duration.ofSeconds(30);
        AiTurn.ProviderError err = new AiTurn.ProviderError(
                Kind.RATE_LIMITED, "too many", Optional.of(delay));
        assertEquals(Kind.RATE_LIMITED, err.kind());
        assertTrue(err.retryAfter().isPresent());
        assertEquals(delay, err.retryAfter().get());
    }

    @Test
    void test5_providerErrorKindHasExactly7Values() {
        Kind[] values = Kind.values();
        assertEquals(7, values.length, "ProviderError.Kind must have exactly 7 values");

        Set<Kind> expected = Set.of(
                Kind.TRANSPORT,
                Kind.PROVIDER,
                Kind.OVERLOADED,
                Kind.RATE_LIMITED,
                Kind.NOT_IMPLEMENTED,
                Kind.CIRCUIT_OPEN,
                Kind.ITERATION_CAP
        );
        Set<Kind> actual = Set.of(values);
        assertEquals(expected, actual, "ProviderError.Kind values must match spec exactly");
    }

    @Test
    void test6_allThreeVariantsHandledExhaustively() {
        // Create one of each variant and verify render() handles all without hitting
        // the AssertionError branch. This is the runtime equivalent of sealed exhaustiveness.
        AiTurn[] variants = {
            new AiTurn.FinalReply("x", true),
            new AiTurn.ToolUses(List.of()),
            new AiTurn.ProviderError(Kind.CIRCUIT_OPEN, "msg", Optional.empty())
        };
        for (AiTurn v : variants) {
            assertNotNull(render(v), "render() must handle variant: " + v.getClass().getSimpleName());
        }
        assertEquals("final:x", render(variants[0]));
        assertEquals("uses:0", render(variants[1]));
        assertEquals("err:CIRCUIT_OPEN", render(variants[2]));
    }
}
