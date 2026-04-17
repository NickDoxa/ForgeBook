package com.forgebook.client.ui;

import com.forgebook.client.session.ClientChatSession;
import com.forgebook.network.ForgebookNetwork;
import com.forgebook.network.packet.ChatRequestPacket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * Standalone Screen rendered over the InventoryScreen. See RESEARCH
 * "Screen Architecture Resolution" for the full rationale —
 * Minecraft.setScreen replaces the current screen, so we hold the parent
 * InventoryScreen as a field and re-render it below us with mouse coords
 * set to Integer.MAX_VALUE (Pitfall 11) to suppress parent tooltips.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>init() — call parent.init(minecraft, width, height) so its leftPos/topPos
 *       are computed at our window dimensions, then build our own widgets.</li>
 *   <li>render() — parent.render(graphics, MAX, MAX, partialTick) first, then
 *       renderBackground (our dim overlay) and super.render for our widgets.</li>
 *   <li>onClose() — ClientChatSession.clear() FIRST, then setScreen(parent).
 *       Order is locked by Pitfall 9.</li>
 *   <li>isPauseScreen() — returns false. Pitfall 8: prevents integrated-server
 *       tick freeze (matches InventoryScreen).</li>
 *   <li>keyPressed(ESC) — onClose.</li>
 *   <li>keyPressed(ENTER) when input focused, non-blank, and not pending — submit.</li>
 * </ul>
 *
 * <p>UI-08 reverse firewall: this class lives under {@code com.forgebook.client.ui}
 * so {@code net.minecraft.*} imports are permitted (SCAF-02 forward rule).
 * It MUST NOT import {@code com.forgebook.ai.*}, {@code com.forgebook.safety.*},
 * or {@code com.forgebook.config.ApiKey}. The chat UI only sends a
 * ChatRequestPacket via the wire and renders whatever the server replies;
 * every AI decision stays server-side.
 */
public class ChatScreen extends Screen {

    private static final Logger LOG = LogManager.getLogger();

    /**
     * EditBox max chars. UI-SPEC §"Interaction Contract" does not pin a number;
     * we pick 512 to leave comfortable headroom below the
     * ChatRequestPacket 32 000-byte wire cap.
     */
    private static final int INPUT_MAX_LENGTH = 512;
    private static final int INPUT_HEIGHT = 20;
    private static final int SUBMIT_BUTTON_W = 40;
    private static final int SUBMIT_BUTTON_H = 20;
    private static final int INPUT_SUBMIT_GAP = 2;
    /** Gap between panel content and input row (vanilla 2-px idiom). */
    private static final int INPUT_TOP_GAP = 2;

    private final Screen parent;
    private EditBox input;
    private Button submitBtn;
    private ChatPanelWidget panel;

