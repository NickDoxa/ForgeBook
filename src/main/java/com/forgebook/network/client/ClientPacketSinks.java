package com.forgebook.network.client;

import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Volatile-sink registry bridging the server→client packet handlers in
 * com.forgebook.network.packet.* to the Phase-4 client session in
 * com.forgebook.client.session.*. Pattern promoted from the Phase-1
 * ChatRequestHandler.responseSinkForTests volatile-sink seam
 * (com.forgebook.network.handler.ChatRequestHandler:82) to production use.
 *
 * <p>Why this class exists: SCAF-02's client firewall forbids ChatResponsePacket/
 * ChatErrorPacket (under com.forgebook.network.packet) from importing
 * com.forgebook.client.session.* directly. Routing through a neutral
 * volatile-sink keeps packet handlers free of any reference to client
 * session types while still letting the client wire up the dispatch.
 *
 * <p>Why the package is com.forgebook.network.client (NOT com.forgebook.client.network):
 * This package has ZERO net.minecraft.client.* imports. It lives under
 * com.forgebook.network to signal "wire adapter, not UI"; the "client" suffix
 * denotes "client-side wire sink". SCAF-02's CI grep (net.minecraft.client.*
 * outside com.forgebook.client.*) does NOT flag this file — verified by reading
 * .github/workflows/build.yml lines 31-45.
 *
 * <p>Lifecycle: client boot (ClientSetup.init — plan 04-05) installs the sinks.
 * On a dedicated server the class is never touched (packet handlers on the
 * server dispatch via different code paths; handleOnClient is only invoked
 * on the logical client). Null sinks are benign: handleOnClient log-warns
 * and drops — same fail-safe the Phase-1 responseSinkForTests pattern uses.
 */
public final class ClientPacketSinks {

    /**
     * Sink for ChatResponsePacket.handleOnClient. Assigned by ClientSetup.init
     * to (id, text) -&gt; ClientChatSession.get().append(id, text).
     * Null until client boot completes; null-guard expected at call sites.
     */
    public static volatile BiConsumer<UUID, String> replySink = null;

    /**
     * Triple-arg functional interface for ChatErrorPacket.handleOnClient
     * (BiConsumer is binary-only; we need (UUID, ErrorCode, String)).
     */
    @FunctionalInterface
    public interface ErrorSink {
        void accept(UUID id, ErrorCode code, String humanReadable);
    }

    /**
     * Sink for ChatErrorPacket.handleOnClient. Assigned by ClientSetup.init
     * to (id, code, msg) -&gt; ClientChatSession.get().appendError(id, code, msg).
     */
    public static volatile ErrorSink errorSink = null;

    private ClientPacketSinks() {}
}
