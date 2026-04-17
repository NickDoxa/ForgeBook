package com.forgebook.network.handler;

import com.forgebook.ai.AiDispatcher;
import com.forgebook.ai.DispatchContext;
import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.ForgebookNetwork;
import com.forgebook.network.packet.ChatErrorPacket;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.network.packet.ChatRequestPacket;
import com.forgebook.network.packet.ChatResponsePacket;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.RateLimiterHolder;
import com.forgebook.safety.RequestAuditLogger;
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
 * Phase 2 dispatch: the executor task body calls AiDispatcher.INSTANCE.dispatch(...)
 * and maps the Result (Reply or Error) to a ChatResponsePacket or ChatErrorPacket.
 * AiDispatcher runs synchronously inside the submitted task — it does NOT submit
 * to AiExecutor again (AgentLoop's internal parallel tool calls do touch AiExecutor,
 * but that is Plan 06's concern, not this handler's).
 *
 * D-19 invariant:
 *   1. Network thread captures ctx immediately, sets packet handled.
 *   2. aiExecutor.submit(...) — HTTP / provider work runs here (Phase 2+).
 *   3. ctx.enqueueWork(...) wraps ONLY the final game-state mutation (the send).
 *
 * Pitfall 3: reversing 2 and 3 (putting submit inside enqueueWork) freezes the
 * server tick. Explicitly prohibited.
 *
 * <h2>SAFE-06: network-thread authorization precheck (Plan 03-05)</h2>
 * Authorizer.authorize runs on the Netty network thread BEFORE AiExecutor.submit.
 * Rationale: AiExecutor's ArrayBlockingQueue(64) is a finite resource — spoofed
 * packets from a malicious client must not be allowed to enqueue, even if the
 * dispatch inside would have denied them. Authorizer re-reads player.hasPermissions(2)
 * server-side, so client-forged permission claims are ineffective.
 *
 * On Denied: a ChatErrorPacket is returned via the existing responder/sink path,
 * wrapped in enqueueWork so the send happens on the tick thread (Pitfall 2 —
 * disconnected-player safety). RequestAuditLogger.logDenied fires once with
 * (uuid, CHAT_UI, errorCode, startNanos).
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
        // SAFE-06: authorize on the NETWORK THREAD before consuming AiExecutor queue capacity.
        // Spoofed client packets cannot forge hasPermissions(2) — the server re-reads it here.
        // D-14: single volatile load of ConfigSnapshot; pass down to Authorizer.
        ConfigSnapshot snap = ConfigHolder.get();
        if (snap == null) {
            LOG.error("ChatRequestHandler invoked before ConfigHolder seeded; rejecting.");
            ChatErrorPacket errInit = new ChatErrorPacket(
                pkt.requestId(), ErrorCode.PROVIDER, "ForgeBook not initialized — check server logs.");
            Consumer<Object> sinkInit = responseSinkForTests;
            if (sinkInit != null) sinkInit.accept(errInit); else responder.accept(errInit);
            return;
        }
        long startNanos = System.nanoTime();
        java.util.UUID senderUuid = sender != null ? sender.getUUID() : null;
        String playerName = sender != null ? sender.getName().getString() : "<no sender>";
        LOG.info("chat request uuid={} player={} req_id={} msg_len={}",
            senderUuid, playerName, pkt.requestId(),
            pkt.message() == null ? 0 : pkt.message().length());
        Authorizer.Result auth = Authorizer.authorize(
            snap, sender, RequestKind.CHAT_UI, RateLimiterHolder.get());
        if (auth instanceof Authorizer.Denied d) {
            LOG.info("chat denied uuid={} code={}", senderUuid, d.code());
            RequestAuditLogger.logDenied(
                sender != null ? sender.getUUID() : null,
                RequestKind.CHAT_UI, d.code(), startNanos);
            // Hop to tick thread for the final send (preserves Phase 2 enqueueWork contract — the
            // responder may touch network state that Forge expects to be mutated on the server tick).
            enqueueWork.accept(() -> {
                ChatErrorPacket err = new ChatErrorPacket(pkt.requestId(), d.code(), d.humanReadable());
                Consumer<Object> sink = responseSinkForTests;
                if (sink != null) sink.accept(err); else responder.accept(err);
            });
            return;
        }
        // auth is Allowed — fall through to existing Phase 2 body.
        try {
            AiExecutor.get().submit(() -> {
                // Phase 2: dispatch via AiDispatcher (AI-04). Runs synchronously inside
                // this task — AiDispatcher does NOT submit to AiExecutor itself.
                try {
                    AiDispatcher.Result result = AiDispatcher.INSTANCE.dispatch(
                        new DispatchContext(pkt.message(), sender, RequestKind.CHAT_UI));

                    // D-19: enqueueWork ONLY for the final game-state mutation (the send).
                    enqueueWork.accept(() -> {
                        Object out;
                        if (result instanceof AiDispatcher.Reply r) {
                            LOG.info("chat reply uuid={} truncated={} text_len={}",
                                senderUuid, r.truncated(), r.text() == null ? 0 : r.text().length());
                            out = new ChatResponsePacket(pkt.requestId(), r.text());
                        } else {
                            AiDispatcher.Error err = (AiDispatcher.Error) result;
                            LOG.info("chat error uuid={} code={}", senderUuid, err.code());
                            out = new ChatErrorPacket(pkt.requestId(), err.code(), err.humanReadable());
                        }
                        Consumer<Object> sink = responseSinkForTests;
                        if (sink != null) sink.accept(out); else responder.accept(out);
                    });
                } catch (Exception ex) {
                    // T-02-07-08: exception message goes only to server log; client gets static string.
                    LOG.error("Dispatch failed for {}",
                        sender != null ? sender.getUUID() : "<no sender>", ex);
                    enqueueWork.accept(() -> {
                        ChatErrorPacket err = new ChatErrorPacket(
                            pkt.requestId(), ErrorCode.PROVIDER, "Internal error.");
                        Consumer<Object> sink = responseSinkForTests;
                        if (sink != null) sink.accept(err); else responder.accept(err);
                    });
                }
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
