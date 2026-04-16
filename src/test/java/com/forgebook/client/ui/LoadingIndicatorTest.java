package com.forgebook.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Cadence contract for the "Thinking…" dot cycler. Period = 2000 ms (4 × 500).
 * Frames: 0 → ".", 1 → "..", 2 → "...", 3 → "".
 */
class LoadingIndicatorTest {

    @Test void frame0ms_dot()              { assertEquals(".",   LoadingIndicator.frame(0L)); }
    @Test void frame500ms_twoDots()        { assertEquals("..",  LoadingIndicator.frame(500L)); }
    @Test void frame1000ms_threeDots()     { assertEquals("...", LoadingIndicator.frame(1000L)); }
    @Test void frame1500ms_blank()         { assertEquals("",    LoadingIndicator.frame(1500L)); }
    @Test void frame2000ms_wrapsToDot()    { assertEquals(".",   LoadingIndicator.frame(2000L)); }
    @Test void frame2500ms_wrapsToTwoDots(){ assertEquals("..",  LoadingIndicator.frame(2500L)); }
}
