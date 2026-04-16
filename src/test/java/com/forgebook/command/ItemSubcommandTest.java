package com.forgebook.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.RateLimiter;
import com.forgebook.safety.RequestAuditLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Four-branch unit coverage for {@link ItemSubcommand#executeInternal}.
 *
 * <h2>Why the pure-Java seam (executeInternal)</h2>
 * {@link net.minecraft.server.level.ServerPlayer}, {@link net.minecraft.commands.CommandSourceStack},
 * and {@link net.minecraft.world.item.ItemStack} are hostile to pure-JUnit instantiation outside
 * the game harness (their supertype chains drag in Minecraft registries that fail to initialize
 * without a server) — see CLAUDE.md "avoid mocking Minecraft classes" and the
 * {@link com.forgebook.safety.Authorizer#authorize} primitive-overload precedent from Plan 03,
 * plus the {@link com.forgebook.ai.RagItemPipeline#runInternal} seam from Plan 04.
 *
 * <p>{@link ItemSubcommand} therefore exposes a package-private {@code executeInternal}
 * overload that takes pure-Java parameters ({@link Item} — which is safe to reference as a
 * null, a {@link UUID}, booleans, {@link Function} lookups, a {@link java.util.function.Supplier}
 * for the executor). Tests drive it directly — no Mockito mocks of Minecraft classes needed.
 *
 * <h2>Auth precedes submit (SAFE-06 invariant)</h2>
 * Tests 1, 2, and 4 all assert that the executor supplier was never called before denial —
 * the cardinal SAFE-06 invariant mirrored from
 * {@link com.forgebook.network.handler.ChatRequestHandler} in Plan 05.
 */
class ItemSubcommandTest {

    private static final Item ITEM_SENTINEL = null; // passed through resourceLookup — never dereferenced
    private static final ResourceLocation RL = new ResourceLocation("create", "creative_motor");
    private static final ConfigSnapshot SNAP = mock(ConfigSnapshot.class);
    private UUID uuid;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        uuid = UUID.randomUUID();
        sink = new RecordingSink();
        ItemSubcommand.failureSinkForTests = sink;
    }

    @AfterEach
    void tearDown() {
        ItemSubcommand.failureSinkForTests = s -> {};
    }

    // ================================================================================
    // Test 1: Empty hand → sendFailure + return 0, no Authorizer, no executor.
    // ================================================================================
    @Test
    void empty_hand_returns_zero_and_sendFailure_without_submit() {
        AtomicInteger executorCalls = new AtomicInteger();
        AtomicInteger resourceCalls = new AtomicInteger();
        RateLimiter limiter = mock(RateLimiter.class);

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            int rc = ItemSubcommand.executeInternal(
                /* src */ null,
                /* player */ null,
                /* item */ ITEM_SENTINEL,
                uuid,
                /* isEmptyHand */ true,
                /* heldContext */ true,
                i -> { resourceCalls.incrementAndGet(); return RL; },
                modId -> Optional.empty(),
                () -> SNAP,
                () -> limiter,
                () -> { executorCalls.incrementAndGet(); return mock(ExecutorService.class); });

            assertEquals(0, rc, "empty hand must return 0");
            assertNotNull(sink.lastFailure, "empty hand must sendFailure");
            assertTrue(sink.lastFailure.contains("Hold an item"), sink.lastFailure);
            // Authorizer never consulted; executorSupplier never touched.
            authStatic.verifyNoInteractions();
            assertEquals(0, executorCalls.get(), "SAFE-06: executor never touched on empty-hand short-circuit");
            assertEquals(0, resourceCalls.get(), "resource lookup skipped on empty-hand");
        }
    }

    // ================================================================================
    // Test 2: Authorizer.Denied → logDenied + sendFailure, no submit.
    // ================================================================================
    @Test
    void auth_denied_emits_logDenied_and_sendFailure_without_submit() {
        AtomicInteger executorCalls = new AtomicInteger();
        RateLimiter limiter = mock(RateLimiter.class);

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Denied(ErrorCode.DISABLED,
                    "ForgeBook is temporarily disabled by an operator."));

            int rc = ItemSubcommand.executeInternal(
                /* src */ null,
                /* player */ null,
                ITEM_SENTINEL,
                uuid,
                /* isEmptyHand */ false,
                /* heldContext */ true,
                i -> RL,
                modId -> Optional.empty(),
                () -> SNAP,
                () -> limiter,
                () -> { executorCalls.incrementAndGet(); return mock(ExecutorService.class); });

            assertEquals(0, rc, "denied path must return 0");
            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                eq(uuid), eq(RequestKind.ITEM), eq(ErrorCode.DISABLED), anyLong()), times(1));
            assertEquals("ForgeBook is temporarily disabled by an operator.", sink.lastFailure);
            // SAFE-06 invariant: executor NEVER touched on denial.
            assertEquals(0, executorCalls.get(),
                "SAFE-06: executor supplier must not be called on Authorizer.Denied");
        }
    }

    // ================================================================================
    // Test 3: Allowed → executor.submit called exactly once; we do NOT run the Runnable.
    // ================================================================================
    @Test
    void allowed_path_submits_rag_pipeline_to_aiexecutor() {
        RateLimiter limiter = mock(RateLimiter.class);
        ExecutorService mockExec = mock(ExecutorService.class);

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Allowed());

            int rc = ItemSubcommand.executeInternal(
                /* src */ null,
                /* player */ null,
                ITEM_SENTINEL,
                uuid,
                /* isEmptyHand */ false,
                /* heldContext */ true,
                i -> RL,
                modId -> Optional.empty(),
                () -> SNAP,
                () -> limiter,
                () -> mockExec);

            assertEquals(1, rc, "allowed path must return Command.SINGLE_SUCCESS (== 1)");
            // Exactly one Runnable submitted; we intentionally do NOT run it — RagItemPipeline
            // is covered end-to-end by RagItemPipelineTest (Plan 04 Task 2).
            org.mockito.Mockito.verify(mockExec, times(1)).submit(any(Runnable.class));
            // No denial audit line on the allowed path.
            auditStatic.verify(() -> RequestAuditLogger.logDenied(
                any(), any(), any(), anyLong()), never());
            // sendFailure must not have fired synchronously.
            assertNull(sink.lastFailure, "allowed path must not sendFailure synchronously");
        }
    }

    // ================================================================================
    // Test 4: Executor rejection → OVERLOADED audit + sendFailure, no throw.
    // ================================================================================
    @Test
    void executor_rejection_emits_OVERLOADED_failure() {
        RateLimiter limiter = mock(RateLimiter.class);
        ExecutorService mockExec = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("queue full"))
            .when(mockExec).submit(any(Runnable.class));

        try (MockedStatic<Authorizer> authStatic = org.mockito.Mockito.mockStatic(Authorizer.class);
             MockedStatic<RequestAuditLogger> auditStatic = org.mockito.Mockito.mockStatic(RequestAuditLogger.class)) {

            authStatic.when(() -> Authorizer.authorize(
                any(ConfigSnapshot.class), any(), any(RequestKind.class), any(RateLimiter.class)))
                .thenReturn(new Authorizer.Allowed());

            int rc = ItemSubcommand.executeInternal(
                /* src */ null,
                /* player */ null,
                ITEM_SENTINEL,
                uuid,
                /* isEmptyHand */ false,
                /* heldContext */ true,
                i -> RL,
                modId -> Optional.empty(),
                () -> SNAP,
                () -> limiter,
                () -> mockExec);

            assertEquals(1, rc, "rejection path still returns SINGLE_SUCCESS after error feedback");
            auditStatic.verify(() -> RequestAuditLogger.logFailure(
                eq(uuid), eq(RequestKind.ITEM), eq(ErrorCode.OVERLOADED),
                anyInt(), anyInt(), anyLong()), times(1));
            assertEquals("Server is busy. Try again.", sink.lastFailure);
        }
    }

    // ------------------------------------------------------------------
    // Helper: capture failure text from the production failureSinkForTests.
    // ------------------------------------------------------------------
    private static final class RecordingSink implements java.util.function.Consumer<String> {
        volatile String lastFailure;
        @Override public void accept(String s) { lastFailure = s; }
    }
}
