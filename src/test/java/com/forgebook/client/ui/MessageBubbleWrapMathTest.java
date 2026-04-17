package com.forgebook.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MessageBubbleWrapMathTest {

    @Test
    void userFactory_setsUserKind() {
        MessageBubble mb = MessageBubble.user("hi");
        assertEquals(MessageBubble.Kind.USER, mb.kind());
        assertEquals("hi", mb.text());
    }

    @Test
    void assistantFactory_setsAssistantKind() {
        MessageBubble mb = MessageBubble.assistant("reply");
        assertEquals(MessageBubble.Kind.ASSISTANT, mb.kind());
    }

    @Test
    void computeBubbleHeight_oneLine_padding4_lineHeight9_gap1_equals18() {
        // 4 + 1 * (9 + 1) + 4 = 18
        assertEquals(18, MessageBubble.computeBubbleHeight(1, 4, 4, 9, 1));
    }

    @Test
    void computeBubbleHeight_threeLines_equals38() {
        // 4 + 3 * 10 + 4 = 38
        assertEquals(38, MessageBubble.computeBubbleHeight(3, 4, 4, 9, 1));
    }

    @Test
    void computeBubbleHeight_zeroLines_isPaddingOnly() {
        // 4 + 0 + 4 = 8
        assertEquals(8, MessageBubble.computeBubbleHeight(0, 4, 4, 9, 1));
    }

    @Test
    void messageBubble_isChatEntry() {
        ChatEntry entry = MessageBubble.user("x");
        assertTrue(entry instanceof MessageBubble);
    }
}
