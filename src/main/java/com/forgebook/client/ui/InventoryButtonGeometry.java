package com.forgebook.client.ui;

/**
 * Pure-function geometry for the in-inventory ForgeBook button.
 * UI-SPEC §"Inventory Button Injection": 20×20 button at (leftPos + imageWidth + 4, topPos + 4).
 *
 * Testable without booting Minecraft. Production caller in InventoryButtonInjector
 * (plan 04-05) reads AbstractContainerScreen.getGuiLeft/getGuiTop/getXSize and
 * delegates here — same primitive-overload pattern as Authorizer.authorize
 * (com.forgebook.safety.Authorizer:75-105).
 */
public final class InventoryButtonGeometry {

    /** UI-SPEC button dimension. Square button, standard vanilla idiom. */
    public static final int SIZE = 20;
    /** Horizontal gap between inventory right edge and button left edge. */
    public static final int GAP_X = 4;
    /** Vertical offset from inventory top edge to button top edge. */
    public static final int OFFSET_Y = 4;

    public record Rect(int x, int y, int w, int h) {}

    private InventoryButtonGeometry() {}

    /**
     * Compute button bounds given inventory screen's leftPos, topPos, imageWidth
     * (the vanilla AbstractContainerScreen public accessors).
     *
     * @param leftPos    inventory.getGuiLeft()
     * @param topPos     inventory.getGuiTop()
     * @param imageWidth inventory.getXSize() — vanilla default is 176
     * @return 20×20 Rect positioned 4 px to the right of inventory, 4 px down from top
     */
    public static Rect compute(int leftPos, int topPos, int imageWidth) {
        return new Rect(leftPos + imageWidth + GAP_X, topPos + OFFSET_Y, SIZE, SIZE);
    }
}
