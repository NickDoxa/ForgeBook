package com.forgebook.network.packet;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client → Server chat request. Phase 1: echo handler uses requestId + message.
 * Phase 4: ClientChatSession tags each outgoing message with a fresh UUID.
 *
 * S-6 length cap: writeUtf(message, 32_000) prevents an attacker from OOMing the
 * opposite side with a 2GiB string. 32_000 matches Minecraft vanilla chat limits.
 */
public record ChatRequestPacket(UUID requestId, String message) {

    public static void encode(ChatRequestPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.requestId);
        buf.writeUtf(p.message, 32_000);
    }

    public static ChatRequestPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String msg = buf.readUtf(32_000);
        return new ChatRequestPacket(id, msg);
    }

    /** Forge SimpleChannel handler adapter — delegates to ChatRequestHandler. */
    public static void handle(ChatRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        com.forgebook.network.handler.ChatRequestHandler.handle(pkt, ctx);
    }
}
