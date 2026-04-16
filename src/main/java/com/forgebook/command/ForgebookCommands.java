package com.forgebook.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Brigadier root registrar for /forgebook. Wires the full six-subcommand tree
 * (ask, item, reload, disable, enable, stats). Registered as a forge-bus listener
 * from {@link com.forgebook.ForgeBookMod}.
 *
 * <h2>Pitfall 1: .requires vs runtime authorization</h2>
 * `reload`, `disable`, `enable`, `stats` use `.requires(src -> src.hasPermission(2))`
 * because they're admin-only at the command-structure level — non-OPs don't even
 * see them in tab-completion.
 *
 * `ask` and `item` DO NOT use `.requires(...)` because `op_only` is a runtime config
 * knob (CFG-*). Even when `op_only=true`, the command itself is still syntactically
 * available to all players — the runtime {@link com.forgebook.safety.Authorizer}
 * gate inside the .executes body returns FORBIDDEN when appropriate. This matches
 * the CHAT_UI path (ChatRequestHandler.handleForTest Plan 05), keeping the auth
 * decision in one place per RequestKind.
 *
 * <h2>Explicit bus binding</h2>
 * This class is not {@literal @Mod.EventBusSubscriber}-annotated. {@link com.forgebook.ForgeBookMod}
 * wires it via {@code MinecraftForge.EVENT_BUS.addListener(ForgebookCommands::onRegister)}
 * — the Forge bus dispatches RegisterCommandsEvent, not the mod bus.
 */
public final class ForgebookCommands {

    private static final Logger LOG = LogManager.getLogger();

    private ForgebookCommands() {}

    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("forgebook")
                // ask (runtime-auth via Authorizer — no .requires)
                .then(Commands.literal("ask")
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(AskSubcommand::execute)))
                // item (two branches; runtime-auth via Authorizer)
                .then(Commands.literal("item")
                    .executes(ItemSubcommand::executeHeld)
                    .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                        .executes(ItemSubcommand::executeWithArg)))
                // reload (admin — structural OP gate)
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))
                    .executes(ForgebookReloadCommand::executeReload))
                // disable (admin)
                .then(Commands.literal("disable")
                    .requires(src -> src.hasPermission(2))
                    .executes(AdminSubcommands::executeDisable))
                // enable (admin)
                .then(Commands.literal("enable")
                    .requires(src -> src.hasPermission(2))
                    .executes(AdminSubcommands::executeEnable))
                // stats (admin)
                .then(Commands.literal("stats")
                    .requires(src -> src.hasPermission(2))
                    .executes(AdminSubcommands::executeStats)));

        LOG.info("ForgeBook commands registered: ask, item, reload, disable, enable, stats");
    }
}
