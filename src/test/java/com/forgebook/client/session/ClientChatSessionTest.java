package com.forgebook.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgebook.client.ui.ErrorCard;
import com.forgebook.client.ui.MessageBubble;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientChatSessionTest {

    private ClientChatSession session;

    @BeforeEach
    void setUp() {
        session = ClientChatSession.get();
        session.clear();
    }

    @Test
    void fresh_sessionIsEmptyAndIdle() {
        assertFalse(session.isPending());
        assertTrue(session.snapshotBubbles().isEmpty());
        assertTrue(session.snapshotErrors().isEmpty());
        assertNull(session.pendingRequestId());
    }

    @Test
    void appendUserMessage_plusMarkPending_entersPending() {
        UUID id = UUID.randomUUID();
        session.appendUserMessage(id, "hi");
        session.markPending(id);
        assertTrue(session.isPending());
        assertEquals(id, session.pendingRequestId());
        assertEquals(1, session.snapshotBubbles().size());
        assertEquals(MessageBubble.Kind.USER, session.snapshotBubbles().get(0).kind());
    }

    @Test
    void append_withMatchingId_appendsAssistantBubbleAndReturnsIdle() {
        UUID id = UUID.randomUUID();
        session.appendUserMessage(id, "hi");
        session.markPending(id);
        session.append(id, "hello back");
        assertFalse(session.isPending());
        assertNull(session.pendingRequestId());
        List<MessageBubble> bubbles = session.snapshotBubbles();
        assertEquals(2, bubbles.size());
        assertEquals(MessageBubble.Kind.ASSISTANT, bubbles.get(1).kind());
        assertEquals("hello back", bubbles.get(1).text());
    }

    @Test
    void append_withMismatchedId_isSilentNoOp_staleGuardUID11() {
        UUID id = UUID.randomUUID();
        UUID staleId = UUID.randomUUID();
        session.appendUserMessage(id, "hi");
        session.markPending(id);
        session.append(staleId, "lol");
        // Still pending, no assistant bubble added
        assertTrue(session.isPending());
        assertEquals(id, session.pendingRequestId());
        assertEquals(1, session.snapshotBubbles().size());
    }

    @Test
    void appendError_withMatchingId_appendsErrorCardAndReturnsIdle() {
        UUID id = UUID.randomUUID();
        session.markPending(id);
        session.appendError(id, ErrorCode.FORBIDDEN, "Not allowed");
        assertFalse(session.isPending());
        List<ErrorCard> errs = session.snapshotErrors();
        assertEquals(1, errs.size());
        assertEquals(ErrorCode.FORBIDDEN, errs.get(0).code());
        assertEquals("Not allowed", errs.get(0).humanReadable());
    }

    @Test
    void appendError_withMismatchedId_isSilentNoOp() {
        UUID id = UUID.randomUUID();
        UUID staleId = UUID.randomUUID();
        session.markPending(id);
        session.appendError(staleId, ErrorCode.PROVIDER, "x");
        assertTrue(session.isPending());
        assertTrue(session.snapshotErrors().isEmpty());
    }

    @Test
    void clear_resetsAllState() {
        UUID id = UUID.randomUUID();
        session.appendUserMessage(id, "hi");
        session.markPending(id);
        session.clear();
        assertFalse(session.isPending());
        assertNull(session.pendingRequestId());
        assertTrue(session.snapshotBubbles().isEmpty());
        assertTrue(session.snapshotErrors().isEmpty());
    }

    @Test
    void appendAfterClear_withOldId_isNoOp() {
        UUID id = UUID.randomUUID();
        session.appendUserMessage(id, "hi");
        session.markPending(id);
        session.clear();
        session.append(id, "late reply");
        assertTrue(session.snapshotBubbles().isEmpty()); // clear wiped bubbles; append dropped by stale-guard
    }

    @Test
    void markPending_overwritesPriorPendingId() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        session.markPending(first);
        session.markPending(second);
        assertEquals(second, session.pendingRequestId());
        // Reply to first is now stale:
        session.append(first, "first reply");
        assertTrue(session.snapshotBubbles().isEmpty());
        // Reply to second succeeds:
        session.append(second, "second reply");
        assertEquals(1, session.snapshotBubbles().size());
    }

    @Test
    void snapshotBubbles_isDefensiveCopy() {
        UUID id = UUID.randomUUID();
        session.appendUserMessage(id, "hi");
        List<MessageBubble> copy = session.snapshotBubbles();
        // List.copyOf is an immutable view — mutation attempts throw UnsupportedOperationException.
        assertThrows(UnsupportedOperationException.class, () -> copy.add(MessageBubble.user("evil")));
        // Internal state unaffected.
        assertEquals(1, session.snapshotBubbles().size());
    }
}
