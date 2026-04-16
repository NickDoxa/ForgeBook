package com.forgebook.network.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.network.packet.ChatRequestPacket;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.RateLimiter;
import com.forgebook.safety.RateLimiterHolder;
import com.forgebook.safety.RequestAuditLogger;
import com.forgebook.util.AiExecutor;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for SAFE-06 authorization precheck in {@link ChatRequestHandler#handleForTest}.
 *
 * Focus: the precheck runs on the NETWORK THREAD and short-circuits BEFORE
 * {@link AiExecutor#get()} is touched, so spoofed packets from non-OP clients
 * never consume an AiExecutor queue slot (ArrayBlockingQueue(64)).
 *
 * <h2>Deviation note — ServerPlayer is passed as {@code null}</h2>
 * Per CLAUDE.md, {@code net.minecraft.server.level.ServerPlayer} cannot be
 * mocked with Mockito — its supertype chain fails to initialize outside the
 * game harness (Plan 03-03 hit the same constraint and solved it with an
 * Authorizer primitive-overload seam). For SAFE-06 coverage we stub
 * {@link Authorizer#authorize(ConfigSnapshot, ServerPlayer, RequestKind, RateLimiter)}
 * via {@link MockedStatic} and pass {@code sender = null} to
 * {@link ChatRequestHandler#handleForTest}. The handler's auth-denied branch
 * guards {@code sender.getUUID()} via {@code sender != null ? sender.getUUID() : null},
 * so the null-sender path exercises the same control flow as a real player would —
 * the SAFE-06 invariant (AiExecutor never touched on denied paths) is proven.
 *
 * Audit UUID is therefore asserted as {@code isNull()} in these tests; production
 * callers ({@link ChatRequestHandler#handle}) already drop null-sender packets at
 * line 71 before reaching {@code handleForTest}, so the production UUID is non-null.
 */
class ChatRequestHandlerAuthorizerTest {

    // Common fixtures (sender intentionally null — see class Javadoc)
    private UUID requestId;
    private ChatRequestPacket pkt;
    private List<Runnable> enqueued;
    private Consumer<Runnable> enqueueWork;
    private List<Object> captured;
    private Consumer<Object> responder;
    private ConfigSnapshot snap;

    @BeforeEach
    void setUp() {
        requestId = UUID.randomUUID();
        pkt = new ChatRequestPacket(requestId, "what is this block for?");
        enqueued = new ArrayList<>();
        enqueueWork = enqueued::add;
        captured = new ArrayList<>();
        responder = captured::add;
        snap = mock(ConfigSnapshot.class);
    }

    @AfterEach
    void tearDown() {
        // Invariant per responseSinkForTests Javadoc: tests MUST reset in teardown.
        ChatRequestHandler.responseSinkForTests = null;
    }

    @Test
    void auth_denied_disabled_emits_ChatErrorPacket_DISABLED_without_aiexecutor_submit() {
        RateLimiter limiter = mock(RateLimiter.class);

        try (MockedStatic<ConfigHolder> configStatic = mockStaticConfig(snap);
             MockedStatic<RateLimiterHolder> rlhStatic = mockStaticRateLimiter(limiter);
             MockedStatic<Authorizer> authStatic = mockStaticAuthorizer(
                 new Authorizer.Denied(ErrorCode.DISABLED,
                     "ForgeBook is temporarily disabled by an operator."));
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class);
             MockedStatic<AiExecutor> executorStatic = org.mockito.Mockito.mockStatic(AiExecutor.class)) {

            ChatRequestHandler.handleForTest(pkt, /* sender */ null, enqueueWork, responder);

            // Exactly one Runnable enqueued — run it to reach the responder.
            assertEquals(1, enqueued.size(), "denied path must enqueue exactly one Runnable");
            enqueued.get(0).run();

            // Captured packet is ChatErrorPacket(DISABLED).
            assertEquals(1, captured.size());
            assertInstanceOf(ChatErrorPacket.class, captured.get(0));
            ChatErrorPacket err = (ChatErrorPacket) captured.get(0);
            assertEquals(ErrorCode.DISABLED, err.code());

            // Audit logDenied fired once with correct args (UUID=null because sender=null).
            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                isNull(), eq(RequestKind.CHAT_UI), eq(ErrorCode.DISABLED), anyLong()));

            // CRITICAL SAFE-06 INVARIANT: AiExecutor was never touched.
            executorStatic.verify(AiExecutor::get, never());
        }
    }

    @Test
    void auth_denied_forbidden_emits_ChatErrorPacket_FORBIDDEN_without_aiexecutor_submit() {
        RateLimiter limiter = mock(RateLimiter.class);

        try (MockedStatic<ConfigHolder> configStatic = mockStaticConfig(snap);
             MockedStatic<RateLimiterHolder> rlhStatic = mockStaticRateLimiter(limiter);
             MockedStatic<Authorizer> authStatic = mockStaticAuthorizer(
                 new Authorizer.Denied(ErrorCode.FORBIDDEN,
                     "ForgeBook is OP-only on this server."));
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class);
             MockedStatic<AiExecutor> executorStatic = org.mockito.Mockito.mockStatic(AiExecutor.class)) {

            ChatRequestHandler.handleForTest(pkt, /* sender */ null, enqueueWork, responder);

            assertEquals(1, enqueued.size());
            enqueued.get(0).run();
            assertEquals(1, captured.size());
            ChatErrorPacket err = (ChatErrorPacket) captured.get(0);
            assertEquals(ErrorCode.FORBIDDEN, err.code());

            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                isNull(), eq(RequestKind.CHAT_UI), eq(ErrorCode.FORBIDDEN), anyLong()));
            executorStatic.verify(AiExecutor::get, never());
        }
    }

    @Test
    void auth_denied_rate_limited_emits_ChatErrorPacket_RATE_LIMITED_without_aiexecutor_submit() {
        RateLimiter limiter = mock(RateLimiter.class);
        String retryMsg = "Rate limit reached. Try again in 12s.";

        try (MockedStatic<ConfigHolder> configStatic = mockStaticConfig(snap);
             MockedStatic<RateLimiterHolder> rlhStatic = mockStaticRateLimiter(limiter);
             MockedStatic<Authorizer> authStatic = mockStaticAuthorizer(
                 new Authorizer.Denied(ErrorCode.RATE_LIMITED, retryMsg));
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class);
             MockedStatic<AiExecutor> executorStatic = org.mockito.Mockito.mockStatic(AiExecutor.class)) {

            ChatRequestHandler.handleForTest(pkt, /* sender */ null, enqueueWork, responder);

            assertEquals(1, enqueued.size());
            enqueued.get(0).run();
            assertEquals(1, captured.size());
            ChatErrorPacket err = (ChatErrorPacket) captured.get(0);
            assertEquals(ErrorCode.RATE_LIMITED, err.code());
            assertEquals(retryMsg, err.humanReadable(),
                "RATE_LIMITED humanReadable must pass through the Denied.humanReadable() string verbatim");

            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                isNull(), eq(RequestKind.CHAT_UI), eq(ErrorCode.RATE_LIMITED), anyLong()));
            executorStatic.verify(AiExecutor::get, never());
        }
    }

    @Test
    void auth_allowed_falls_through_to_existing_dispatch_path() {
        RateLimiter limiter = mock(RateLimiter.class);
        ExecutorService mockExec = mock(ExecutorService.class);

        try (MockedStatic<ConfigHolder> configStatic = mockStaticConfig(snap);
             MockedStatic<RateLimiterHolder> rlhStatic = mockStaticRateLimiter(limiter);
             MockedStatic<Authorizer> authStatic = mockStaticAuthorizer(new Authorizer.Allowed());
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class);
             MockedStatic<AiExecutor> executorStatic = org.mockito.Mockito.mockStatic(AiExecutor.class)) {

            executorStatic.when(AiExecutor::get).thenReturn(mockExec);

            ChatRequestHandler.handleForTest(pkt, /* sender */ null, enqueueWork, responder);

            // The Allowed branch proceeds to AiExecutor.get() and calls submit(Runnable).
            executorStatic.verify(AiExecutor::get, times(1));

            // Neither denied nor success audit fired (dispatch hasn't executed — we never invoke the
            // Runnable captured by the mock executor).
            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                any(), any(), any(), anyLong()), never());

            // No enqueued tick-thread work yet (the Phase 2 body queues work INSIDE the submitted
            // Runnable, which we never execute here).
            assertTrue(enqueued.isEmpty(),
                "nothing should be enqueued on the tick thread until the submitted Runnable runs");
            assertTrue(captured.isEmpty());
        }
    }

    @Test
    void config_holder_null_emits_PROVIDER_without_submit() {
        try (MockedStatic<ConfigHolder> configStatic = org.mockito.Mockito.mockStatic(ConfigHolder.class);
             MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class);
             MockedStatic<AiExecutor> executorStatic = org.mockito.Mockito.mockStatic(AiExecutor.class)) {

            configStatic.when(ConfigHolder::get).thenReturn(null);

            ChatRequestHandler.handleForTest(pkt, /* sender */ null, enqueueWork, responder);

            // Error goes out directly on the network thread (no enqueueWork hop).
            assertTrue(enqueued.isEmpty(),
                "ConfigHolder.get() == null is a server-misconfig state; error must dispatch synchronously");
            assertEquals(1, captured.size());
            assertInstanceOf(ChatErrorPacket.class, captured.get(0));
            ChatErrorPacket err = (ChatErrorPacket) captured.get(0);
            assertEquals(ErrorCode.PROVIDER, err.code());

            // Authorizer was never consulted — the null-snap short-circuit beats the auth gate.
            authStatic.verifyNoInteractions();
            // AiExecutor never touched.
            executorStatic.verify(AiExecutor::get, never());
        }
    }

    @Test
    void denied_packet_emits_logDenied_exactly_once() {
        RateLimiter limiter = mock(RateLimiter.class);

        try (MockedStatic<ConfigHolder> configStatic = mockStaticConfig(snap);
             MockedStatic<RateLimiterHolder> rlhStatic = mockStaticRateLimiter(limiter);
             MockedStatic<Authorizer> authStatic = mockStaticAuthorizer(
                 new Authorizer.Denied(ErrorCode.DISABLED,
                     "ForgeBook is temporarily disabled by an operator."));
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class);
             MockedStatic<AiExecutor> executorStatic = org.mockito.Mockito.mockStatic(AiExecutor.class)) {

            ChatRequestHandler.handleForTest(pkt, /* sender */ null, enqueueWork, responder);
            enqueued.get(0).run();

            // Exactly one logDenied — and neither logSuccess nor logFailure.
            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                any(), any(), any(), anyLong()), times(1));
            auditStatic.verify(() -> RequestAuditLogger.logSuccess(
                any(), any(), anyInt(), anyInt(), anyLong()), never());
            auditStatic.verify(() -> RequestAuditLogger.logFailure(
                any(), any(), any(), anyInt(), anyInt(), anyLong()), never());
        }
    }

    @Test
    void responseSinkForTests_overrides_responder_on_denied_branch() {
        RateLimiter limiter = mock(RateLimiter.class);
        List<Object> sinkList = new ArrayList<>();
        ChatRequestHandler.responseSinkForTests = sinkList::add;

        try (MockedStatic<ConfigHolder> configStatic = mockStaticConfig(snap);
             MockedStatic<RateLimiterHolder> rlhStatic = mockStaticRateLimiter(limiter);
             MockedStatic<Authorizer> authStatic = mockStaticAuthorizer(
                 new Authorizer.Denied(ErrorCode.DISABLED,
                     "ForgeBook is temporarily disabled by an operator."));
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class);
             MockedStatic<AiExecutor> executorStatic = org.mockito.Mockito.mockStatic(AiExecutor.class)) {

            ChatRequestHandler.handleForTest(pkt, /* sender */ null, enqueueWork, responder);
            enqueued.get(0).run();

            // Sink received the packet; responder did NOT.
            assertEquals(1, sinkList.size(), "sink must receive exactly one packet");
            assertInstanceOf(ChatErrorPacket.class, sinkList.get(0));
            assertTrue(captured.isEmpty(), "responder must NOT be invoked when sink is non-null");
        }
    }

    // --- helpers --------------------------------------------------------

    private static MockedStatic<ConfigHolder> mockStaticConfig(ConfigSnapshot snap) {
        MockedStatic<ConfigHolder> m = org.mockito.Mockito.mockStatic(ConfigHolder.class);
        m.when(ConfigHolder::get).thenReturn(snap);
        return m;
    }

    private static MockedStatic<RateLimiterHolder> mockStaticRateLimiter(RateLimiter limiter) {
        MockedStatic<RateLimiterHolder> m = org.mockito.Mockito.mockStatic(RateLimiterHolder.class);
        m.when(RateLimiterHolder::get).thenReturn(limiter);
        return m;
    }

    private static MockedStatic<Authorizer> mockStaticAuthorizer(Authorizer.Result result) {
        MockedStatic<Authorizer> m = org.mockito.Mockito.mockStatic(Authorizer.class);
        // nullable(ServerPlayer.class) explicitly matches null — these tests pass sender=null
        // to avoid mocking ServerPlayer per CLAUDE.md. any(ServerPlayer.class) does NOT match
        // null in Mockito matcher semantics, so stubs with any(...) would silently miss.
        m.when(() -> Authorizer.authorize(
            any(ConfigSnapshot.class), nullable(ServerPlayer.class),
            any(RequestKind.class), any(RateLimiter.class)))
            .thenReturn(result);
        return m;
    }
}
