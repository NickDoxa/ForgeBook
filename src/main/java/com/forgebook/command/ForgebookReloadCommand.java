package com.forgebook.command;

import com.forgebook.config.ConfigHolder;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * /forgebook reload — OP-only, atomic config snapshot rebuild (CFG-07, D-15).
 *
 * Per D-15, this is the ONLY reload trigger in Phase 1 — ModConfigEvent.Reloading
 * (file-watch) is deliberately NOT wired. Operators opt in explicitly.
 *
 * The .executes lambda runs on the server tick thread (Brigadier dispatch); the
 * ConfigHolder.set assignment is a single volatile store — concurrent readers
 * on aiExecutor worker threads (Phase 2+) either see the old or new snapshot
 * with no tearing.
 */
public final class ForgebookReloadCommand {

    private static final Logger LOG = LogManager.getLogger();

    private ForgebookReloadCommand() {}

    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("forgebook")
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))  // OP-only (D-15)
                    .executes(ctx -> {
                        ConfigHolder.set(ConfigHolder.buildFromSpec());
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("ForgeBook config reloaded."), true);
                        LOG.info("ForgeBook config reloaded by {}", ctx.getSource().getTextName());
                        return Command.SINGLE_SUCCESS;
                    })));
    }
}
