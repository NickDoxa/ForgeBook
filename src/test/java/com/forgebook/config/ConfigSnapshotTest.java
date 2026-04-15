package com.forgebook.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigSnapshotTest {

    @Test void record_preservesAllNineFields() {
        ConfigSnapshot s = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("sk-ant-x"),
            "claude-haiku",
            Optional.of("modpack-123"),
            new ApiKey("cf-y"),
            true, 5, false, 1);
        assertEquals(AiProviderKind.ANTHROPIC, s.aiProvider());
        assertEquals("sk-ant-x", s.aiApiKey().raw());
        assertEquals("claude-haiku", s.aiModel());
        assertEquals(Optional.of("modpack-123"), s.curseforgeModpackId());
        assertEquals("cf-y", s.curseforgeApiKey().raw());
        assertTrue(s.opOnly());
        assertEquals(5, s.rateLimitPerMinute());
        assertFalse(s.enableWebSearch());
        assertEquals(1, s.configVersion());
    }

    @Test void toString_doesNotLeakApiKey_becauseApiKey_toString_redacts() {
        ConfigSnapshot s = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey("sk-ant-LEAK"),
            "m", Optional.empty(), new ApiKey("cf-LEAK"),
            true, 5, false, 1);
        String rendered = s.toString();
        assertFalse(rendered.contains("sk-ant-LEAK"), rendered);
        assertFalse(rendered.contains("cf-LEAK"), rendered);
        assertTrue(rendered.contains("<redacted>"));
    }

    @Test void holder_isVolatile_singleRefSwap() {
        ConfigSnapshot a = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC, new ApiKey("1"), "m",
            Optional.empty(), new ApiKey("2"), true, 5, false, 1);
        ConfigSnapshot b = new ConfigSnapshot(
            AiProviderKind.OPENAI, new ApiKey("3"), "m2",
            Optional.empty(), new ApiKey("4"), false, 10, true, 1);
        ConfigHolder.set(a);
        assertSame(a, ConfigHolder.get());
        ConfigHolder.set(b);
        assertSame(b, ConfigHolder.get());
    }
}
