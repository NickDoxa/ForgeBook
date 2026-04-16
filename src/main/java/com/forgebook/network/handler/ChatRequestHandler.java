package com.forgebook.network.handler;

import com.forgebook.network.ForgebookNetwork;
import com.forgebook.network.packet.ChatErrorPacket;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.network.packet.ChatRequestPacket;
import com.forgebook.network.packet.ChatResponsePacket;
import com.forgebook.util.AiExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NET-03 / NET-06 / D-19: canonical executor-hop + enqueueWork pattern.
 *
 * Phase 1 body: echo. Phase 2 replaces the executor task body with a call to
 * AiDispatcher.dispatch(...).
 *
 * D-19 invariant:
 *   1. Network thread captures ctx immediately, sets packet handled.
 *   2. aiExecutor.submit(...) — HTTP / provider work runs here (Phase 2+).
 *   3. ctx.enqueueWork(...) wraps ONLY the final game-state mutation (the send).
 *
 * Pitfall 3: reversing 2 and 3 (putting submit inside enqueueWork) freezes the
 * server tick. Explicitly prohibited.
 *
 * D-20 rejection semantics: when aiExecutor's ArrayBlockingQueue(64) overflows,
 * AbortPolicy throws RejectedExecutionException — we catch and send
 * ChatErrorPacket(OVERLOADED) to the client.
 *
 * <h2>Test seam (Plan 01-05 / D-28)</h2>
 * The public {@link #handle(ChatRequestPacket, Supplier)} entry point cannot be
 * driven synchronously from a GameTest because {@code NetworkEvent.Context} in
 * Forge 47.x does not expose a public constructor — we cannot mint a stub
 * context from a test. Instead, the {@link #handleForTest} overload lets the
 * GameTest (in {@code com.forgebook.gametest}) inject a synchronous enqueuer
 * ({@code Runnable::run}) and a response sink that captures the outbound
 * packet in lieu of calling {@link ForgebookNetwork#CHANNEL}.send(...).
 * Production code paths are untouched.
 */
public final class ChatRequestHandler {

    private static final Logger LOG = LogManager.getLogger();

    /**
     * Test sink. When non-null, the handler's final send step
     * calls this instead of {@link ForgebookNetwork#CHANNEL}.send(...). Used by
     * {@code ChatEchoGameTest} (Plan 01-05) to observe response/error packets
     * without binding to a real network connection. Volatile so production
     * threads see a null sink immediately after the test's @AfterEach clears
     * it. MUST be reset to null in test teardown.
     */
    /* @VisibleForTesting — null in production; tests set and must reset in teardown. */
    public static volatile Consumer<Object> responseSinkForTests = null;

    private ChatRequestHandler() {}

    public static void handle(ChatRequestPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);

        final ServerPlayer sender = ctx.getSender();
        if (sender == null) {
            LOG.warn("ChatRequestPacket received with no sender; dropping.");
            return;
        }

        handleForTest(
            pkt,
            sender,
            /* enqueueWork = */ (Runnable r) -> ctx.enqueueWork(r),
            /* responder  = */ msg -> ForgebookNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> sender), msg));
    }

    /**
     * Test-driven overload. Production callers go through
     * {@link #handle(ChatRequestPacket, Supplier)}; the GameTest supplies a
     * synchronous enqueuer ({@code Runnable::run}) and a capturing responder.
     *
     * <p>Logic mirrors the public path exactly — D-19 executor-hop, D-20
     * rejection-to-OVERLOADED — so the GameTest exercises the real
     * AiExecutor submission and the real encode path (indirectly, via compile
     * dependency on ForgebookNetwork.CHANNEL). Callers MUST NOT bypass this
     * overload in production.
     */
    public static void handleForTest(
            ChatRequestPacket pkt,
            ServerPlayer sender,
            java.util.function.Consumer<Runnable> enqueueWork,
            Consumer<Object> responder) {
        try {
            AiExecutor.get().submit(() -> {
                // Phase 1: echo (no provider call).
                // Phase 2+: AiDispatcher.dispatch(pkt, sender) -> Claude provider.
                String reply = "echo: " + pkt.message();
                ChatResponsePacket resp = new ChatResponsePacket(pkt.requestId(), reply);

                // D-19: enqueueWork ONLY for the final send — the send is the
                // game-state touch (serializes on the network send queue, which
                // is thread-confined to the server main thread via enqueueWork).
                enqueueWork.accept(() -> {
                    Consumer<Object> sink = responseSinkForTests;
                    if (sink != null) {
                        sink.accept(resp);
                    } else {
                        responder.accept(resp);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            // D-20 / D-21: queue overflow -> send OVERLOADED error.
            LOG.warn("aiExecutor rejected submission; returning OVERLOADED to {}",
                sender != null ? sender.getUUID() : "<no sender>");
            ChatErrorPacket err = new ChatErrorPacket(
                pkt.requestId(), ErrorCode.OVERLOADED, "Server is busy. Try again.");
            Consumer<Object> sink = responseSinkForTests;
            if (sink != null) {
                sink.accept(err);
            } else {
                responder.accept(err);
            }
        }
    }
}
