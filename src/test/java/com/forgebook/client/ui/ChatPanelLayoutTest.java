package com.forgebook.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.forgebook.client.ui.ChatPanelLayout.LayoutResult;
import org.junit.jupiter.api.Test;

class ChatPanelLayoutTest {

    @Test
    void normal_1280x720_centered240WidePanel() {
        LayoutResult r = ChatPanelLayout.compute(1280, 720);
        assertFalse(r.tooSmall());
        assertFalse(r.stacked());
        assertEquals(240, r.panelW());
        assertEquals(520, r.panelX()); // (1280-240)/2
        assertEquals(20, r.panelY());
        assertEquals(680, r.panelH()); // 720-40
    }

    @Test
    void stackedBoundary_320x240_isNormal() {
        // 320 is the inclusive lower bound for normal mode (winW >= STACKED_THRESHOLD_WIDTH).
        LayoutResult r = ChatPanelLayout.compute(320, 240);
        assertFalse(r.tooSmall());
        assertFalse(r.stacked());
        assertEquals(240, r.panelW());
        assertEquals(40, r.panelX()); // (320-240)/2
    }

    @Test
    void stackedMode_319x240() {
        LayoutResult r = ChatPanelLayout.compute(319, 240);
        assertFalse(r.tooSmall());
        assertTrue(r.stacked());
        assertEquals(303, r.panelW()); // 319 - 16
        assertEquals(8, r.panelX());
        assertEquals(200, r.panelH()); // 240 - 40
    }

    @Test
    void minDimensions_240x180_stackedOk() {
        LayoutResult r = ChatPanelLayout.compute(240, 180);
        assertFalse(r.tooSmall());
        assertTrue(r.stacked());
        assertEquals(224, r.panelW()); // 240 - 16
        assertEquals(8, r.panelX());
        assertEquals(140, r.panelH());
    }

    @Test
    void belowMinWidth_239x180_tooSmall() {
        LayoutResult r = ChatPanelLayout.compute(239, 180);
        assertTrue(r.tooSmall());
        assertFalse(r.stacked());
        assertEquals(0, r.panelW());
    }

    @Test
    void belowMinHeight_320x179_tooSmall() {
        LayoutResult r = ChatPanelLayout.compute(320, 179);
        assertTrue(r.tooSmall());
    }

    @Test
    void wideScreen_480x360_normalCentered() {
        LayoutResult r = ChatPanelLayout.compute(480, 360);
        assertFalse(r.tooSmall());
        assertFalse(r.stacked());
        assertEquals(240, r.panelW());
        assertEquals(120, r.panelX());
        assertEquals(320, r.panelH()); // 360 - 40
    }

    @Test
    void layoutResult_isRecord_fieldAccessorsWork() {
        LayoutResult r = new LayoutResult(false, false, 100, 20, 240, 400);
        assertEquals(100, r.panelX());
        assertEquals(400, r.panelH());
    }
}
