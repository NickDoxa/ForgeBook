package com.forgebook.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client-only initialization. Invoked ONLY via
 * DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init) from ForgeBookMod.
 *
 * This package (com.forgebook.client) is the ONLY package in the mod allowed to
 * import net.minecraft.client.* per D-10 / SCAF-02. CI enforces via grep lint.
 *
 * Phase 4 responsibilities:
 *   - InventoryButtonInjector and SessionLifecycleListener auto-register via
 *     @Mod.EventBusSubscriber at class load — class loading happens inside the
 *     DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init) block,
 *     so the classes are never loaded on a dedicated server.
 *   - This method installs the packet sinks so server->client replies land in
 *     ClientChatSession without com.forgebook.network.packet.* ever importing
 *     com.forgebook.client.* (SCAF-02 + UI-08 defense-in-depth).
 */
public final class ClientSetup {
    private static final Logger LOG = LogManager.getLogger();

    private ClientSetup() {}

    public static void init() {
        // @Mod.EventBusSubscriber on InventoryButtonInjector (Bus.MOD) and
        // SessionLifecycleListener (Bus.FORGE) auto-registers them at class
        // load. Class loading is triggered by this method being invoked
        // inside DistExecutor.safeRunWhenOn(Dist.CLIENT, ...) from
        // ForgeBookMod. Nothing imperative is needed here for those classes.

        // Wire the packet sinks so server->client replies land in
        // ClientChatSession without com.forgebook.network.packet.* importing
        // com.forgebook.client.* (SCAF-02 + UI-08 defense-in-depth).
        com.forgebook.network.client.ClientPacketSinks.replySink =
            (id, text) -> com.forgebook.client.session.ClientChatSession.get().append(id, text);
        com.forgebook.network.client.ClientPacketSinks.errorSink =
            (id, code, msg) -> com.forgebook.client.session.ClientChatSession.get().appendError(id, code, msg);

        LOG.info("[ForgeBook] Client initialized (Phase 4 UI sinks installed).");
    }
}
