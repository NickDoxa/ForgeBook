package com.forgebook.client.ui;

import com.forgebook.config.ForgebookClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Injects a 20×20 "F" button into the vanilla InventoryScreen at
 * {@code (leftPos + imageWidth + 4, topPos + 4)}. Click opens {@link ChatScreen}.
 *
 * <p>UI-06 gate: reads {@link ForgebookClientConfig#ENABLE_CHAT_INTERFACE}{@code .get()}
 * on every event fire — NOT cached. Pitfall 6: caching the value into a class
 * constant would freeze the toggle at class-load time; ForgeConfigSpec already
 * caches internally, so re-reading is O(1).
 *
 * <p>Why {@code Bus.FORGE} + {@code Dist.CLIENT}:
 * <ul>
 *   <li>{@code ScreenEvent.Init.Post} extends {@code net.minecraftforge.eventbus.api.Event}
 *       and does NOT implement {@code IModBusEvent} — it is dispatched on the
 *       <em>Forge</em> event bus ({@code MinecraftForge.EVENT_BUS}), not the mod
 *       lifecycle bus. Registering on {@code Bus.MOD} produces
 *       {@code IllegalArgumentException} at mod construction with
 *       "takes an argument that is not a subtype of ... IModBusEvent".</li>
 *   <li>Same bus as {@code SessionLifecycleListener} ({@code ClientPlayerNetworkEvent}) —
 *       both are runtime UI/player events, not lifecycle events.</li>
 *   <li>Explicit {@code bus =} avoids the "event never fires" bug called out in
 *       CLAUDE.md §"What NOT to Use".</li>
 *   <li>{@code value = Dist.CLIENT} ensures the class is never loaded on a
 *       dedicated server — defense-in-depth against future refactor drift.</li>
 * </ul>
 *
 * <p>UI-08 reverse firewall: this class lives under {@code com.forgebook.client.ui}
 * and MUST NOT import {@code com.forgebook.ai.*}, {@code com.forgebook.safety.*},
 * or {@code com.forgebook.config.ApiKey}. It only touches
 * {@code com.forgebook.config.ForgebookClientConfig} (a client-tier config class
 * with no secrets) and its sibling UI classes.
 */
@Mod.EventBusSubscriber(modid = "forgebook", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class InventoryButtonInjector {

    private static final Logger LOG = LogManager.getLogger();

    private InventoryButtonInjector() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        // UI-06 — re-read on every fire so runtime config toggling takes effect.
        if (!ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()) return;

        if (!(event.getScreen() instanceof InventoryScreen inv)) return;

        // Delegate geometry to pure function from plan 04-02 — testable
        // without loading Minecraft.
        InventoryButtonGeometry.Rect r = InventoryButtonGeometry.compute(
            inv.getGuiLeft(), inv.getGuiTop(), inv.getXSize());

        Button btn = Button.builder(
                // Text-glyph label per UI-SPEC §"Inventory Button Injection"
                // — no logo texture ships in Phase 4; REL-01 may add one in
                // Phase 5.
                Component.literal("F"),
                b -> Minecraft.getInstance().setScreen(new ChatScreen(inv)))
            .bounds(r.x(), r.y(), r.w(), r.h())
            .tooltip(Tooltip.create(Component.translatable("forgebook.chat.button.tooltip")))
            .build();

        // event.addListener is Forge 47.x API; Button implements
        // GuiEventListener + Renderable + NarratableEntry so this single call
        // attaches all three.
        event.addListener(btn);
        LOG.debug("[ForgeBook] Inventory button injected at ({}, {}).", r.x(), r.y());
    }
}
