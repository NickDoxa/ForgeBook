package com.forgebook.network.packet;

import com.forgebook.network.client.ClientPacketSinks;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server → Client chat reply. Phase 4 dispatches the payload through
 * {@link ClientPacketSinks#replySink} — a volatile BiConsumer installed by
 * ClientSetup.init on the logical client. The sink indirection keeps this
 * class free of any {@code com.forgebook.client.*} import, preserving the
 * SCAF-02 forward firewall (see RESEARCH Pitfall 7).
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
        BiConsumer<UUID, String> sink = ClientPacketSinks.replySink;
        if (sink != null) {
            sink.accept(pkt.requestId, pkt.reply);
        } else {
            LOG.warn("[ForgeBook] ChatResponsePacket received on client before replySink installed for {}; dropping.", pkt.requestId);
        }
    }
}
