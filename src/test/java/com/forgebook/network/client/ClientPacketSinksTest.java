package com.forgebook.network.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;

import java.lang.reflect.Constructor;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientPacketSinksTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ClientPacketSinks.replySink = null;
        ClientPacketSinks.errorSink = null;
    }

    @Test
    void replySink_defaultsToNull() {
        assertNull(ClientPacketSinks.replySink);
    }

    @Test
    void errorSink_defaultsToNull() {
        assertNull(ClientPacketSinks.errorSink);
    }

    @Test
    void replySink_canBeAssignedAndInvoked() {
        AtomicReference<String> captured = new AtomicReference<>();
        ClientPacketSinks.replySink = (id, text) -> captured.set(id + ":" + text);
        UUID id = UUID.randomUUID();
        ClientPacketSinks.replySink.accept(id, "hello");
        assertEquals(id + ":hello", captured.get());
    }

    @Test
    void errorSink_canBeAssignedAndInvoked() {
        AtomicReference<String> captured = new AtomicReference<>();
        ClientPacketSinks.errorSink = (id, code, msg) -> captured.set(code + ":" + msg);
        UUID id = UUID.randomUUID();
        ClientPacketSinks.errorSink.accept(id, ErrorCode.FORBIDDEN, "denied");
        assertEquals("FORBIDDEN:denied", captured.get());
    }

    @Test
    void biConsumerType_matchesAssignedLambda() {
        BiConsumer<UUID, String> sink = (id, text) -> {};
        ClientPacketSinks.replySink = sink;
        assertNotNull(ClientPacketSinks.replySink);
    }

    @Test
    void noPublicConstructor() throws Exception {
        Constructor<?>[] ctors = ClientPacketSinks.class.getDeclaredConstructors();
        assertEquals(1, ctors.length);
        assertFalse(java.lang.reflect.Modifier.isPublic(ctors[0].getModifiers()));
    }
}
