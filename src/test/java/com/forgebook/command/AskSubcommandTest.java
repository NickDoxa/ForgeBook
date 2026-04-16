package com.forgebook.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.forgebook.ai.AiDispatcher;
import com.forgebook.ai.DispatchContext;
import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.RateLimiter;
import com.forgebook.safety.RequestAuditLogger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Five-branch unit coverage for {@link AskSubcommand#executeInternal}.
 *
 * <h2>Why pure-Java seam (executeInternal)</h2>
 * {@link net.minecraft.server.level.ServerPlayer},
 * {@link net.minecraft.commands.CommandSourceStack}, and
 * {@link net.minecraft.server.MinecraftServer} are hostile to pure-JUnit instantiation
 * (CLAUDE.md "avoid mocking Minecraft classes"). {@link AskSubcommand} therefore exposes
 * a package-private {@code executeInternal} seam that takes pure-Java
 * {@link java.util.function.Function} / {@link java.util.function.Supplier} /
 * {@link java.util.function.Consumer} collaborators. Tests drive it with:
 * <ul>
 *   <li>Canned {@link ConfigSnapshot} + {@link RateLimiter} ({@code mock(...)} is safe
 *       for non-Minecraft types — Mockito 5 inline mock-maker is the default).</li>
 *   <li>A sync-executing "executor" that runs the submitted {@link Runnable} inline
 *       via {@code doAnswer}.</li>
 *   <li>A sync {@code tickThreadHop} (just {@code Runnable::run}) so the Reply/Error
 *       assertion fires within the test.</li>
 *   <li>A captured {@code successSinkForTests} / {@code failureSinkForTests} to read
 *       the text that production would pass to
 *       {@link net.minecraft.commands.CommandSourceStack#sendSuccess(java.util.function.Supplier, boolean)}
 *       / {@link net.minecraft.commands.CommandSourceStack#sendFailure(net.minecraft.network.chat.Component)}.</li>
 * </ul>
 *
 * <h2>Auth precedes submit (SAFE-06 invariant)</h2>
 * Tests 1 and 5 assert the executor is never touched before authorization denial.
 */
class AskSubcommandTest {

    private static final UUID UUID_A = UUID.randomUUID();
    private static final String MSG = "what is iron used for?";

    private RecordingSink successSink;
    private RecordingSink failureSink;
    private final ConfigSnapshot snap = mock(ConfigSnapshot.class);
    private final RateLimiter limiter = mock(RateLimiter.class);

    @BeforeEach
    void setUp() {
        successSink = new RecordingSink();
        failureSink = new RecordingSink();
        AskSubcommand.successSinkForTests = successSink;
        AskSubcommand.failureSinkForTests = failureSink;
    }

    @AfterEach
    void tearDown() {
        AskSubcommand.successSinkForTests = s -> {};
        AskSubcommand.failureSinkForTests = s -> {};
    }

    // ================================================================================
    // Test 1: Authorizer.Denied → logDenied + sendFailure, executor never consulted.
    // ================================================================================
    @Test
    void auth_denied_emits_logDenied_and_sendFailure_without_submit() {
        AtomicInteger executorCalls = new AtomicInteger();

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Denied(ErrorCode.DISABLED,
                    "ForgeBook is temporarily disabled by an operator."));

            int rc = AskSubcommand.executeInternal(
                /* src */ null, /* player */ null, UUID_A, MSG,
                () -> snap,
                () -> limiter,
                dc -> new AiDispatcher.Reply("never", false),
                () -> { executorCalls.incrementAndGet(); return mock(ExecutorService.class); },
                Runnable::run,
                p -> new DispatchContext(MSG, p, RequestKind.ASK));

            assertEquals(0, rc, "denied path must return 0");
            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                eq(UUID_A), eq(RequestKind.ASK), eq(ErrorCode.DISABLED), anyLong()), times(1));
            assertEquals("ForgeBook is temporarily disabled by an operator.", failureSink.last);
            assertEquals(0, executorCalls.get(), "SAFE-06: executor never touched on denial");
        }
    }

    // ================================================================================
    // Test 2: Allowed → executor.submit called exactly once (Runnable captured).
    // ================================================================================
    @Test
    void allowed_path_submits_to_aiexecutor() {
        ExecutorService exec = mock(ExecutorService.class);
        AtomicReference<Runnable> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return null; })
            .when(exec).submit(any(Runnable.class));

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Allowed());

            int rc = AskSubcommand.executeInternal(
                /* src */ null, /* player */ null, UUID_A, MSG,
                () -> snap,
                () -> limiter,
                dc -> new AiDispatcher.Reply("hello", false),
                () -> exec,
                Runnable::run,
                p -> new DispatchContext(MSG, p, RequestKind.ASK));

            assertEquals(1, rc);
            verify(exec, times(1)).submit(any(Runnable.class));
            assertNotNull(captured.get(), "Runnable must be captured");
            // Allowed path: no denial audit.
            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                any(), any(), any(), anyLong()), never());
        }
    }

    // ================================================================================
    // Test 3: Reply → sendSuccess via tickThreadHop.
    // ================================================================================
    @Test
    void reply_translates_to_sendSuccess() {
        ExecutorService exec = mock(ExecutorService.class);
        // Execute submitted Runnable synchronously.
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(exec).submit(any(Runnable.class));

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Allowed());

            int rc = AskSubcommand.executeInternal(
                /* src */ null, /* player */ null, UUID_A, MSG,
                () -> snap,
                () -> limiter,
                dc -> new AiDispatcher.Reply("Iron is used to craft tools.", false),
                () -> exec,
                Runnable::run, // tick-thread hop runs inline
                p -> new DispatchContext(MSG, p, RequestKind.ASK));

            assertEquals(1, rc);
            assertEquals("Iron is used to craft tools.", successSink.last,
                "Reply text must be forwarded to sendSuccess");
            assertNull(failureSink.last, "no failure on Reply path");
        }
    }

    // ================================================================================
    // Test 4: Error → sendFailure via tickThreadHop.
    // ================================================================================
    @Test
    void error_translates_to_sendFailure() {
        ExecutorService exec = mock(ExecutorService.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(exec).submit(any(Runnable.class));

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Allowed());

            int rc = AskSubcommand.executeInternal(
                /* src */ null, /* player */ null, UUID_A, MSG,
                () -> snap,
                () -> limiter,
                dc -> new AiDispatcher.Error(ErrorCode.TRANSPORT, "Transient network issue. Try again."),
                () -> exec,
                Runnable::run,
                p -> new DispatchContext(MSG, p, RequestKind.ASK));

            assertEquals(1, rc);
            assertEquals("Transient network issue. Try again.", failureSink.last,
                "Error.humanReadable must be forwarded to sendFailure");
            assertNull(successSink.last, "no success on Error path");
        }
    }

    // ================================================================================
    // Test 5: Executor rejection → OVERLOADED audit + sendFailure.
    // ================================================================================
    @Test
    void executor_rejection_emits_OVERLOADED() {
        ExecutorService exec = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("queue full"))
            .when(exec).submit(any(Runnable.class));

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Allowed());

            int rc = AskSubcommand.executeInternal(
                /* src */ null, /* player */ null, UUID_A, MSG,
                () -> snap,
                () -> limiter,
                dc -> new AiDispatcher.Reply("never", false),
                () -> exec,
                Runnable::run,
                p -> new DispatchContext(MSG, p, RequestKind.ASK));

            assertEquals(1, rc, "rejection still returns SINGLE_SUCCESS after error feedback");
            auditStatic.verify(() -> RequestAuditLogger.logFailure(
                eq(UUID_A), eq(RequestKind.ASK), eq(ErrorCode.OVERLOADED),
                anyInt(), anyInt(), anyLong()), times(1));
            assertEquals("Server is busy. Try again.", failureSink.last);
        }
    }

    private static final class RecordingSink implements java.util.function.Consumer<String> {
        volatile String last;
        @Override public void accept(String s) { last = s; }
    }
}
