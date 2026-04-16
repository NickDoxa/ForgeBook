package com.forgebook.safety;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

    private static ServerPlayer playerMock(UUID uuid, boolean isOp) {
        ServerPlayer p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.hasPermissions(2)).thenReturn(isOp);
        return p;
    }

    @Test
    void killSwitchOn_deniesWithDisabled_beforeAnyOtherCheck() {
        KillSwitch.setDisabled(true);
        ConfigSnapshot snap = snapWith(false, 60);
        ServerPlayer op = playerMock(UUID.randomUUID(), true);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, op, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Denied.class, r);
        assertEquals(ErrorCode.DISABLED, ((Authorizer.Denied) r).code());
    }

    @Test
    void nullSender_deniesWithForbidden() {
        ConfigSnapshot snap = snapWith(false, 60);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, null, RequestKind.ITEM, limiter);
        assertInstanceOf(Authorizer.Denied.class, r);
        assertEquals(ErrorCode.FORBIDDEN, ((Authorizer.Denied) r).code());
    }

    @Test
    void opOnlyAndNonOp_deniesWithForbidden() {
        ConfigSnapshot snap = snapWith(true, 60);
        ServerPlayer nonOp = playerMock(UUID.randomUUID(), false);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, nonOp, RequestKind.CHAT_UI, limiter);
        assertInstanceOf(Authorizer.Denied.class, r);
        assertEquals(ErrorCode.FORBIDDEN, ((Authorizer.Denied) r).code());
    }

    @Test
    void nonOpAndRateLimitExhausted_deniesWithRateLimited() {
        ConfigSnapshot snap = snapWith(false, 1);  // 1 req/min
        ServerPlayer nonOp = playerMock(UUID.randomUUID(), false);
        RateLimiter limiter = new RateLimiter(1);
        // First call consumes the token.
        Authorizer.Result first = Authorizer.authorize(snap, nonOp, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Allowed.class, first);
        // Second call: bucket empty.
        Authorizer.Result second = Authorizer.authorize(snap, nonOp, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Denied.class, second);
        Authorizer.Denied d = (Authorizer.Denied) second;
        assertEquals(ErrorCode.RATE_LIMITED, d.code());
        assertTrue(d.humanReadable().contains("Try again in"),
            "retry-after message must explain when; got: " + d.humanReadable());
    }

    @Test
    void opAndOpOnly_allowed() {
        ConfigSnapshot snap = snapWith(true, 60);
        ServerPlayer op = playerMock(UUID.randomUUID(), true);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Result r = Authorizer.authorize(snap, op, RequestKind.ASK, limiter);
        assertInstanceOf(Authorizer.Allowed.class, r);
    }

    @Test
    void opBypassesRateLimit_evenWhenLimiterIsExhausted() {
        ConfigSnapshot snap = snapWith(false, 1);  // 1 req/min
        ServerPlayer op = playerMock(UUID.randomUUID(), true);
        RateLimiter limiter = new RateLimiter(1);
        // OPs skip tryAcquire entirely — 10 consecutive calls all Allowed.
        for (int i = 0; i < 10; i++) {
            assertInstanceOf(Authorizer.Allowed.class,
                Authorizer.authorize(snap, op, RequestKind.ASK, limiter),
                "call " + i + " should be Allowed for OP");
        }
    }

    @Test
    void humanReadableIsCannedLiteral_noUserInputConcat() {
        ConfigSnapshot snap = snapWith(true, 60);
        ServerPlayer nonOp = playerMock(UUID.randomUUID(), false);
        RateLimiter limiter = new RateLimiter(60);
        Authorizer.Denied d = (Authorizer.Denied)
            Authorizer.authorize(snap, nonOp, RequestKind.ASK, limiter);
        // The message must be a fixed string — no UUIDs, message text, or stack fragments.
        assertEquals("ForgeBook is OP-only on this server.", d.humanReadable());
    }
}
