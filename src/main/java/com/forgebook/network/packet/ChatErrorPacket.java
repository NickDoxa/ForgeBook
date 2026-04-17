package com.forgebook.network.packet;

import com.forgebook.network.client.ClientPacketSinks;
import com.forgebook.network.client.ClientPacketSinks.ErrorSink;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server → Client error. 6 codes declared now so the wire schema is stable
 * across Phases 1-3 (OVERLOADED is the only code emitted in Phase 1; others
 * used by Phase 3 command-surface work).
 *
 * <p>Phase 4 dispatches the payload through {@link ClientPacketSinks#errorSink}
 * — a volatile ErrorSink installed by ClientSetup.init on the logical client.
 * The sink indirection keeps this class free of any {@code com.forgebook.client.*}
 * import, preserving the SCAF-02 forward firewall (RESEARCH Pitfall 7).
 */
public record ChatErrorPacket(UUID requestId, ErrorCode code, String humanReadable) {

    private static final Logger LOG = LogManager.getLogger();

    public enum ErrorCode {
        OVERLOADED,
        TRANSPORT,
        RATE_LIMITED,
        FORBIDDEN,
        PROVIDER,
        DISABLED
    }

    public static void encode(ChatErrorPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.requestId);
        buf.writeEnum(p.code);
        buf.writeUtf(p.humanReadable, 512);
    }

    public static ChatErrorPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        ErrorCode code = buf.readEnum(ErrorCode.class);
        String msg = buf.readUtf(512);
        return new ChatErrorPacket(id, code, msg);
    }

    /** Client-side handler. */
    public static void handleOnClient(ChatErrorPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().setPacketHandled(true);
        ErrorSink sink = ClientPacketSinks.errorSink;
        if (sink != null) {
            sink.accept(pkt.requestId, pkt.code, pkt.humanReadable);
        } else {
            LOG.warn("[ForgeBook] ChatErrorPacket received on client before errorSink installed for {}: {} — {}",
                pkt.requestId, pkt.code, pkt.humanReadable);
        }
    }
}
