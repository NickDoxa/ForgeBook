package com.forgebook;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ForgeBook mod entry.
 *
 * Responsibilities in Phase 1:
 *   - Register dual ForgeConfigSpec (SERVER + CLIENT) per D-12.
 *   - Wire mod-bus commonSetup (schedules ForgebookNetwork.register on enqueueWork).
 *   - Forge-bus wiring: /forgebook reload (Plan 02), ConfigHolder snapshot seed
 *     on ServerStartingEvent (Plan 02), AiExecutor.start on ServerStartingEvent
 *     and AiExecutor.onServerStopping on ServerStoppingEvent (Plan 03).
 *   - DistExecutor.safeRunWhenOn is the ONLY entry into com.forgebook.client.* (D-10).
 *
 * D-10 (firewall): this file and every file outside com.forgebook.client MUST NOT
 * reference the Minecraft client package. CI enforces via grep lint (see .github/workflows/build.yml).
 */
@Mod(ForgeBookMod.MODID)
public class ForgeBookMod {
    public static final String MODID = "forgebook";
    private static final Logger LOG = LogManager.getLogger();

    public ForgeBookMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // D-12, CFG-01, CFG-02: dual ForgeConfigSpec registration.
        // ForgebookServerConfig.SPEC and ForgebookClientConfig.SPEC are created in Plan 02.
        // Register both here so the mod-constructor wires the lifecycle at class-load.
        ModLoadingContext.get().registerConfig(
            ModConfig.Type.SERVER,
            com.forgebook.config.ForgebookServerConfig.SPEC,
            "forgebook-server.toml");
        ModLoadingContext.get().registerConfig(
            ModConfig.Type.CLIENT,
            com.forgebook.config.ForgebookClientConfig.SPEC,
            "forgebook-client.toml");

        // Mod-bus event wiring.
        modBus.addListener(this::commonSetup);

        // Forge-bus (game lifecycle) wiring.
        MinecraftForge.EVENT_BUS.register(this);

        // Plan 02 wiring: /forgebook reload command + initial snapshot seed on server start.
        // D-15: /forgebook reload is the ONLY reload trigger; ModConfigEvent.Reloading is intentionally NOT wired.
        // ServerStartingEvent seeds ConfigHolder so downstream readers can assume non-null after server start.
        MinecraftForge.EVENT_BUS.addListener(com.forgebook.command.ForgebookReloadCommand::onRegister);
        MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.server.ServerStartingEvent e) ->
                com.forgebook.config.ConfigHolder.set(
                    com.forgebook.config.ConfigHolder.buildFromSpec()));

        // Plan 03 wiring: aiExecutor lifecycle (D-20). Separate ServerStartingEvent
        // listener from the ConfigHolder seeder above — distinct concerns; Forge
        // dispatches multiple listeners on the same event.
        MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.server.ServerStartingEvent e) ->
                com.forgebook.util.AiExecutor.start());
        MinecraftForge.EVENT_BUS.addListener(com.forgebook.util.AiExecutor::onServerStopping);

        // Plan 02-07 / AI-08 / D-08: pre-render system prompt at ServerStartedEvent.
        // ServerStartedEvent fires AFTER AiExecutor.start() (above) so buildAndCache
        // can submit the CurseForge fetch to AiExecutor without "executor not started" failure.
        // Listener pattern matches the ServerStartingEvent listeners above.
        MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.server.ServerStartedEvent e) ->
                com.forgebook.ai.SystemPromptBuilder.buildAndCache(e.getServer()));

        // D-10, SCAF-02, SCAF-04: the ONLY entry into client-dist code.
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
            () -> com.forgebook.client.ClientSetup::init);

        LOG.info("ForgeBook mod constructor complete.");
    }

    private void commonSetup(final FMLCommonSetupEvent e) {
        // NET-01: SimpleChannel registration must run on the mod-loading thread,
        // wrapped in enqueueWork so it serializes with other mods' common setup.
        e.enqueueWork(com.forgebook.network.ForgebookNetwork::register);
        LOG.info("ForgeBook common setup — network channel registered.");
    }
}