    public ChatScreen(Screen parent) {
        super(Component.translatable("forgebook.chat.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Let the parent (InventoryScreen) re-size its widgets at our current
        // window dimensions so its render() call below has accurate
        // leftPos/topPos. Idempotent — Mojang uses the same pattern in
        // "back button" screens (RESEARCH "Screen Architecture Resolution").
        if (parent != null) {
            parent.init(this.minecraft, this.width, this.height);
        }

        // Compute scale-aware layout via pure function from plan 04-02.
        ChatPanelLayout.LayoutResult layout =
            ChatPanelLayout.compute(this.width, this.height);
        if (layout.tooSmall()) {
            // UI-07 minimum-width guard — render just a label, no widgets.
            // The message itself is drawn in render() below when the widget
            // list is empty.
            return;
        }

        // Panel occupies the top portion; input + submit row sits below.
        int inputRowH = INPUT_HEIGHT + INPUT_TOP_GAP;
        int panelH = layout.panelH() - inputRowH;
        this.panel = this.addRenderableWidget(
            new ChatPanelWidget(layout.panelX(), layout.panelY(), layout.panelW(), panelH));

        int inputRowY = layout.panelY() + panelH + INPUT_TOP_GAP;
        int inputW = layout.panelW() - SUBMIT_BUTTON_W - INPUT_SUBMIT_GAP;
        this.input = new EditBox(this.font, layout.panelX(), inputRowY, inputW, INPUT_HEIGHT,
            Component.translatable("forgebook.chat.input.placeholder"));
        this.input.setMaxLength(INPUT_MAX_LENGTH);
        this.input.setHint(Component.translatable("forgebook.chat.input.placeholder"));
        // No-op responder; submit is explicit via Ask button or ENTER.
        this.input.setResponder(s -> {});
        this.addRenderableWidget(this.input);

        this.submitBtn = Button.builder(
                Component.translatable("forgebook.chat.submit"),
                this::onSubmitClicked)
            .bounds(layout.panelX() + inputW + INPUT_SUBMIT_GAP, inputRowY,
                    SUBMIT_BUTTON_W, SUBMIT_BUTTON_H)
            .build();
        this.addRenderableWidget(this.submitBtn);

        this.setInitialFocus(this.input);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Dim the background with vanilla's dark overlay. We intentionally do
        // NOT re-render the parent InventoryScreen: its 3D player-model
        // rendering path (renderEntityInInventoryFollowsMouse) uses pose-stack
        // 3D draws that bleed through flat 2D overlays, and its slot-tooltip
        // pass is deferred by GuiGraphics so it renders AFTER our paint —
        // both cause items/player to appear on top of the chat panel. While
        // chat is open the panel owns the screen; ESC closes chat and
        // returns the player to the inventory (ChatScreen.onClose restores
        // the parent via setScreen).
        this.renderBackground(graphics);

        // If the screen was too small at init time (no widgets added), draw a
        // centered "screen too small" label and bail.
        ChatPanelLayout.LayoutResult layout =
            ChatPanelLayout.compute(this.width, this.height);
        if (layout.tooSmall()) {
            Component tooSmall = Component.translatable("forgebook.chat.screen_too_small");
            int tw = this.font.width(tooSmall);
            graphics.drawString(this.font, tooSmall,
                (this.width - tw) / 2, this.height / 2, 0xFFE0E0E0, false);
            return;
        }

        // Render all widgets added via addRenderableWidget: panel, input,
        // submit button.
        super.render(graphics, mouseX, mouseY, partialTick);

        // Disable input + submit while a request is in flight
        // (UI-SPEC §"In-flight state machine"). Re-check per frame — cheap.
        boolean pending = ClientChatSession.get().isPending();
        if (this.input != null) {
            this.input.setEditable(!pending);
        }
        if (this.submitBtn != null && this.input != null) {
            this.submitBtn.active = !pending && !this.input.getValue().isBlank();
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Pitfall 8 — matches InventoryScreen; prevents integrated-server freeze.
        return false;
    }

    @Override
    public void onClose() {
        // Pitfall 9 — clear BEFORE setScreen so any rapid re-open sees fresh state.
        ClientChatSession.get().clear();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER
                && this.input != null
                && this.input.isFocused()
                && !this.input.getValue().isBlank()
                && !ClientChatSession.get().isPending()) {
            this.onSubmitClicked(this.submitBtn);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onSubmitClicked(Button btn) {
        if (this.input == null) return;
        String msg = this.input.getValue().trim();
        if (msg.isEmpty() || ClientChatSession.get().isPending()) return;
        UUID reqId = UUID.randomUUID();
        ClientChatSession.get().appendUserMessage(reqId, msg);
        ClientChatSession.get().markPending(reqId);
        this.input.setValue("");
        if (this.panel != null) {
            this.panel.scrollToBottom();
        }
        try {
            ForgebookNetwork.CHANNEL.sendToServer(new ChatRequestPacket(reqId, msg));
        } catch (Throwable t) {
            // Catch-all: if the channel is unavailable (e.g. offline / LAN
            // race / vanilla server with no ForgeBook installed), surface a
            // no-server error card via the session itself. Mirrors
            // UI-SPEC §"Client vanilla-server detection" — no raw Throwable
            // reaches the render layer.
            LOG.warn("[ForgeBook] Failed to send ChatRequestPacket for {}; surfacing no-server error.", reqId, t);
            ClientChatSession.get().appendError(reqId,
                com.forgebook.network.packet.ChatErrorPacket.ErrorCode.TRANSPORT,
                Component.translatable("forgebook.error.no_server.body").getString());
        }
    }
}
