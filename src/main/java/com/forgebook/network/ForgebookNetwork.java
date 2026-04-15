package com.forgebook.network;

import com.forgebook.network.packet.ChatErrorPacket;
import com.forgebook.network.packet.ChatRequestPacket;
import com.forgebook.network.packet.ChatResponsePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * NET-01 / D-17: SimpleChannel "forgebook:main" with protocol version "1".
 *
 * Uses NetworkRegistry.newSimpleChannel — NOT the NeoForge/1.20.2+ fluent
 * builder API (CLAUDE.md "What NOT to Use", Pitfall 2).
 *
 * D-17: same protocol version string on both sides — bump when packet schema
 * changes breakingly.
 *
 * Asymmetric consumer threads:
 *   - ChatRequestPacket (C->S): consumerNetworkThread so the handler can decide
 *     whether to hop to aiExecutor or enqueueWork. D-19 forbids auto-enqueueing
 *     C->S packets — the network thread MUST schedule off-tick work itself.
 *   - ChatResponsePacket + ChatErrorPacket (S->C): consumerMainThread because the
 *     client's rendering work (Phase 4) must land on the render thread.
 */
public final class ForgebookNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("forgebook", "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;
    private static int nextId() { return nextId++; }

    private ForgebookNetwork() {}

    /** Called from ForgeBookMod commonSetup -> enqueueWork. */
    public static void register() {
        CHANNEL.messageBuilder(ChatRequestPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
            .encoder(ChatRequestPacket::encode)
            .decoder(ChatRequestPacket::decode)
            .consumerNetworkThread(ChatRequestPacket::handle)
            .add();

        CHANNEL.messageBuilder(ChatResponsePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ChatResponsePacket::encode)
            .decoder(ChatResponsePacket::decode)
            .consumerMainThread(ChatResponsePacket::handleOnClient)
            .add();

        CHANNEL.messageBuilder(ChatErrorPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ChatErrorPacket::encode)
            .decoder(ChatErrorPacket::decode)
            .consumerMainThread(ChatErrorPacket::handleOnClient)
            .add();
    }
}
