package com.forgebook.client.ui;

import com.forgebook.client.session.ClientChatSession;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The single rendering surface for the Phase-4 chat UI: panel frame, message list
 * (user/assistant bubbles), inline error cards, loading indicator, and scrollbar.
 *
 * <p>Architectural invariant (UI-08 reverse firewall): this class lives under
 * {@code com.forgebook.client.ui} so {@code net.minecraft.*} imports are permitted
 * (SCAF-02 forward rule). Conversely it MUST NOT import
 * {@code com.forgebook.ai.*}, {@code com.forgebook.safety.*}, or
 * {@code com.forgebook.config.ApiKey}. Only session state and the wire-protocol
 * {@code ChatErrorPacket.ErrorCode} (reached transitively through {@link ErrorCard})
 * cross into this file.
 *
 * <p>All layout constants come from the Phase-4 UI-SPEC color palette and spacing
 * tables. All text wrapping uses {@link Font#split(FormattedText, int)} — never
 * manual whitespace tokenisation (RESEARCH Pitfall: breaks on CJK, locks in a
 * single whitespace model, ignores Minecraft's formatting codes).
 *
 * <p>Rendering tests are intentionally deferred to plan 04-06 live smoke — the
 * pure-function inputs (bubble-height math, stripe-color table, dot-cycler frame,
 * panel-layout compute) are fully unit-tested in plans 04-01 and 04-02. The only
 * seam this class adds is the Font/GuiGraphics call sequence, which requires a
 * booted Minecraft to verify.
 */
public class ChatPanelWidget extends AbstractWidget {

    // UI-SPEC color palette (ARGB)
    private static final int COLOR_PANEL_BG         = 0xF0101019;
    private static final int COLOR_PANEL_BORDER_OUT = 0xFF000000;
    private static final int COLOR_PANEL_BORDER_IN  = 0xFF5A5A6E;
    private static final int COLOR_BUBBLE_FILL      = 0xFF2E2F37;
    private static final int COLOR_BUBBLE_BORDER    = 0xFF1A1A22;
    private static final int COLOR_USER_TEXT        = 0xFFFFFFFF;
    private static final int COLOR_ASSISTANT_TEXT   = 0xFFE0E0E0;
    private static final int COLOR_ACCENT           = 0xFFB0C4F5;
    private static final int COLOR_SCROLLBAR_TRACK  = 0x40202028;
    private static final int COLOR_SCROLLBAR_THUMB  = 0xFF8A8AA0;
    private static final int COLOR_SCROLLBAR_ACTIVE = 0xFFB0C4F5;
    private static final int COLOR_PLACEHOLDER      = 0xFF808080;

    // UI-SPEC spacing tokens (GUI pixels)
    private static final int PANEL_PADDING   = 6;
    private static final int BUBBLE_PADDING  = 4;
    private static final int BUBBLE_LINE_GAP = 1;
    private static final int MESSAGE_GAP     = 4;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int ERROR_STRIPE_W  = 4;

    /** Bubble width budget: 75% of the content area per UI-SPEC. */
    private static final float BUBBLE_MAX_WIDTH_FRAC = 0.75f;

    /** Pixels scrolled per mouse-wheel notch. */
    private static final double SCROLL_STEP_PX = 10.0;

    private double scrollAmount = 0.0;
    private boolean scrollbarDragging = false;

    public ChatPanelWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("forgebook.chat.title"));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderPanelBackground(graphics);
        renderContent(graphics, mouseX, mouseY);
    }

    // -- Panel chrome --------------------------------------------------------

    private void renderPanelBackground(GuiGraphics g) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        // Dominant fill
        g.fill(x, y, x + w, y + h, COLOR_PANEL_BG);
        // Outer 1-px border
        g.fill(x, y, x + w, y + 1, COLOR_PANEL_BORDER_OUT);
        g.fill(x, y + h - 1, x + w, y + h, COLOR_PANEL_BORDER_OUT);
        g.fill(x, y, x + 1, y + h, COLOR_PANEL_BORDER_OUT);
        g.fill(x + w - 1, y, x + w, y + h, COLOR_PANEL_BORDER_OUT);
        // Inner bevel highlight (vanilla tooltip idiom)
        g.fill(x + 1, y + 1, x + w - 1, y + 2, COLOR_PANEL_BORDER_IN);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, COLOR_PANEL_BORDER_IN);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, COLOR_PANEL_BORDER_IN);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, COLOR_PANEL_BORDER_IN);
    }

    // -- Content flow --------------------------------------------------------

    private void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        // Fresh snapshots per render — synchronized on the session, defensive copies,
        // immune to mid-frame tears from packet-handler writes.
        List<MessageBubble> bubbles = ClientChatSession.get().snapshotBubbles();
        List<ErrorCard> errors = ClientChatSession.get().snapshotErrors();
        boolean pending = ClientChatSession.get().isPending();

        int contentX = getX() + PANEL_PADDING;
        int contentY = getY() + PANEL_PADDING;
        int contentW = getWidth() - 2 * PANEL_PADDING - SCROLLBAR_WIDTH;
        int contentH = getHeight() - 2 * PANEL_PADDING;

        // Empty-state placeholder (UI-SPEC §"Copywriting Contract" + §"Typography — italic hint").
        if (bubbles.isEmpty() && errors.isEmpty() && !pending) {
            Component hint = Component.translatable("forgebook.chat.empty.body");
            List<FormattedCharSequence> hintLines = font.split(FormattedText.of(hint.getString()), contentW);
            int blockH = hintLines.size() * font.lineHeight
                + Math.max(0, hintLines.size() - 1) * BUBBLE_LINE_GAP;
            int lineY = contentY + (contentH - blockH) / 2;
            for (FormattedCharSequence line : hintLines) {
                int lineW = font.width(line);
                g.drawString(
                    font, line,
                    contentX + (contentW - lineW) / 2,
                    lineY,
                    COLOR_PLACEHOLDER, false
                );
                lineY += font.lineHeight + BUBBLE_LINE_GAP;
            }
            return;
        }

        // Scissor to the content rect so overflow rows don't bleed onto the panel border
        // or into the scrollbar track.
        g.enableScissor(contentX, contentY, contentX + contentW, contentY + contentH);
        int cursorY = contentY - (int) scrollAmount;

        for (MessageBubble mb : bubbles) {
            cursorY = renderBubble(g, font, mb, contentX, cursorY, contentW);
            cursorY += MESSAGE_GAP;
        }
        for (ErrorCard ec : errors) {
            cursorY = renderErrorCard(g, font, ec, contentX, cursorY, contentW);
            cursorY += MESSAGE_GAP;
        }
        if (pending) {
            cursorY = renderLoading(g, font, contentX, cursorY, contentW);
        }
        g.disableScissor();

        // Reconstruct total laid-out height (cursorY was offset by scrollAmount at start)
        // and clamp scroll — must happen AFTER layout so we know maxScroll.
        int totalHeight = (cursorY + (int) scrollAmount) - contentY;
        int maxScroll = Math.max(0, totalHeight - contentH);
        if (scrollAmount > maxScroll) scrollAmount = maxScroll;
        if (scrollAmount < 0) scrollAmount = 0;

        renderScrollbar(g, contentH, totalHeight, mouseX, mouseY);
    }

    // -- Message bubble ------------------------------------------------------

    private int renderBubble(GuiGraphics g, Font font, MessageBubble mb, int contentX, int y, int contentW) {
        int bubbleMaxW = (int) (contentW * BUBBLE_MAX_WIDTH_FRAC);
        int textMaxW = Math.max(1, bubbleMaxW - 2 * BUBBLE_PADDING);
        List<FormattedCharSequence> lines = font.split(FormattedText.of(mb.text()), textMaxW);
        int lineCount = Math.max(1, lines.size());
        int bubbleH = MessageBubble.computeBubbleHeight(
            lineCount, BUBBLE_PADDING, BUBBLE_PADDING, font.lineHeight, BUBBLE_LINE_GAP
        );
        int bubbleW = bubbleMaxW;
        int bubbleX = (mb.kind() == MessageBubble.Kind.USER)
            ? (contentX + contentW - bubbleW)  // right-aligned (UI-SPEC §"Message bubble differentiation")
            : contentX;                        // left-aligned

        // Label above bubble: bold "You" (white) or bold "ForgeBook" (accent)
        boolean isUser = mb.kind() == MessageBubble.Kind.USER;
        Component label = isUser
            ? Component.literal("\u00a7lYou")
            : Component.literal("\u00a7lForgeBook");
        int labelColor = isUser ? COLOR_USER_TEXT : COLOR_ACCENT;
        int labelX = isUser
            ? (bubbleX + bubbleW - font.width(label))
            : bubbleX;
        g.drawString(font, label, labelX, y, labelColor, false);
        int bubbleY = y + font.lineHeight + 1;

        // Bubble fill + 1-px border
        g.fill(bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH, COLOR_BUBBLE_FILL);
        g.fill(bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + 1, COLOR_BUBBLE_BORDER);
        g.fill(bubbleX, bubbleY + bubbleH - 1, bubbleX + bubbleW, bubbleY + bubbleH, COLOR_BUBBLE_BORDER);
        g.fill(bubbleX, bubbleY, bubbleX + 1, bubbleY + bubbleH, COLOR_BUBBLE_BORDER);
        g.fill(bubbleX + bubbleW - 1, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH, COLOR_BUBBLE_BORDER);
        // 2-px accent tail (right for user, left for assistant)
        if (isUser) {
            g.fill(bubbleX + bubbleW - 2, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH, COLOR_ACCENT);
        } else {
            g.fill(bubbleX, bubbleY, bubbleX + 2, bubbleY + bubbleH, COLOR_ACCENT);
        }

        // Body text (no shadow — bubble fill already provides contrast)
        int textColor = isUser ? COLOR_USER_TEXT : COLOR_ASSISTANT_TEXT;
        int lineY = bubbleY + BUBBLE_PADDING;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, bubbleX + BUBBLE_PADDING, lineY, textColor, false);
            lineY += font.lineHeight + BUBBLE_LINE_GAP;
        }
        return bubbleY + bubbleH;
    }

    // -- Error card ----------------------------------------------------------

    private int renderErrorCard(GuiGraphics g, Font font, ErrorCard ec, int contentX, int y, int contentW) {
        int cardW = contentW;
        int stripeColor = ErrorCard.stripeColor(ec.code());
        Component heading = Component.translatable(ErrorCard.headingKey(ec.code()));

        // Server-supplied humanReadable takes precedence (Phase 3 pre-formats RATE_LIMITED %d).
        // Fallback to i18n body key when humanReadable is null or blank (treat as if the server
        // didn't emit a user-facing string).
        String bodyText = (ec.humanReadable() != null && !ec.humanReadable().isBlank())
            ? ec.humanReadable()
            : Component.translatable(ErrorCard.bodyKey(ec.code())).getString();

        int textMaxW = Math.max(1, cardW - ERROR_STRIPE_W - 2 * BUBBLE_PADDING);
        List<FormattedCharSequence> lines = font.split(FormattedText.of(bodyText), textMaxW);
        int lineCount = Math.max(1, lines.size());
        int cardH = BUBBLE_PADDING
                + font.lineHeight + 2                              // heading line + 2-px gap
                + lineCount * (font.lineHeight + BUBBLE_LINE_GAP)
                + BUBBLE_PADDING;

        // Card fill + border
        g.fill(contentX, y, contentX + cardW, y + cardH, COLOR_BUBBLE_FILL);
        g.fill(contentX, y, contentX + cardW, y + 1, COLOR_BUBBLE_BORDER);
        g.fill(contentX, y + cardH - 1, contentX + cardW, y + cardH, COLOR_BUBBLE_BORDER);
        g.fill(contentX, y, contentX + 1, y + cardH, COLOR_BUBBLE_BORDER);
        g.fill(contentX + cardW - 1, y, contentX + cardW, y + cardH, COLOR_BUBBLE_BORDER);
        // 4-px left stripe in taxonomy color (UI-SPEC §"Phase-3 Error Taxonomy ⇢ UI Mapping")
        g.fill(contentX, y, contentX + ERROR_STRIPE_W, y + cardH, stripeColor);

        // Heading (bold, tinted) then wrapped body (neutral text color)
        g.drawString(
            font, heading,
            contentX + ERROR_STRIPE_W + BUBBLE_PADDING,
            y + BUBBLE_PADDING,
            stripeColor, false
        );
        int lineY = y + BUBBLE_PADDING + font.lineHeight + 2;
        for (FormattedCharSequence line : lines) {
            g.drawString(
                font, line,
                contentX + ERROR_STRIPE_W + BUBBLE_PADDING, lineY,
                COLOR_ASSISTANT_TEXT, false
            );
            lineY += font.lineHeight + BUBBLE_LINE_GAP;
        }
        return y + cardH;
    }

    // -- Loading indicator ---------------------------------------------------

    private int renderLoading(GuiGraphics g, Font font, int contentX, int y, int contentW) {
        String dots = LoadingIndicator.frame(System.currentTimeMillis());
        Component label = Component.translatable("forgebook.chat.loading");
        String rendered = label.getString() + dots;
        int textW = font.width(rendered);
        int lx = contentX + (contentW - textW) / 2;
        g.drawString(font, rendered, lx, y + 2, COLOR_ACCENT, false);
        return y + font.lineHeight + 2;
    }

    // -- Scrollbar -----------------------------------------------------------

    private void renderScrollbar(GuiGraphics g, int viewH, int totalH, int mouseX, int mouseY) {
        if (totalH <= viewH) return; // no overflow → no scrollbar
        int trackX = getX() + getWidth() - PANEL_PADDING - SCROLLBAR_WIDTH;
        int trackY = getY() + PANEL_PADDING;
        int trackH = viewH;
        g.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, COLOR_SCROLLBAR_TRACK);

        float thumbFrac = (float) viewH / (float) totalH;
        int thumbH = Math.max(12, (int) (trackH * thumbFrac));
        int maxScroll = totalH - viewH;
        float scrollFrac = (maxScroll > 0) ? (float) (scrollAmount / maxScroll) : 0f;
        int thumbY = trackY + (int) ((trackH - thumbH) * scrollFrac);
        int color = scrollbarDragging ? COLOR_SCROLLBAR_ACTIVE : COLOR_SCROLLBAR_THUMB;
        g.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, color);
    }

    // -- Mouse / focus -------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // Negative delta = scroll down (further into history). Scroll amount grows.
        scrollAmount -= delta * SCROLL_STEP_PX;
        if (scrollAmount < 0) scrollAmount = 0;
        // Upper clamp happens at next render once total content height is known.
        return true;
    }

    /**
     * Requests auto-scroll to the bottom on the next render. Called by ChatScreen
     * after user submits a message, so the new reply is immediately visible.
     */
    public void scrollToBottom() {
        this.scrollAmount = Double.MAX_VALUE; // clamped in renderContent
    }

    // -- Narration -----------------------------------------------------------

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("forgebook.chat.title"));
        int msgCount = ClientChatSession.get().snapshotBubbles().size();
        int errCount = ClientChatSession.get().snapshotErrors().size();
        boolean pending = ClientChatSession.get().isPending();
        String summary = msgCount + " messages, " + errCount + " errors"
            + (pending ? ", thinking" : "");
        output.add(NarratedElementType.HINT, Component.literal(summary));
    }
}
