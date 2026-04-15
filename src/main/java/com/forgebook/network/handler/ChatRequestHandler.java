package com.forgebook.network.handler;

import com.forgebook.network.ForgebookNetwork;
import com.forgebook.network.packet.ChatErrorPacket;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.network.packet.ChatRequestPacket;
import com.forgebook.network.packet.ChatResponsePacket;
import com.forgebook.util.AiExecutor;
import java.util.concurrent.RejectedExecutionException;
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
 */
public final class ChatRequestHandler {

    private static final Logger LOG = LogManager.getLogger();

    private ChatRequestHandler() {}

    public static void handle(ChatRequestPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.setPacketHandled(true);

        final ServerPlayer sender = ctx.getSender();
        if (sender == null) {
            LOG.warn("ChatRequestPacket received with no sender; dropping.");
            return;
        }

        try {
            AiExecutor.get().submit(() -> {
                // Phase 1: echo (no provider call).
                // Phase 2+: AiDispatcher.dispatch(pkt, sender) -> Claude provider.
                String reply = "echo: " + pkt.message();
                ChatResponsePacket resp = new ChatResponsePacket(pkt.requestId(), reply);

                // D-19: enqueueWork ONLY for the final send — the send is the
                // game-state touch (it serializes on the network send queue, which
                // is thread-confined to the server main thread via enqueueWork).
                ctx.enqueueWork(() ->
                    ForgebookNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> sender), resp));
            });
        } catch (RejectedExecutionException e) {
            // D-20 / D-21: queue overflow -> send OVERLOADED error.
            LOG.warn("aiExecutor rejected submission; returning OVERLOADED to {}", sender.getUUID());
            ChatErrorPacket err = new ChatErrorPacket(
                pkt.requestId(), ErrorCode.OVERLOADED, "Server is busy. Try again.");
            ForgebookNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> sender), err);
        }
    }
}
