package com.forgebook.safety;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Authorizer}. Uses the package-private
 * {@code authorize(ConfigSnapshot, UUID, boolean, RequestKind, RateLimiter)} overload
 * to avoid mocking {@code ServerPlayer} — per CLAUDE.md, MC classes must not be
 * mocked (their supertype chain cannot be initialized outside the game harness).
 * The public {@code authorize(snap, ServerPlayer, kind, limiter)} entry point is a
 * thin unpacker that pulls UUID + isOp then delegates to the overload under test.
 */
class AuthorizerTest {

    @AfterEach
    void resetKillSwitch() {
        KillSwitch.setDisabled(false);
    }

    private static ConfigSnapshot snapWith(boolean opOnly, int rpm) {
        ConfigSnapshot snap = mock(ConfigSnapshot.class);
        when(snap.opOnly()).thenReturn(opOnly);
        when(snap.rateLimitPerMinute()).thenReturn(rpm);
        return snap;
    }

    @Test
    void killSwitchOn_deniesWithDisabled_beforeAnyOtherCheck() {
        KillSwitch.setDisabled(true);
        ConfigSnapshot snap = snapWith(false, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, UUID.randomUUID(), true, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Denied.class, r);
        assertEquals(ErrorCode.DISABLED, ((Authorizer.Denied) r).code());
    }

    @Test
    void nullSender_deniesWithForbidden() {
        ConfigSnapshot snap = snapWith(false, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, (UUID) null, false, RequestKind.ITEM, limiter);
        assertInstanceOf(Authorizer.Denied.class, r);
        assertEquals(ErrorCode.FORBIDDEN, ((Authorizer.Denied) r).code());
    }

    @Test
    void opOnlyAndNonOp_deniesWithForbidden() {
        ConfigSnapshot snap = snapWith(true, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, UUID.randomUUID(), false, RequestKind.CHAT_UI, limiter);
        assertInstanceOf(Authorizer.Denied.class, r);
        assertEquals(ErrorCode.FORBIDDEN, ((Authorizer.Denied) r).code());
    }

    @Test
    void nonOpAndRateLimitExhausted_deniesWithRateLimited() {
        ConfigSnapshot snap = snapWith(false, 1);  // 1 req/min
        UUID uuid = UUID.randomUUID();
        RateLimiter limiter = new RateLimiter(1);
        // First call consumes the token.
        Authorizer.Result first = Authorizer.authorize(snap, uuid, false, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Allowed.class, first);
        // Second call: bucket empty.
        Authorizer.Result second = Authorizer.authorize(snap, uuid, false, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Denied.class, second);
        Authorizer.Denied d = (Authorizer.Denied) second;
        assertEquals(ErrorCode.RATE_LIMITED, d.code());
        assertTrue(d.humanReadable().contains("Try again in"),
            "retry-after message must explain when; got: " + d.humanReadable());
    }

    @Test
    void opAndOpOnly_allowed() {
        ConfigSnapshot snap = snapWith(true, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, UUID.randomUUID(), true, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Allowed.class, r);
    }

    @Test
    void opBypassesRateLimit_evenWhenLimiterIsExhausted() {
        ConfigSnapshot snap = snapWith(false, 1);  // 1 req/min
        UUID opUuid = UUID.randomUUID();
        RateLimiter limiter = new RateLimiter(1);
        // OPs skip tryAcquire entirely — 10 consecutive calls all Allowed.
        for (int i = 0; i < 10; i++) {
            assertInstanceOf(Authorizer.Allowed.class,
                Authorizer.authorize(snap, opUuid, true, RequestKind.ASK, limiter),
                "call " + i + " should be Allowed for OP");
        }
    }

    @Test
    void humanReadableIsCannedLiteral_noUserInputConcat() {
        ConfigSnapshot snap = snapWith(true, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Denied d = (Authorizer.Denied)
            Authorizer.authorize(snap, UUID.randomUUID(), false, RequestKind.ASK, limiter);
        // The message must be a fixed string — no UUIDs, message text, or stack fragments.
        assertEquals("ForgeBook is OP-only on this server.", d.humanReadable());
    }

    // ================================================================================
    // Phase 5 / REL-02: Denied now carries a Component feedback alongside humanReadable
    // (Option A split — wire-format String preserved, new Component for command surface).
    // Component.translatable(key).getString() returns the key verbatim when no language
    // pack is loaded (JUnit env), which is the standard assertion pattern per Phase 4.
    // ================================================================================

    @Test
    void killSwitchOn_feedback_carriesDisabledKey() {
        KillSwitch.setDisabled(true);
        ConfigSnapshot snap = snapWith(false, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Denied d = (Authorizer.Denied)
            Authorizer.authorize(snap, UUID.randomUUID(), true, RequestKind.ASK, limiter);
        assertNotNull(d.feedback(), "Denied must carry a non-null Component feedback");
        assertEquals("forgebook.command.denied.disabled", d.feedback().getString(),
            "feedback Component must translate to the disabled key");
    }

    @Test
    void nullSender_feedback_carriesNotPlayerKey() {
        ConfigSnapshot snap = snapWith(false, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Denied d = (Authorizer.Denied)
            Authorizer.authorize(snap, (UUID) null, false, RequestKind.ITEM, limiter);
        assertEquals("forgebook.command.denied.not_player", d.feedback().getString());
    }

    @Test
    void opOnlyAndNonOp_feedback_carriesForbiddenKey() {
        ConfigSnapshot snap = snapWith(true, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Denied d = (Authorizer.Denied)
            Authorizer.authorize(snap, UUID.randomUUID(), false, RequestKind.CHAT_UI, limiter);
        assertEquals("forgebook.command.denied.forbidden", d.feedback().getString());
    }

    @Test
    void rateLimited_feedback_carriesRateLimitedKeyAndHumanReadableRetryAfter() {
        ConfigSnapshot snap = snapWith(false, 1);
        UUID uuid = UUID.randomUUID();
        RateLimiter limiter = new RateLimiter(1);
        // First call consumes token.
        Authorizer.authorize(snap, uuid, false, RequestKind.ASK, limiter);
        // Second is rate-limited.
        Authorizer.Denied d = (Authorizer.Denied)
            Authorizer.authorize(snap, uuid, false, RequestKind.ASK, limiter);
        assertNotNull(d.feedback());
        // feedback carries the translation key; humanReadable still carries the inline
        // English fallback with the retry-after seconds for wire/audit use.
        assertTrue(d.feedback().toString().contains("forgebook.command.denied.rate_limited"),
            "feedback toString must reference the rate_limited key: " + d.feedback());
        assertTrue(d.humanReadable().contains("Try again in"),
            "humanReadable fallback must still describe retry-after: " + d.humanReadable());
    }

    @Test
    void denied_record_canonicalConstructor_accepts_threeArgs() {
        // Document the new Option A shape: (ErrorCode code, String humanReadable, Component feedback).
        Authorizer.Denied d = new Authorizer.Denied(
            ErrorCode.DISABLED,
            "ForgeBook is temporarily disabled by an operator.",
            Component.translatable("forgebook.command.denied.disabled"));
        assertEquals(ErrorCode.DISABLED, d.code());
        assertEquals("ForgeBook is temporarily disabled by an operator.", d.humanReadable());
        assertEquals("forgebook.command.denied.disabled", d.feedback().getString());
    }
}
