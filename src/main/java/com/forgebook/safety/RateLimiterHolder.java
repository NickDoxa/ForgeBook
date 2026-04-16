package com.forgebook.safety;

/**
 * Volatile-holder singleton for the current RateLimiter. Seeded on
 * ServerStartingEvent (wired in Plan 06 from ForgebookReloadCommand or
 * ForgeBookMod) and swapped on /forgebook reload.
 *
 * Mirrors com.forgebook.integration.ModpackContextCache shape exactly —
 * private ctor, private static volatile field, public static get/swap.
 *
 * Returns null before seeding. Callers (Authorizer, ChatRequestHandler,
 * Plan 06 subcommands) MUST handle the pre-seed null case defensively
 * (the existing AiDispatcher pattern of "ConfigHolder.get() == null →
 * PROVIDER error" is the reference). In practice, seeding happens on
 * ServerStartingEvent which fires strictly before any packet or command
 * can be served.
 *
 * swap() is a single volatile store — no synchronization needed.
 */
public final class RateLimiterHolder {

    private static volatile RateLimiter current = null;

    private RateLimiterHolder() {}

    public static RateLimiter get() { return current; }

    public static void swap(RateLimiter next) { current = next; }
}
