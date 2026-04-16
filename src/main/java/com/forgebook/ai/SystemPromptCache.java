package com.forgebook.ai;

/**
 * Volatile holder for the pre-rendered system prompt (AI-08, D-08).
 * Set by SystemPromptBuilder.buildAndCache at ServerStartedEvent (Plan 07)
 * and on /forgebook reload. Read by AiDispatcher per request.
 *
 * Defensive default: get() returns "" when not yet built — caller (AiDispatcher)
 * may treat empty as "fall back to a minimal prompt" or surface a startup error.
 *
 * Mirrors ConfigHolder pattern from Phase 1 (volatile-holder singleton).
 * Thread-safety: volatile provides happens-before between set() on the server-main
 * thread at startup and get() on AiExecutor worker threads per request (T-02-06-07).
 */
public final class SystemPromptCache {
    private static volatile String current = "";

    private SystemPromptCache() {}

    public static String get() { return current; }

    public static void set(String prompt) { current = (prompt == null ? "" : prompt); }
}
