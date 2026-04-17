package com.forgebook.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import com.forgebook.safety.KillSwitch;
import com.forgebook.safety.StatsAccumulator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Five-branch unit coverage for {@link AdminSubcommands}.
 *
 * <h2>KillSwitch as real state</h2>
 * {@link KillSwitch} is a real static holder (Plan 01) that unit tests can exercise directly
 * without mocking — it's pure Java (an {@link java.util.concurrent.atomic.AtomicBoolean}).
 * Tests reset it in {@code @BeforeEach}/{@code @AfterEach}.
 *
 * <h2>StatsAccumulator via MockedStatic</h2>
 * Test 5 stubs {@link StatsAccumulator#render()} via {@link MockedStatic} to assert the
 * rendered string is forwarded as-is to {@code sendSuccess(..., false)}.
 *
 * <h2>Why *Internal seams</h2>
 * The public {@code executeDisable / executeEnable / executeStats} methods consume
 * {@link com.mojang.brigadier.context.CommandContext}&lt;
 * {@link net.minecraft.commands.CommandSourceStack}&gt; — both hostile to pure-JUnit
 * (CLAUDE.md "avoid mocking Minecraft classes"). The package-private {@code *Internal}
 * overloads take a {@link BiConsumer}&lt;String, Boolean&gt; send callback so tests can
 * capture the (text, broadcast) pair that production forwards to
 * {@code ctx.getSource().sendSuccess(() -> Component.literal(text), broadcast)}.
 */
class AdminSubcommandsTest {

    private Recorder recorder;

    @BeforeEach
    void setUp() {
        KillSwitch.setDisabled(false);
        recorder = new Recorder();
    }

    @AfterEach
    void tearDown() {
        KillSwitch.setDisabled(false);
    }

    // ================================================================================
    // Test 1: executeDisable flips KillSwitch from false→true and broadcasts.
    // Phase 5 / REL-02: BiConsumer String now carries a TRANSLATION KEY, not prose.
    // ================================================================================
    @Test
    void executeDisable_flips_killswitch_and_broadcasts() {
        assertFalse(KillSwitch.isDisabled(), "precondition: KillSwitch starts enabled");

        int rc = AdminSubcommands.executeDisableInternal("admin", recorder);

        assertEquals(1, rc, "executeDisable must return SINGLE_SUCCESS");
        assertTrue(KillSwitch.isDisabled(), "KillSwitch must be flipped to disabled");
        assertEquals(1, recorder.calls.size(), "exactly one send call");
        Recorder.Call call = recorder.calls.get(0);
        assertEquals("forgebook.command.disable.success", call.text,
            "disable success must emit the translation key, not prose");
        assertTrue(call.broadcast, "disable must broadcast (broadcast=true)");
    }

    // ================================================================================
    // Test 2: executeDisable when already disabled emits no-op message.
    // ================================================================================
    @Test
    void executeDisable_noop_message_when_already_disabled() {
        KillSwitch.setDisabled(true);

        int rc = AdminSubcommands.executeDisableInternal("admin", recorder);

        assertEquals(1, rc);
        assertTrue(KillSwitch.isDisabled(), "KillSwitch stays disabled");
        assertEquals(1, recorder.calls.size());
        Recorder.Call call = recorder.calls.get(0);
        assertEquals("forgebook.command.disable.already", call.text,
            "no-op disable must emit the already-disabled translation key");
        assertTrue(call.broadcast);
    }

    // ================================================================================
    // Test 3: executeEnable flips KillSwitch true→false and broadcasts.
    // ================================================================================
    @Test
    void executeEnable_flips_killswitch_and_broadcasts() {
        KillSwitch.setDisabled(true);

        int rc = AdminSubcommands.executeEnableInternal("admin", recorder);

        assertEquals(1, rc);
        assertFalse(KillSwitch.isDisabled(), "KillSwitch must be flipped to enabled");
        assertEquals(1, recorder.calls.size());
        Recorder.Call call = recorder.calls.get(0);
        assertEquals("forgebook.command.enable.success", call.text,
            "enable success must emit the translation key, not prose");
        assertTrue(call.broadcast, "enable must broadcast");
    }

    // ================================================================================
    // Test 4: executeEnable when already enabled emits no-op message.
    // ================================================================================
    @Test
    void executeEnable_noop_message_when_already_enabled() {
        assertFalse(KillSwitch.isDisabled(), "precondition");

        int rc = AdminSubcommands.executeEnableInternal("admin", recorder);

        assertEquals(1, rc);
        assertFalse(KillSwitch.isDisabled());
        assertEquals(1, recorder.calls.size());
        Recorder.Call call = recorder.calls.get(0);
        assertEquals("forgebook.command.enable.already", call.text,
            "no-op enable must emit the already-enabled translation key");
        assertTrue(call.broadcast);
    }

    // ================================================================================
    // Test 5: executeStats forwards StatsAccumulator.render() to sendSuccess(..., false).
    // ================================================================================
    @Test
    void executeStats_sends_StatsAccumulator_render_without_broadcast() {
        String rendered = "ForgeBook stats (test session):\n  total requests : 42\n";
        try (MockedStatic<StatsAccumulator> statsStatic = org.mockito.Mockito.mockStatic(StatsAccumulator.class)) {
            statsStatic.when(StatsAccumulator::render).thenReturn(rendered);

            int rc = AdminSubcommands.executeStatsInternal(recorder);

            assertEquals(1, rc);
            assertEquals(1, recorder.calls.size());
            Recorder.Call call = recorder.calls.get(0);
            assertEquals(rendered, call.text, "stats text must be StatsAccumulator.render() verbatim");
            assertFalse(call.broadcast, "stats must NOT broadcast (broadcast=false)");
        }
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------
    private static final class Recorder implements BiConsumer<String, Boolean> {
        final List<Call> calls = new ArrayList<>();

        @Override public void accept(String text, Boolean broadcast) {
            calls.add(new Call(text, broadcast));
        }

        static final class Call {
            final String text;
            final boolean broadcast;
            Call(String text, boolean broadcast) {
                this.text = text;
                this.broadcast = broadcast;
            }
        }
    }
}
