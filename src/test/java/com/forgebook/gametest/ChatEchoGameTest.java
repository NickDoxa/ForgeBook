package com.forgebook.gametest;

import com.forgebook.network.handler.ChatRequestHandler;
import com.forgebook.network.packet.ChatRequestPacket;
import com.forgebook.network.packet.ChatResponsePacket;
import com.forgebook.util.AiExecutor;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * D-28 server-only GameTest. Invokes ChatRequestHandler's package-private test
 * overload on the dedicated GameTest server, captures the response via the
 * test sink, and asserts the echo payload.
 *
 * <p>Not a wire-level round trip: per RESEARCH.md L1145-1158, true client-side
 * packet decode is deferred to Phase 5 prod-jar smoke. This test covers
 * everything EXCEPT the Netty encoder/decoder — which is exercised at build
 * time by `./gradlew build` succeeding on the compile-time reference to the
 * codecs.
 *
 * <p>NET-06: asserts end-to-end handler-layer echo on every CI run.
 *
 * <p>Execution: `./gradlew runGameTestServer` — the gradle `gameTestServer`
 * run config sets {@code forge.enabledGameTestNamespaces=forgebook} (Pitfall 8).
 */
@GameTestHolder("forgebook")
@PrefixGameTestTemplate(false)
public class ChatEchoGameTest {

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void chatEchoRoundTrip(GameTestHelper helper) {
        // AiExecutor is started on ServerStartingEvent; the GameTest harness
        // fires that event before running tests, so get() must not throw.
        try {
            AiExecutor.get();
        } catch (IllegalStateException e) {
            // Defensive: if the harness ran tests before ServerStartingEvent,
            // start the executor here. Never expected in practice.
            AiExecutor.start();
        }

        final AtomicReference<Object> captured = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        ChatRequestHandler.responseSinkForTests = resp -> {
            captured.set(resp);
            latch.countDown();
        };

        try {
            ChatRequestPacket req =
                new ChatRequestPacket(UUID.randomUUID(), "hello forgebook");

            // Synthetic enqueuer: run inline so the AiExecutor -> "tick thread"
            // handoff flattens onto the test observation thread. Synchronous
            // enqueueWork is safe here because the handler's enqueueWork body
            // is the send call only — no tick-state mutation.
            // sender=null is acceptable here: the handler's null-sender branch
            // in handle() is bypassed by going directly through handleForTest.
            ChatRequestHandler.handleForTest(
                req,
                /* sender    = */ null,
                /* enqueueWork = */ Runnable::run,
                /* responder  = */ msg -> { /* unreachable: sink wins */ });

            boolean arrived;
            try {
                arrived = latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                helper.fail("interrupted waiting for echo");
                return;
            }

            if (!arrived) {
                helper.fail("no response captured within 5s (AiExecutor not running?)");
                return;
            }

            Object resp = captured.get();
            if (!(resp instanceof ChatResponsePacket cr)) {
                helper.fail("response was not ChatResponsePacket: " + resp);
                return;
            }
            if (!"echo: hello forgebook".equals(cr.reply())) {
                helper.fail("echo body mismatch: got '" + cr.reply() + "'");
                return;
            }

            helper.succeed();
        } finally {
            ChatRequestHandler.responseSinkForTests = null;
        }
    }
}
