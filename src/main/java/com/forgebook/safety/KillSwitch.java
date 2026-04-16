package com.forgebook.safety;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CMD-05 global kill switch. Flipped by /forgebook disable|enable; read at
 * every dispatch entry inside Authorizer.authorize as the FIRST check
 * (cheapest, fail-fast — DISABLED beats FORBIDDEN and RATE_LIMITED).
 *
 * Mirrors com.forgebook.ai.SystemPromptCache shape (volatile-holder singleton).
 * AtomicBoolean used for documented intent; a plain volatile boolean would
 * work equally well (no compound operations), but AtomicBoolean documents
 * "flag that multiple threads read and one thread writes."
 *
 * Intentionally NOT in ConfigSnapshot — kill-switch is a runtime override,
 * not a config value. /forgebook reload does NOT reset it. Server restart
 * resets to false (deliberate v1 simplification — RESEARCH §Pattern 5).
 */
public final class KillSwitch {

    private static final AtomicBoolean DISABLED = new AtomicBoolean(false);

    private KillSwitch() {}

    public static boolean isDisabled() { return DISABLED.get(); }

    public static void setDisabled(boolean b) { DISABLED.set(b); }
}
