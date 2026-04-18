package com.forgebook.safety;

import java.util.UUID;

import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Central authorization gate for all three AI entry points (CHAT_UI packet handler,
 * /forgebook ask, /forgebook item). SAFE-01 (OP gate) + SAFE-02 (rate limit) +
 * SAFE-03 (retry-after message) + SAFE-06 (spoof-resistant: callers invoke BEFORE
 * submitting to AiExecutor).
 *
 * <h2>Check order (fixed)</h2>
 * <ol>
 *   <li>KillSwitch.isDisabled() -&gt; DISABLED (cheapest; fail-fast)</li>
 *   <li>sender == null -&gt; FORBIDDEN (defensive against /execute as @e invocations)</li>
 *   <li>opOnly &amp;&amp; !isOp -&gt; FORBIDDEN</li>
 *   <li>!isOp -&gt; rate-limit tryAcquire; Limited -&gt; RATE_LIMITED with retry-after</li>
 * </ol>
 * OPs bypass the rate limit entirely (SAFE-02: "OPs always bypass").
 *
 * <h2>humanReadable strings are CANNED LITERALS</h2>
 * Never concatenate user input (UUID, message text, error details). The only
 * dynamic content allowed is the retry-after seconds (a long, not user-supplied).
 * Enforced by code review per Pitfall 5 — ChatErrorPacket.humanReadable is wire
 * payload AND audit log field.
 *
 * <h2>Pure function</h2>
 * No I/O, no mutation, no volatile reads beyond KillSwitch.isDisabled() and
 * RateLimiter.tryAcquire (caller passes a fresh ConfigSnapshot per D-14).
 *
 * <h2>Test seam (package-private {@link #authorize(ConfigSnapshot, UUID, boolean, RequestKind, RateLimiter)})</h2>
 * The primitive-args overload exists because {@code ServerPlayer} cannot be mocked
 * with Mockito in a pure-JUnit environment (its supertype chain drags in Minecraft
 * classes that fail to initialize outside the game harness — see CLAUDE.md
 * "avoid mocking Minecraft classes"). The public ServerPlayer-taking entry point
 * simply unpacks the two fields the auth logic actually reads (UUID, isOp) and
 * delegates to the primitive overload. This keeps behaviour identical while
 * letting unit tests construct the exact check-order scenarios without mocking
 * ServerPlayer.
 */
public final class Authorizer {

    /** Result type — sealed to force exhaustive handling at call sites. */
    public sealed interface Result permits Allowed, Denied {}

    /** Caller proceeds to AiExecutor.submit(...). */
    public record Allowed() implements Result {}

    /**
     * Caller short-circuits with ChatErrorPacket(code, humanReadable) or sendFailure.
     *
     * <h3>Phase 5 (REL-02) — Option A split fields</h3>
     * <ul>
     *   <li>{@code humanReadable} — default-locale English fallback, preserved for
     *       {@link com.forgebook.network.packet.ChatErrorPacket} wire encoding
     *       ({@code buf.writeUtf}) and {@code RequestAuditLogger} fallback. NOT changed
     *       to a Component to avoid breaking the SimpleChannel protocol version.</li>
     *   <li>{@code feedback} — {@link Component#translatable(String, Object...)} carrying
     *       the i18n key (+ optional args) for command-surface rendering. Consumed by
     *       {@code AskSubcommand}, {@code ItemSubcommand}, and {@code RagItemPipeline}.</li>
     * </ul>
     */
    public record Denied(ErrorCode code, String humanReadable, Component feedback) implements Result {}

    private Authorizer() {}

    /**
     * Public production entry. Extracts UUID + isOp from the ServerPlayer (or passes
     * null/false if sender itself is null) and delegates to the primitive overload.
     */
    public static Result authorize(ConfigSnapshot snap, ServerPlayer sender, RequestKind kind,
                                   RateLimiter limiter) {
        UUID uuid = (sender == null) ? null : sender.getUUID();
        boolean isOp = (sender != null) && sender.hasPermissions(2);
        return authorize(snap, uuid, isOp, kind, limiter);
    }

    /**
     * Re-check OP gate + kill switch without consuming a rate-limit token. Used by
     * {@link com.forgebook.ai.RagItemPipeline#run} in the off-tick executor task —
     * {@link com.forgebook.command.ItemSubcommand} already called
     * {@link #authorize(ConfigSnapshot, ServerPlayer, RequestKind, RateLimiter)} on
     * the tick thread and consumed a token; re-consuming one here would double-spend
     * the per-player bucket (visible since the 1.0.6 burst cap).
     *
     * <p>Still re-checks kill switch, null-sender, and OP gate in case an operator
     * toggled state between the tick authorize and the executor run. The window is
     * sub-second but the re-check is essentially free.
     */
    public static Result reauthorizeSkipRateLimit(ConfigSnapshot snap, ServerPlayer sender, RequestKind kind) {
        UUID uuid = (sender == null) ? null : sender.getUUID();
        boolean isOp = (sender != null) && sender.hasPermissions(2);
        if (KillSwitch.isDisabled()) {
            return new Denied(
                ErrorCode.DISABLED,
                "ForgeBook is temporarily disabled by an operator.",
                Component.translatable("forgebook.command.denied.disabled"));
        }
        if (uuid == null) {
            return new Denied(
                ErrorCode.FORBIDDEN,
                "Only players may invoke ForgeBook.",
                Component.translatable("forgebook.command.denied.not_player"));
        }
        if (snap.opOnly() && !isOp) {
            return new Denied(
                ErrorCode.FORBIDDEN,
                "ForgeBook is OP-only on this server.",
                Component.translatable("forgebook.command.denied.forbidden"));
        }
        return new Allowed();
    }

    /**
     * Test seam + core logic. Package-private so unit tests in {@code com.forgebook.safety}
     * can invoke without constructing a ServerPlayer.
     *
     * Null UUID indicates "no sender" and is treated identically to a null ServerPlayer.
     */
    static Result authorize(ConfigSnapshot snap, UUID uuid, boolean isOp, RequestKind kind,
                            RateLimiter limiter) {
        // 1. Kill switch (cheapest check; beats every other denial)
        if (KillSwitch.isDisabled()) {
            return new Denied(
                ErrorCode.DISABLED,
                "ForgeBook is temporarily disabled by an operator.",
                Component.translatable("forgebook.command.denied.disabled"));
        }

        // 2. Null-sender (defensive — /execute as @e non-player contexts)
        if (uuid == null) {
            return new Denied(
                ErrorCode.FORBIDDEN,
                "Only players may invoke ForgeBook.",
                Component.translatable("forgebook.command.denied.not_player"));
        }

        // 3. OP gate
        if (snap.opOnly() && !isOp) {
            return new Denied(
                ErrorCode.FORBIDDEN,
                "ForgeBook is OP-only on this server.",
                Component.translatable("forgebook.command.denied.forbidden"));
        }

        // 4. Rate limit (OPs bypass per SAFE-02)
        if (!isOp) {
            RateLimiter.Outcome outcome = limiter.tryAcquire(uuid);
            if (outcome instanceof RateLimiter.Limited l) {
                return new Denied(
                    ErrorCode.RATE_LIMITED,
                    "Rate limit reached. Try again in " + l.retryAfterSeconds() + "s.",
                    Component.translatable(
                        "forgebook.command.denied.rate_limited", l.retryAfterSeconds()));
            }
        }

        return new Allowed();
    }
}
