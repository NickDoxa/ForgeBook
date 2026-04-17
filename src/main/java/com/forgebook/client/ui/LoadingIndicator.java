package com.forgebook.client.ui;

/**
 * Pure-function dot cycler for the "Thinking…" pending-state indicator.
 * Cadence: 500 ms per frame; 4 frames per cycle (".", "..", "...", "" then wrap).
 * Caller supplies current time in ms (from System.currentTimeMillis() in production).
 * Kept separate from render code to allow deterministic unit testing without a
 * game tick loop.
 */
public final class LoadingIndicator {

    /** Total cycle length in ms (4 frames × 500 ms). */
    public static final long PERIOD_MS = 2000L;
    /** Frame duration in ms. */
    public static final long FRAME_MS = 500L;

    private LoadingIndicator() {}

    /**
     * Returns the dot string for the current frame in the animation cycle.
     * Frames: 0 → ".", 1 → "..", 2 → "...", 3 → "" (blank). Cycle repeats every {@link #PERIOD_MS}.
     *
     * @param nowMs current time in ms (must be ≥ 0)
     * @return one of ".", "..", "...", or ""
     */
    public static String frame(long nowMs) {
        long phase = Math.floorMod(nowMs, PERIOD_MS);
        int idx = (int) (phase / FRAME_MS);
        return switch (idx) {
            case 0 -> ".";
            case 1 -> "..";
            case 2 -> "...";
            default -> "";
        };
    }
}
