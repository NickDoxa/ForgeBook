package com.forgebook.client.ui;

/**
 * Pure-function layout math for the chat panel. Called from ChatScreen.init()
 * after reading this.width/this.height, which Minecraft re-invokes on window
 * resize AND GUI-scale change — so layout auto-adapts without a tick loop.
 *
 * Testable without booting Minecraft — the entire math lives here, not in the
 * Screen subclass. Pattern follows Phase-3's com.forgebook.safety.Authorizer
 * primitive-overload seam (Authorizer.java:75-105).
 *
 * UI-SPEC §"Chat panel dimensions" + §"Small-screen fallback" are the source of truth.
 */
public final class ChatPanelLayout {

    /** Minimum window width to render any widget at all. Below this → "Screen too small" label only. */
    public static final int MIN_WIDTH = 240;
    /** Minimum window height to render any widget. */
    public static final int MIN_HEIGHT = 180;
    /** Threshold below which the inventory is hidden and the chat panel occupies full width. */
    public static final int STACKED_THRESHOLD_WIDTH = 320;
    /** Default panel width at ≥ STACKED_THRESHOLD_WIDTH screens. */
    public static final int DEFAULT_PANEL_WIDTH = 240;
    /** Distance of panel top edge from window top. */
    public static final int PANEL_Y_INSET = 20;
    /** Total vertical inset (top + bottom) subtracted from winH to get panelH. */
    public static final int PANEL_VERTICAL_INSET_TOTAL = 40;
    /** Horizontal padding on each side in stacked mode (total subtraction = 16). */
    public static final int STACKED_HORIZONTAL_PADDING = 8;
    /** Left-edge inset for the normal-mode panel. Panel is always pinned left, not centered,
     *  so the chat sits over the inventory without fighting the 3D player-model render layer. */
    public static final int LEFT_EDGE_INSET = 8;

    public record LayoutResult(
        boolean tooSmall,
        boolean stacked,
        int panelX, int panelY, int panelW, int panelH
    ) {}

    private ChatPanelLayout() {}

    /**
     * Computes panel geometry for the given window dimensions (in GUI pixels).
     *
     * @param winW window width in GUI pixels
     * @param winH window height in GUI pixels
     * @return LayoutResult — examine tooSmall first, then stacked, then use panelX/Y/W/H.
     */
    public static LayoutResult compute(int winW, int winH) {
        if (winW < MIN_WIDTH || winH < MIN_HEIGHT) {
            return new LayoutResult(true, false, 0, 0, 0, 0);
        }
        boolean stacked = winW < STACKED_THRESHOLD_WIDTH;
        int panelW = stacked ? (winW - 2 * STACKED_HORIZONTAL_PADDING) : DEFAULT_PANEL_WIDTH;
        // Pin to left edge in both modes. The previous centered layout clashed
        // visually with InventoryScreen's 3D player model (which renders through
        // flat overlays) and with vanilla slot-hover tooltips. Left-pinning also
        // matches user expectation — chat panel sits on the left of the screen,
        // layering over the left portion of the inventory as needed.
        int panelX = stacked ? STACKED_HORIZONTAL_PADDING : LEFT_EDGE_INSET;
        int panelY = PANEL_Y_INSET;
        int panelH = winH - PANEL_VERTICAL_INSET_TOTAL;
        return new LayoutResult(false, stacked, panelX, panelY, panelW, panelH);
    }
}
