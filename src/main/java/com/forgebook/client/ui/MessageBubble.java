package com.forgebook.client.ui;

/**
 * A user or assistant message bubble value type. Differentiation per UI-SPEC:
 * USER = right-aligned with "You" label; ASSISTANT = left-aligned with "ForgeBook" label.
 * Rendering lives in ChatPanelWidget; this record holds only data.
 */
public record MessageBubble(Kind kind, String text) implements ChatEntry {

    public enum Kind { USER, ASSISTANT }

    public static MessageBubble user(String text) {
        return new MessageBubble(Kind.USER, text);
    }

    public static MessageBubble assistant(String text) {
        return new MessageBubble(Kind.ASSISTANT, text);
    }

    /**
     * Pure-function bubble height math. Extracted for unit testing without a Font
     * instance. Caller computes lineCount via Font.split(...).size() at render time.
     *
     * @param lineCount       number of wrapped lines (from Font.split)
     * @param paddingTop      pixels above first line (UI-SPEC: 4)
     * @param paddingBottom   pixels below last line (UI-SPEC: 4)
     * @param lineHeight      Font.lineHeight (vanilla = 9)
     * @param lineGap         pixels between lines (UI-SPEC: 1)
     * @return total bubble height = paddingTop + lineCount * (lineHeight + lineGap) + paddingBottom
     */
    public static int computeBubbleHeight(int lineCount, int paddingTop, int paddingBottom, int lineHeight, int lineGap) {
        return paddingTop + lineCount * (lineHeight + lineGap) + paddingBottom;
    }
}
