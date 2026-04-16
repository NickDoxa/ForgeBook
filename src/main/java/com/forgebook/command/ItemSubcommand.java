package com.forgebook.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;

/**
 * Stub — Plan 06a wires the Brigadier tree; Plan 06b replaces these bodies
 * with the authorize → AiExecutor.submit → RagItemPipeline dispatch logic.
 * Method signatures are frozen here so ForgebookCommands::executeHeld /
 * ::executeWithArg method references compile.
 */
public final class ItemSubcommand {
    private ItemSubcommand() {}

    public static int executeHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        throw new UnsupportedOperationException("Plan 06b pending");
    }

    public static int executeWithArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        throw new UnsupportedOperationException("Plan 06b pending");
    }
}
