package com.forgebook.command;

import com.forgebook.safety.KillSwitch;
import com.forgebook.safety.StatsAccumulator;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.BiConsumer;

/**
 * OP-gated admin subcommands for {@code /forgebook}: disable, enable, stats (CMD-03 + CMD-04).
 *
 * <p>All three operate on in-memory state and return synchronously on the tick thread —
 * no {@link com.forgebook.util.AiExecutor} hop needed. The {@code .requires(hasPermission(2))}
 * structural gate lives in {@link ForgebookCommands} (Plan 06a); these bodies assume the
 * caller is already OP.
 *
 * <h2>Broadcast semantics</h2>
 * <ul>
 *   <li>{@link #executeDisable} / {@link #executeEnable} use {@code sendSuccess(..., true)}:
 *       broadcasts to all OPs so other admins see the kill-switch change (audit trail on-server
 *       as well as in {@code logs/latest.log}).</li>
 *   <li>{@link #executeStats} uses {@code sendSuccess(..., false)}: the caller explicitly
 *       requested stats; don't spam other OPs' chat with potentially multi-line output.</li>
 * </ul>
 *
 * <h2>Test seam</h2>
 * Each public method unpacks the two things it actually needs from the Minecraft
 * {@link CommandSourceStack} (display name + send callback) and delegates to the
 * package-private {@code *Internal} helpers. Tests drive those helpers directly without
 * constructing a {@link CommandSourceStack}. {@link KillSwitch} is a real static holder
 * (Plan 01); tests reset it in {@code @BeforeEach}/{@code @AfterEach}.
 */
public final class AdminSubcommands {

    private static final Logger LOG = LogManager.getLogger();

    private AdminSubcommands() {}

    // ------------------------------------------------------------------
    // Public entry points (production)
    // ------------------------------------------------------------------

    public static int executeDisable(CommandContext<CommandSourceStack> ctx) {
        return executeDisableInternal(
            ctx.getSource().getTextName(),
            (text, broadcast) -> ctx.getSource().sendSuccess(
                () -> Component.literal(text), broadcast));
    }

    public static int executeEnable(CommandContext<CommandSourceStack> ctx) {
        return executeEnableInternal(
            ctx.getSource().getTextName(),
            (text, broadcast) -> ctx.getSource().sendSuccess(
                () -> Component.literal(text), broadcast));
    }

    public static int executeStats(CommandContext<CommandSourceStack> ctx) {
        return executeStatsInternal(
            (text, broadcast) -> ctx.getSource().sendSuccess(
                () -> Component.literal(text), broadcast));
    }

    // ------------------------------------------------------------------
    // Package-private core + test seam
    // ------------------------------------------------------------------

    /**
     * Flip the kill switch to disabled. Idempotent — emits a different message when
     * already disabled. Always broadcasts (other OPs see the change).
     */
    static int executeDisableInternal(String textName, BiConsumer<String, Boolean> send) {
        boolean wasEnabled = !KillSwitch.isDisabled();
        KillSwitch.setDisabled(true);
        String msg = wasEnabled
            ? "ForgeBook disabled. New requests will return DISABLED."
            : "ForgeBook is already disabled.";
        // sendSuccess(..., true) — broadcast to all OPs.
        send.accept(msg, true);
        LOG.info("ForgeBook disabled by {}", textName);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Flip the kill switch to enabled. Idempotent — emits a different message when
     * already enabled. Always broadcasts.
     */
    static int executeEnableInternal(String textName, BiConsumer<String, Boolean> send) {
        boolean wasDisabled = KillSwitch.isDisabled();
        KillSwitch.setDisabled(false);
        String msg = wasDisabled
            ? "ForgeBook enabled. New requests will be processed."
            : "ForgeBook is already enabled.";
        // sendSuccess(..., true) — broadcast to all OPs.
        send.accept(msg, true);
        LOG.info("ForgeBook enabled by {}", textName);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Send {@link StatsAccumulator#render()} output to the caller only (broadcast=false).
     */
    static int executeStatsInternal(BiConsumer<String, Boolean> send) {
        String rendered = StatsAccumulator.render();
        // sendSuccess(..., false) — caller-only, no broadcast.
        send.accept(rendered, false);
        return Command.SINGLE_SUCCESS;
    }
}
