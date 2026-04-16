package com.forgebook.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.forgebook.client.ui.InventoryButtonGeometry.Rect;
import org.junit.jupiter.api.Test;

class InventoryButtonGeometryTest {

    /**
     * Vanilla InventoryScreen at window 500×300 has imageWidth=176, imageHeight=166.
     * Mojang centers it: leftPos = (500-176)/2 = 162. But the test picks arbitrary
     * inputs — what matters is the math, not the specific centering.
     */
    @Test
    void vanilla_500x300_centered_computes268_54() {
        Rect r = InventoryButtonGeometry.compute(88, 50, 176);
        assertEquals(268, r.x()); // 88 + 176 + 4
        assertEquals(54, r.y());  // 50 + 4
        assertEquals(20, r.w());
        assertEquals(20, r.h());
    }

    @Test
    void cornerAtOrigin_176wideInventory_places180_4() {
        Rect r = InventoryButtonGeometry.compute(0, 0, 176);
        assertEquals(180, r.x());
        assertEquals(4, r.y());
    }

    @Test
    void nonDefaultInventoryWidth_200_places304_104() {
        Rect r = InventoryButtonGeometry.compute(100, 100, 200);
        assertEquals(304, r.x()); // 100 + 200 + 4
        assertEquals(104, r.y()); // 100 + 4
    }

    @Test
    void size_alwaysTwentyByTwenty() {
        for (int leftPos : new int[]{0, 50, 500, -10}) {
            for (int topPos : new int[]{0, 50, 300, -10}) {
                for (int imageWidth : new int[]{176, 200, 300}) {
                    Rect r = InventoryButtonGeometry.compute(leftPos, topPos, imageWidth);
                    assertEquals(20, r.w(), "w for " + leftPos + "," + topPos + "," + imageWidth);
                    assertEquals(20, r.h(), "h for " + leftPos + "," + topPos + "," + imageWidth);
                }
            }
        }
    }

    @Test
    void recordEquals_sameInputsMatch() {
        Rect a = InventoryButtonGeometry.compute(88, 50, 176);
        Rect b = InventoryButtonGeometry.compute(88, 50, 176);
        assertEquals(a, b);
    }
}
