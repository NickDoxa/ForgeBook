package com.forgebook.client.ui;

import com.forgebook.network.packet.ChatErrorPacket;

/**
 * An inline error card value type. Task 2 of plan 04-01 expands this with
 * stripeColor / headingKey / bodyKey lookup helpers. This minimal form
 * exists so ChatEntry's sealed permits list compiles after Task 1 (RED→GREEN).
 */
public record ErrorCard(ChatErrorPacket.ErrorCode code, String humanReadable) implements ChatEntry {}
