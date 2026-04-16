package com.forgebook.command;

import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.safety.RateLimiter;
import com.forgebook.safety.RateLimiterHolder;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * /forgebook reload — OP-only, atomic config snapshot rebuild (CFG-07, D-15).
 *
 * Per D-15, this is the ONLY reload trigger — ModConfigEvent.Reloading
 * (file-watch) is deliberately NOT wired. Operators opt in explicitly.
 *
 * Plan 02-07 / D-08: reload now also rebuilds the system prompt cache via
 * {@link com.forgebook.ai.SystemPromptBuilder#buildAndCache}. This triggers a
 * fresh CurseForge fetch (CF-01 — operators should be aware of the API call cost
 * on each reload). ConfigHolder.set runs BEFORE buildAndCache so the prompt
 * builder reads the new snapshot.
 *
 * Plan 03-06a / CMD-06: reload ALSO swaps the RateLimiter so a new
 * rate_limit_per_minute takes effect immediately. RateLimiterHolder.swap runs
 * AFTER ConfigHolder.set so the new limiter reflects the just-committed snapshot.
 *
 * The .executes lambda runs on the server tick thread (Brigadier dispatch); the
 * ConfigHolder.set assignment is a single volatile store — concurrent readers
 * on aiExecutor worker threads either see the old or new snapshot with no tearing.
 */
public final class ForgebookReloadCommand {

    private static final Logger LOG = LogManager.getLogger();

    private ForgebookReloadCommand() {}

    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("forgebook")
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))  // OP-only (D-15)
                    .executes(ForgebookReloadCommand::executeReload)));
    }

    /**
     * Reload body extracted for reuse by {@link com.forgebook.command.ForgebookCommands}.
     * Runs on the server tick thread (Brigadier dispatch). Performs three actions in
     * order:
     *   1. ConfigHolder.set(ConfigHolder.buildFromSpec()) — rebuild the snapshot from TOML.
     *   2. SystemPromptBuilder.buildAndCache(server) — re-render cached system prompt.
     *   3. RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute())) — resize limiter.
     *
     * D-14 invariant: the new snapshot is read ONCE via ConfigHolder.get() after set(),
     * passed to the RateLimiter constructor, and never re-read in this method.
     *
     * Pitfall 6 (RESEARCH): swapping the RateLimiter drops all in-flight per-UUID token
     * buckets. This is intentional — a reload is a "fresh slate" event. Benign leak.
     */
    public static int executeReload(CommandContext<CommandSourceStack> ctx) {
        ConfigHolder.set(ConfigHolder.buildFromSpec());
        // D-08: rebuild system prompt AFTER ConfigHolder.set so buildAndCache reads the new snapshot.
        com.forgebook.ai.SystemPromptBuilder.buildAndCache(ctx.getSource().getServer());
        // CMD-06 / Phase 3: resize the rate limiter to match the new config. Single volatile load.
        ConfigSnapshot snap = ConfigHolder.get();
        RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute()));
        ctx.getSource().sendSuccess(
            () -> Component.literal("ForgeBook config + system prompt reloaded."), true);
        LOG.info("ForgeBook config reloaded by {}", ctx.getSource().getTextName());
        return Command.SINGLE_SUCCESS;
    }
}
