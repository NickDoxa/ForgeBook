package com.forgebook.client.session;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Clears {@link ClientChatSession} whenever the client logs out from a server
 * (single-player world close, dedicated-server disconnect, dimension change
 * crash-recovery — anything that fires
 * {@link ClientPlayerNetworkEvent.LoggingOut}). This is the UI-05 second
 * condition; the first ({@code ChatScreen.onClose}) is handled inside
 * ChatScreen itself in plan 04-05.
 *
 * <p>Auto-registers via {@link Mod.EventBusSubscriber} — no imperative
 * {@code addListener} call needed in {@code ClientSetup.init}. The class is
 * loaded at classpath-scan time inside
 * {@code DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)} in
 * {@code ForgeBookMod}, so a dedicated server never loads this class.
 *
 * <p>CLAUDE.md §"What NOT to Use" is explicit that
 * {@code @Mod.EventBusSubscriber} must specify {@code bus = ...}; we use
 * {@link Mod.EventBusSubscriber.Bus#FORGE} because
 * {@code ClientPlayerNetworkEvent} is a runtime event (not a mod-lifecycle
 * event). {@code value = Dist.CLIENT} ensures the class is loaded only on
 * the logical client.
 *
 * <p>UI-08 reverse-firewall check: this file imports only
 * {@code net.minecraftforge.*} + {@code org.apache.logging.log4j.*} + the
 * sibling {@link ClientChatSession}. No {@code com.forgebook.ai.*}, no
 * {@code com.forgebook.safety.*}, no {@code com.forgebook.config.ApiKey}.
 */
@Mod.EventBusSubscriber(modid = "forgebook", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SessionLifecycleListener {

    private static final Logger LOG = LogManager.getLogger();

    private SessionLifecycleListener() {}

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientChatSession.get().clear();
        LOG.debug("[ForgeBook] ClientChatSession cleared on logout.");
    }
}
