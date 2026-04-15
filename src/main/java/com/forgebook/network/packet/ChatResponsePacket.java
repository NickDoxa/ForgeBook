package com.forgebook.network.packet;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server → Client chat reply. Phase 1: handler just logs the arrival.
 * Phase 4 replaces handleOnClient with ClientChatSession.append(...).
 */
public record ChatResponsePacket(UUID requestId, String reply) {

    private static final Logger LOG = LogManager.getLogger();

    public static void encode(ChatResponsePacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.requestId);
        buf.writeUtf(p.reply, 32_000);
    }

    public static ChatResponsePacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String reply = buf.readUtf(32_000);
        return new ChatResponsePacket(id, reply);
    }

    /** Client-side handler. Registered with consumerMainThread so it runs on render thread. */
    public static void handleOnClient(ChatResponsePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        LOG.info("[ForgeBook] Client received response for {}: {}", pkt.requestId, pkt.reply);
        // Phase 4: com.forgebook.client.ClientChatSession.append(pkt.requestId, pkt.reply);
    }
}
