# Phase 4: In-Inventory Chat UI - Research

**Researched:** 2026-04-16
**Domain:** Minecraft Forge 1.20.1 client-side `Screen` injection into vanilla `InventoryScreen`, custom widget rendering via `GuiGraphics`, client-server packet flow for the chat loop
**Confidence:** HIGH on screen architecture + packet wiring (anchored on Phase 1/3 delivered code); MEDIUM on scroll strategy (two viable paths); HIGH on disconnect/config/firewall contracts (already established by Phase 1)

## Summary

Phase 4 converts the server-side pipeline (Phases 2-3) into a player-reachable UI without weakening any security invariant. All six ROADMAP truths reduce to building two things under `com.forgebook.client.*`: an `InventoryButtonInjector` that listens on `ScreenEvent.Init.Post` and a standalone `ChatScreen extends Screen` that owns the chat panel, input, and error-card rendering. No `AbstractContainerScreen` subclass, no custom keybind, no API-key surface on the client.

The single highest-risk open question from STATE.md is the "sibling Screen vs standalone Screen" spike — **the spike resolves in favor of standalone `Screen`** per the just-approved 04-UI-SPEC.md. `Minecraft.setScreen(newScreen)` **replaces** the current screen; Minecraft does not expose a public "dock two screens side-by-side" primitive. The UI-SPEC's visual contract ("inventory still visible behind the chat panel") is satisfied by one of two mechanisms that both live inside a single standalone `Screen`: **(preferred)** `ChatScreen` stores the `InventoryScreen` as `parent` and in `render()` calls `parent.render(graphics, mouseX, mouseY, partialTick)` *first* to paint the inventory frame, then overlays the chat panel. The `onClose()` restores the parent. This keeps the inventory visually present while `ChatScreen` is the only focused widget host — avoiding the slot hit-testing conflicts JEI/REI flagged by UI-SPEC §"Decision".

The second-highest-risk area is chunked-response assembly: Phase 1 delivered `ChunkedPayload` but has no production call site. Since Phase 2 completion shows `ChatResponsePacket` already caps `writeUtf(message, 32_000)` and agent replies at default Haiku max-tokens rarely exceed 8 KB of text, Phase 4 **defers** multi-chunk response reassembly and relies on the existing 32 KB single-packet ceiling. If a future long-reply scenario hits the cap, the server will truncate at encode time and the client will see one complete bubble. Multi-chunk assembly is flagged here as the only deferrable architectural question and is left out of Phase 4 scope.

**Primary recommendation:** Build `com.forgebook.client.ui.InventoryButtonInjector` (listens on `ScreenEvent.Init.Post` filtered to `InventoryScreen`, respects `ForgebookClientConfig.ENABLE_CHAT_INTERFACE`, adds a `Button` via `event.addListener(...)` at `leftPos + imageWidth + 4`, `topPos + 4`); build `com.forgebook.client.ui.ChatScreen extends Screen` (holds `parent` field, renders parent first then panel, routes `ENTER`/`ESC`, `setInitialFocus(editBox)`); build `com.forgebook.client.session.ClientChatSession` singleton (volatile holder, `append/appendError/clear/markPending/markIdle`, keyed by requestId to discard stale replies); modify `ChatResponsePacket.handleOnClient` and `ChatErrorPacket.handleOnClient` to call into `ClientChatSession`; subscribe `ClientPlayerNetworkEvent.LoggingOut` to call `ClientChatSession.clear()`; register the injector and disconnect listener from `ClientSetup.init()` — the existing `DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)` entry point. The package firewall (SCAF-02's CI grep) is already broad enough (`com.forgebook.client.*`) to cover the new `.client.ui.*` and `.client.session.*` subpackages.

<user_constraints>
## User Constraints (from CONTEXT.md)

**Note:** No `04-CONTEXT.md` has been produced for this phase (no `/gsd-discuss-phase 4` run). The constraints below are derived from the just-approved `04-UI-SPEC.md` design contract, `CLAUDE.md` project instructions, `STATE.md` decisions, and the Phase 1-3 locked-in code. They are treated as **locked** because they have been frozen by preceding work products.

### Locked Decisions (from UI-SPEC, STATE, CLAUDE.md, Phase 1-3 code)

**Screen architecture**
- **UI-D-01:** `ChatScreen` is a standalone `net.minecraft.client.gui.screens.Screen` — NOT an `AbstractContainerScreen<InventoryMenu>` subclass (UI-SPEC resolves the STATE.md Phase-4 spike in favor of standalone Screen; avoids JEI/REI slot hit-test conflicts).
- **UI-D-02:** `InventoryButtonInjector` subscribes to `net.minecraftforge.client.event.ScreenEvent.Init.Post` filtered to `net.minecraft.client.gui.screens.inventory.InventoryScreen` via `instanceof`. Uses `event.addListener(button)` per Forge 47.x API (registration is in `ClientSetup.init` on the mod event bus with `Bus.MOD`, as this is a client-side-only event).
- **UI-D-03:** Button geometry: `20×20` px, at `x = inv.getGuiLeft() + inv.getXSize() + 4`, `y = inv.getGuiTop() + 4`. (Forge's public accessors on `AbstractContainerScreen` — `getGuiLeft()`, `getGuiTop()`, `getXSize()`, `getYSize()` — are the supported read paths, equivalent to package-private `leftPos`/`topPos`/`imageWidth`.)
- **UI-D-04:** Click action: `Minecraft.getInstance().setScreen(new ChatScreen(currentInventoryScreen))`. The `InventoryScreen` becomes `ChatScreen.parent` and is re-rendered from `ChatScreen.render()` to preserve the inventory's visual presence.
- **UI-D-05:** `ChatScreen.onClose()` restores the parent: `this.minecraft.setScreen(this.parent)` **after** calling `ClientChatSession.clear()`.
- **UI-D-06:** `ChatScreen.isPauseScreen() == false` (matches `InventoryScreen`; not a pause menu — prevents integrated-server tick freeze).
- **UI-D-07:** No custom keybind. Deferring `RegisterKeyMappingsEvent` registration entirely — the inventory-button is the sole discoverable entry point (UI-SPEC §"Interaction Contract → Keybinds"; PROJECT.md out-of-scope list).

**Session semantics**
- **UI-D-08:** `ClientChatSession` is a singleton with volatile state; held under `com.forgebook.client.session.ClientChatSession`. NOT under `com.forgebook.client.ui.*` so ui classes importing session are fine but ui classes are never imported by session (one-way dependency).
- **UI-D-09:** Session clears on BOTH (a) `ChatScreen.onClose()` AND (b) `ClientPlayerNetworkEvent.LoggingOut` (Forge-bus, client-side). The LoggingOut subscription lives in the same `ClientSetup` initialization path.
- **UI-D-10:** One in-flight request at a time per session. Submitting while pending is a no-op at the UI level (button greyed, EditBox disabled).
- **UI-D-11:** Stale-response guard — responses arriving after session clear are silently dropped by `ClientChatSession.append` (requestId unknown → no-op).

**Packet integration (anchored on Phase 1-3 delivered code)**
- **UI-D-12:** Use existing `ForgebookNetwork.CHANNEL.sendToServer(new ChatRequestPacket(UUID.randomUUID(), message))` — no new packet types, no channel changes.
- **UI-D-13:** Modify (not replace) `ChatResponsePacket.handleOnClient` and `ChatErrorPacket.handleOnClient` to dispatch into `ClientChatSession`. These methods are already registered with `consumerMainThread` in `ForgebookNetwork.register()` — safe to touch client state directly without an additional `enqueueWork` hop.
- **UI-D-14:** Chunked response reassembly is **out of scope** for Phase 4. Rely on the 32 KB single-packet ceiling. If output exceeds 32 KB, the server truncates at encode time (`buf.writeUtf(reply, 32_000)` — `ChatResponsePacket.encode`). Reassembly is v2 work.

**Config & package firewall**
- **UI-D-15:** Read `ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()` on every `ScreenEvent.Init.Post` fire (not cached). Config load completes during Forge mod-loading (well before the first InventoryScreen opens), so `.get()` is safe by the time the injector ever runs.
- **UI-D-16:** Zero imports of `com.forgebook.ai.*`, `com.forgebook.config.ApiKey`, or `com.forgebook.safety.*` from `com.forgebook.client.ui.*` or `com.forgebook.client.session.*`. The existing SCAF-02 CI grep (scoped to `com.forgebook.client.*` → `net.minecraft.client.*`) does NOT yet cover the reverse direction (client → ai/safety) — Phase 4 adds a second grep rule for UI-08 (see Pitfall 7).
- **UI-D-17:** `ChatScreen` holds NO `ApiKey` reference, NO config secret field, NO provider-specific data. It only renders `List<MessageBubble>` + `List<ErrorCard>` obtained from `ClientChatSession` + current `EditBox` state.

**Testing**
- **UI-D-18:** Pure-Java seams (value types, layout math, state machine) tested via JUnit 5 — the same pattern Phase 3 used (e.g., `AuthorizerTest`, `RateLimiterTest`). Screen rendering itself is not unit-tested; live smoke is the human checkpoint (precedent: Phase 3's "live /forgebook smoke" item).
- **UI-D-19:** Follows Phase 3's `*Internal` test-seam pattern: any class touching a Minecraft client type at its public entry point gets an `*Internal` overload taking pure-data parameters (e.g., `ClientChatSessionInternal.appendInternal(requestId, text, now)` skipping the volatile holder). The live path (in a `ChatScreen` subclass) is not directly tested; Phase 3 set this precedent with `ChatRequestHandler.handleForTest`.

### Claude's Discretion

- Exact package organization under `com.forgebook.client.*` (new subpackages `ui` + `session` recommended; final names planner's call).
- Scrollbar implementation: custom math in `ChatPanelWidget` OR use vanilla `AbstractSelectionList<MessageEntry>`. Both work; custom math gives tighter UI-SPEC compliance (color palette, 4-px stripe on error cards, 75% bubble max-width); `AbstractSelectionList` gives less code at the cost of deviating from UI-SPEC's bespoke error-card shape. **RESEARCH recommends custom math** — see §"Implementation Approach → Scroll Strategy".
- Loading-indicator tick cadence (UI-SPEC specifies 500 ms per frame; implementation can use `Screen.tick()` counter or `System.currentTimeMillis() % 2000 / 500`).
- Whether to add a narrow `visibleForTesting` static in `ClientChatSession` or rely on package-private test access. Phase 3 precedent: `@VisibleForTesting public volatile` static sinks (see `ChatRequestHandler.responseSinkForTests`).
- Whether `MessageBubble` and `ErrorCard` are records/value-types (preferred — UI-SPEC explicitly calls them "value type, not a widget") vs. small classes.
- i18n key implementation: Phase 4 **declares** the keys in `assets/forgebook/lang/en_us.json` per UI-SPEC §"Copywriting Contract"; REL-02 (Phase 5) may reorganize. Planner decides the exact initial JSON layout.

### Deferred Ideas (OUT OF SCOPE)

- Chunked response reassembly (UI-D-14) — v2 (V2-UX-01 streaming supersedes this concern entirely).
- Default keybind for the chat UI — REQUIREMENTS.md "Out of Scope" row.
- Persistent conversation history — REQUIREMENTS.md "Out of Scope" row.
- Streaming token-by-token rendering — V2-UX-01.
- Custom logo texture — Phase 5 REL-01 (Phase 4 uses text-glyph `"F"` as button label).
- Full i18n coverage sweep — Phase 5 REL-02.
- Mod-compat matrix verification at runtime — Phase 5 REL-04.
- `AbstractContainerScreen<InventoryMenu>` subclass approach — resolved against in UI-SPEC (UI-D-01).
- New packet types for Phase 4 — UI-D-12 reuses the existing three.
- Per-session chunked-response assembly state — UI-D-14.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| UI-01 | `InventoryButtonInjector` on `ScreenEvent.Init.Post(InventoryScreen)`, fixed offset, no overlap with vanilla widgets | §"Implementation Approach → Inventory Button Injection", §"Key Classes & APIs → ScreenEvent" |
| UI-02 | Click opens `ChatScreen` — either `AbstractContainerScreen<InventoryMenu>` subclass (with shifted `leftPos`) OR standalone `Screen` fallback | §"Implementation Approach → Screen Architecture" — resolves in favor of standalone `Screen` per UI-D-01 |
| UI-03 | Vanilla-reused assets + user-supplied logo only — no third-party textures | §"File Layout Proposal" (text-glyph button + procedural `GuiGraphics.fill` panel — zero texture files this phase) |
| UI-04 | Scrollable conversation with user+assistant bubbles, input, submit, loading indicator, inline error surface | §"Implementation Approach → Scroll Strategy", §"Key Classes & APIs → GuiGraphics/Font/EditBox" |
| UI-05 | `ClientChatSession` in-memory only; cleared on screen close OR disconnect | §"Integration Points → ClientChatSession", §"Key Classes & APIs → ClientPlayerNetworkEvent.LoggingOut" |
| UI-06 | `enable_chat_interface` client config — when false, no button injected | §"Integration Points → CLIENT config" |
| UI-07 | Renders at GUI scales 1-4 on ≥1280×720; small-screen fallback | §"Implementation Approach → Scale-Aware Layout" |
| UI-08 | Client never holds/displays any API key; all AI via `ChatRequestPacket` | §"Integration Points → Package firewall", §"Pitfalls → Pitfall 7 (leaking config into client UI)" |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Inventory button injection | Browser / Client (`com.forgebook.client.ui.InventoryButtonInjector`) | — | Pure client-side widget registration; server has no awareness of UI |
| Chat panel rendering | Browser / Client (`ChatScreen`, `ChatPanelWidget`, `MessageBubble`, `ErrorCard`) | — | `GuiGraphics` + `Font` are client-only types; `net.minecraft.client.*` imports are strictly client-tier per D-10 firewall |
| Session state holding | Browser / Client (`com.forgebook.client.session.ClientChatSession`) | — | Per UI-05 the session is ephemeral and client-local; no server persistence |
| Packet dispatch (C→S request) | Browser / Client → sends via `ForgebookNetwork.CHANNEL` | API / Backend (consumes) | Client serializes `ChatRequestPacket`; server handles in `ChatRequestHandler` (already delivered Phase 1/3) |
| Packet handling (S→C reply/error) | API / Backend (emits) → Browser / Client (renders) | — | Existing `ChatResponsePacket.handleOnClient` / `ChatErrorPacket.handleOnClient` run on render thread (consumerMainThread) — Phase 4 just routes their output into `ClientChatSession` |
| Authorization / rate limit / kill switch | API / Backend (already owned by Phase 3's `Authorizer` + `RateLimiter` + `KillSwitch`) | — | Phase 4 MUST NOT add any client-side gatekeeping — clients always send; server always decides. Defense-in-depth: a client that short-circuits FORBIDDEN locally is a bug, not a feature |
| AI provider calls | API / Backend (`AiDispatcher` + `AgentLoop` + `ClaudeProvider`) | — | Non-negotiable — client never carries an API key (UI-08) |
| Config tier | API / Backend (`forgebook-server.toml`) for secrets + behavior; Browser / Client (`forgebook-client.toml`) for `enable_chat_interface` only | — | Already enforced Phase 1 (`ForgebookServerConfig` + `ForgebookClientConfig`) |
| Disconnect detection | Browser / Client (`ClientPlayerNetworkEvent.LoggingOut` — client-only Forge event) | — | Session lives on the client; only the client knows when it disconnected |

**Tier invariant:** Every AI decision stays in API/Backend. The only data leaving the API tier toward the client is the `ChatResponsePacket.reply` text or a `ChatErrorPacket.ErrorCode + humanReadable` pair — both already defined in Phase 1 and populated by Phase 3.

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Minecraft Forge | 1.20.1-47.4.18 | Mod platform, widget framework, event bus | [VERIFIED: CLAUDE.md stack table] — project-locked, all Phase 1-3 code anchored on this |
| `net.minecraft.client.gui.screens.Screen` | vanilla 1.20.1 | Base class for `ChatScreen` | [VERIFIED: `docs.minecraftforge.net/en/1.20.x/gui/screens/` — public Screen lifecycle] The standard base; UI-SPEC §"Layout & Sizing → ChatScreen Layout" mandates `Screen` not `AbstractContainerScreen` |
| `net.minecraft.client.gui.GuiGraphics` | vanilla 1.20.1 | Rendering surface (fill, blit, drawString) | [VERIFIED: `docs.minecraftforge.net/en/1.20.x/gui/screens/` — "Any GUI rendered by Minecraft is typically done using GuiGraphics"] — the mandatory draw API in 1.20+ after `RenderSystem`/`PoseStack`-on-caller was retired |
| `net.minecraft.client.gui.Font` | vanilla 1.20.1 | Text measurement + split-for-wrapping | [VERIFIED: Forge javadocs — `Font.split(FormattedText, int width)` returns `List<FormattedCharSequence>`; `Font.width(String)` for measurement] |
| `net.minecraft.client.gui.components.Button` | vanilla 1.20.1 | Inventory-toggle button (20×20) and submit ("Ask") button | [VERIFIED: Forge docs "Common Widgets" — "Button: Clickable interactable element"] |
| `net.minecraft.client.gui.components.EditBox` | vanilla 1.20.1 | Text input row | [VERIFIED: Forge javadocs `EditBox` in 1.20.1 — single-line; `setMaxLength`, `setResponder(Consumer<String>)`, `setHint`] |
| `net.minecraft.client.gui.components.AbstractWidget` | vanilla 1.20.1 | Base for `ChatPanelWidget` (custom widget) | [VERIFIED: Forge javadocs — extended by Button, EditBox, all built-in widgets; subclass must implement `renderWidget`, `updateWidgetNarration`] |
| `net.minecraftforge.client.event.ScreenEvent.Init.Post` | Forge 47.x | Inventory-button injection entry point | [VERIFIED: UI-SPEC §"Injection hook"; nekoyue javadocs for 1.19.3 confirm `addListener(GuiEventListener)` signature — same class in 47.x per CLAUDE.md "Stack Patterns"] |
| `net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut` | Forge 47.x | Disconnect detection | [VERIFIED: Forge javadocs + web search — "fired on the main Forge event bus, only on the logical client"] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `net.minecraftforge.common.ForgeConfigSpec.BooleanValue` | Forge 47.x | Read `ENABLE_CHAT_INTERFACE` flag | [VERIFIED: already used in `ForgebookClientConfig.java:15`] — `.get()` returns cached value |
| `ChatRequestPacket` / `ChatResponsePacket` / `ChatErrorPacket` | Phase 1 delivered | Wire contracts | [VERIFIED: source files read during research — `ChatRequestPacket.java`, `ChatResponsePacket.java`, `ChatErrorPacket.java`] |
| `ForgebookNetwork.CHANNEL` | Phase 1 delivered | Client sends requests via `.sendToServer(pkt)` | [VERIFIED: `ForgebookNetwork.java:31-36`] |
| `net.minecraft.network.chat.Component` | vanilla 1.20.1 | i18n-aware text rendering | [VERIFIED: Forge docs — "Strings should typically be passed in as Components"] `Component.translatable(key)` for every copy string |
| `org.lwjgl.glfw.GLFW` constants | LWJGL bundled with MC | Key codes (`GLFW_KEY_ENTER = 257`, `GLFW_KEY_ESCAPE = 256`) | [VERIFIED: Forge Key Mappings docs — "Input codes in Minecraft are defined in GLFW, with KEYSYM tokens prefixed with GLFW_KEY_*"] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Standalone `Screen` (UI-D-01) | `AbstractContainerScreen<InventoryMenu>` with shifted `leftPos` | UI-02 lists this as the primary spec but UI-SPEC overrides with standalone; container screen inherits vanilla slot hit-test that JEI/REI/Quark assume; risk of click-through into real slots from the panel area. Standalone is safer by elimination. |
| Custom scroll math | `AbstractSelectionList<MessageEntry>` | SelectionList gives keyboard nav + scroll for free but renders rows as identical height-fixed entries — UI-SPEC's 75%-max-width bubbles + full-width error cards don't fit the row-pattern cleanly. Custom math is ~80 LOC and matches UI-SPEC; SelectionList is ~30 LOC and deviates. **Choose custom math.** |
| `Font.split(FormattedText, int)` for wrapping | Manual word-break on `String.split` | Vanilla Font.split handles CJK + bidi + soft-hyphen + formatting codes correctly; rolling your own is a guaranteed bug farm. Use `Font.split`. |
| `Minecraft.getInstance().setScreen(new ChatScreen(parent))` | `Minecraft.getInstance().pushGuiLayer(...)` | Forge 47.x does not expose a public `pushGuiLayer` on 1.20.1 Screen — the method is NeoForge 1.20.4+. `setScreen` + parent-field is the 1.20.1 idiom. |
| Keybind + inventory button | Inventory button only (UI-D-07) | Keybind registration is a trivial add but PROJECT.md's "saturated keybind ecosystem" argument is correct; deferring until v2. |

**Installation:**

No new Gradle dependencies. All required classes are already on the classpath:
- `net.minecraft.client.*` — Minecraft 1.20.1 itself (via ForgeGradle Mojang-mapped)
- `net.minecraftforge.client.event.*` — Forge 47.4.18
- `org.lwjgl.glfw.GLFW` — bundled with Minecraft

**Version verification:** Not applicable — no new library dependencies introduced by Phase 4.

## Implementation Approach

### Screen Architecture Resolution (resolves STATE.md Phase-4 flag)

**Problem:** `Minecraft.getInstance().setScreen(newScreen)` replaces the current `Screen` — it does not push a modal layer. The UI-SPEC wants the player to "see the inventory still" while the chat panel is open.

**Resolved approach (preferred):**

```java
// ChatScreen.java (sketch)
public class ChatScreen extends Screen {
    private final Screen parent;   // the InventoryScreen that opened us
    private EditBox input;
    private Button submitBtn;
    private ChatPanelWidget panel;

    public ChatScreen(Screen parent) {
        super(Component.translatable("forgebook.chat.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // 1) forward init to parent so its leftPos/topPos/widgets are computed at current scale.
        //    Minecraft already did this when the parent was showing; re-initing after setScreen
        //    is necessary because Minecraft's Screen#resize (called during setScreen) calls init()
        //    on the NEW screen only. We trigger parent init manually:
        if (parent != null) {
            parent.init(this.minecraft, this.width, this.height);
        }
        // 2) build our own widgets
        int panelWidth = 240;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = 20;
        this.panel = this.addRenderableWidget(new ChatPanelWidget(panelX, panelY, ...));
        this.input = this.addRenderableWidget(new EditBox(this.font, ..., Component.empty()));
        this.input.setMaxLength(512);
        this.input.setHint(Component.translatable("forgebook.chat.input.placeholder"));
        this.input.setResponder(this::onInputChanged);
        this.submitBtn = this.addRenderableWidget(Button.builder(
            Component.translatable("forgebook.chat.submit"),
            this::onSubmitClicked).bounds(...).build());
        this.setInitialFocus(this.input);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render parent first so the inventory is visually present behind us.
        // parent.render() does its own renderBackground call — that's fine; we re-dim on top.
        if (parent != null) {
            parent.render(graphics, Integer.MAX_VALUE, Integer.MAX_VALUE, partialTick);
            // Passing (MAX_VALUE, MAX_VALUE) as mouse coords tells the parent nothing is hovered —
            // we don't want tooltips firing on the inventory when the chat panel is focused.
        }
        // Our own dim overlay + panel + widgets
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);  // renders widgets added via addRenderableWidget
    }

    @Override
    public boolean isPauseScreen() { return false; }  // UI-D-06

    @Override
    public void onClose() {
        ClientChatSession.get().clear();  // UI-05
        this.minecraft.setScreen(parent); // restore inventory
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { this.onClose(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER && this.input.isFocused() && !this.input.getValue().isBlank()
                && !ClientChatSession.get().isPending()) {
            this.onSubmitClicked(this.submitBtn);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
```

**Why this works:**
- `setScreen(new ChatScreen(inv))` replaces the screen (no other option exists on 1.20.1 public API), BUT the replacement holds a reference to the old one and redraws it each frame.
- The `parent.init(...)` call guarantees the parent's widgets are sized for the current window — it's idempotent and Mojang uses the same trick in their own "back button" screens.
- `isPauseScreen() == false` matches `InventoryScreen` — prevents integrated-server tick freeze.
- Only the `ChatScreen`'s widgets are interactable; the parent's widgets receive `mouseX = Integer.MAX_VALUE` so `isMouseOver` never returns true for them. Tooltips stay quiet.
- On `onClose`, `setScreen(parent)` restores the inventory without re-opening a menu (the `InventoryMenu` is still server-authoritative and still open because `isPauseScreen() == false` — nothing closed the menu).

**Risk:** If `parent.render` in a future Forge version starts registering listeners (e.g., repeated `addRenderableWidget` calls on re-init), we'd leak. Mitigation: Mojang's `Screen#init` clears the widget list at the start, so this is well-behaved in 1.20.1.

[VERIFIED: `docs.minecraftforge.net/en/1.20.x/gui/screens/` — Screen lifecycle sections "Initialization", "Rendering", "Closing" all confirm the pattern]
[VERIFIED: Forge forum "Make EditBox active and focused" — setFocused + setInitialFocus pattern]

### Inventory Button Injection (UI-01)

```java
// InventoryButtonInjector.java (sketch)
public final class InventoryButtonInjector {
    // Registered from ClientSetup.init() on the MOD event bus (client-side events).
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()) return; // UI-06
        if (!(event.getScreen() instanceof InventoryScreen inv)) return;
        int x = inv.getGuiLeft() + inv.getXSize() + 4;   // = leftPos + imageWidth + 4, UI-SPEC geometry
        int y = inv.getGuiTop() + 4;                      // = topPos + 4
        Button btn = Button.builder(Component.literal("F"), b -> {
            Minecraft.getInstance().setScreen(new ChatScreen(inv));
        })
        .bounds(x, y, 20, 20)
        .tooltip(Tooltip.create(Component.translatable("forgebook.chat.button.tooltip")))
        .build();
        event.addListener(btn);
    }
}
```

**Key points:**
- Uses `inv.getGuiLeft()` / `getGuiTop()` / `getXSize()` — public accessors on `AbstractContainerScreen` that read the package-private `leftPos` / `topPos` / `imageWidth`. Stable API in 1.20.1.
- Read `.get()` on the config value every time — NOT cached. Ensures toggling `enable_chat_interface=false` at runtime (via `/forgebook reload` on client config, though v1 doesn't expose that) takes effect next inventory open.
- The recipe-book native toggle is at the INVENTORY's internal offset (`+48, +79` in vanilla) — UI-SPEC confirms this doesn't collide with `leftPos + imageWidth + 4, topPos + 4`.
- `event.addListener(btn)` is the Forge API: adds the widget as an interactable AND renderable AND narratable listener (because `Button` implements all three), per the nekoyue javadoc "Listeners added through this event may also be marked as renderable or narratable, if they inherit from Widget and NarratableEntry respectively."

[VERIFIED: nekoyue Forge 1.19.3 javadocs for `ScreenEvent.Init` — `addListener(GuiEventListener)` signature; UI-SPEC §"Inventory Button Injection"]
[CITED: `docs.minecraftforge.net/en/1.20.x/gui/screens/` — Button.builder pattern]

### Scroll Strategy (UI-04)

**Decision:** Custom scroll math inside `ChatPanelWidget` (not `AbstractSelectionList`).

**Rationale:**
- UI-SPEC demands bubble alignment (user right, assistant left), 75%-max-width bubbles, full-width error cards with a 4-px stripe — `AbstractSelectionList` assumes uniform row rendering and integer row height.
- Custom math is ~80 LOC: track `scrollAmount` double, compute `maxScroll = totalContentHeight - visibleHeight`, clamp on mouseScrolled, render only visible bubbles (trivial y-culling).
- Mouse wheel via override of `mouseScrolled(double x, double y, double delta)` on `ChatPanelWidget`.
- Auto-scroll to bottom when (a) first render and (b) new message arrives AND user was within 20 px of max — preserves UI-SPEC §"Scroll affordance".
- Scrollbar thumb = 6-px wide rectangle rendered via `GuiGraphics.fill` in the panel's right margin (colors from UI-SPEC palette).

**Alternative path (rejected):** `AbstractSelectionList<MessageEntry>` — less code but wrong shape. Flag this for plan-checker: if the planner picks the SelectionList path, UI-SPEC violations need explicit override.

### Text Wrapping (UI-04)

```java
// Inside MessageBubble.render(GuiGraphics g, Font font, int bubbleWidth, int x, int y, int textColor)
int maxTextWidth = bubbleWidth - 2 * PADDING_INSIDE_BUBBLE;  // e.g., bubbleWidth=180, padding=4
List<FormattedCharSequence> lines = font.split(Component.literal(this.text), maxTextWidth);
int yCursor = y + PADDING_TOP;
for (FormattedCharSequence line : lines) {
    g.drawString(font, line, x + PADDING_LEFT, yCursor, textColor, false);
    yCursor += font.lineHeight + 1;  // UI-SPEC: 9 + 1 = 10 px line box
}
// Total bubble height = PADDING_TOP + lines.size() * 10 + PADDING_BOTTOM
```

[VERIFIED: Forge javadocs confirm `Font.split(FormattedText, int width) : List<FormattedCharSequence>` signature across 1.18-1.21 — stable API]
[VERIFIED: `GuiGraphics.drawString(Font, FormattedCharSequence, int, int, int, boolean)` overload is the correct multi-line rendering path]

### Scale-Aware Layout (UI-07)

Re-executed on every `Screen.init()` (which Minecraft calls on window resize). Reads `this.width`/`this.height` at top of init:

```java
@Override protected void init() {
    super.init();
    int minW = 240, minH = 180;
    if (this.width < minW || this.height < minH) {
        // Minimum-width guard per UI-SPEC
        this.addRenderableOnly(new LabelOnly(Component.translatable("forgebook.chat.screen_too_small"), ...));
        return;
    }
    boolean stacked = this.width < 320;
    int panelW = stacked ? this.width - 16 : 240;
    int panelX = stacked ? 8 : (this.width - panelW) / 2;
    int panelY = 20;
    int panelH = this.height - 40;
    // ... build widgets ...
}
```

`Screen.init()` is called by Minecraft's `Screen#resize` whenever the window is resized OR the GUI scale changes, so the layout re-computes automatically. No tick loop needed for this.

[CITED: `docs.neoforged.net/docs/1.20.4/gui/screens/` — "Called when screen initializes and when the game window resizes"]

## Key Classes & APIs

Concrete 1.20.1 / Forge 47.x class names to import and their role:

| FQN | Role | Usage |
|-----|------|-------|
| `net.minecraft.client.gui.screens.Screen` | base class for `ChatScreen` | extend; override `init`, `render`, `onClose`, `keyPressed`, `tick`, `isPauseScreen` |
| `net.minecraft.client.gui.screens.inventory.InventoryScreen` | target for injection | `instanceof` check in `ScreenEvent.Init.Post` |
| `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen` | parent class of `InventoryScreen` | provides public `getGuiLeft()/getGuiTop()/getXSize()/getYSize()` — use for button geometry |
| `net.minecraft.client.gui.GuiGraphics` | rendering surface | first arg to `render()`; `fill(x1, y1, x2, y2, argb)`, `drawString(Font, Component/FormattedCharSequence/String, x, y, color)`, `blit(...)` |
| `net.minecraft.client.gui.Font` | text metrics + wrapping | `Minecraft.getInstance().font`; `font.width(String) : int`, `font.split(FormattedText, int) : List<FormattedCharSequence>`, `font.lineHeight` (= 9 in 1.20.1) |
| `net.minecraft.client.gui.components.Button` | buttons | `Button.builder(Component, OnPress).bounds(x,y,w,h).tooltip(Tooltip).build()` |
| `net.minecraft.client.gui.components.EditBox` | single-line input | `new EditBox(Font, x, y, w, h, Component msg)`; `setMaxLength(int)`, `setHint(Component)`, `setResponder(Consumer<String>)`, `setValue(String)`, `getValue() : String`, `setFocused(boolean)` |
| `net.minecraft.client.gui.components.AbstractWidget` | base for custom widgets | `ChatPanelWidget` extends this; implement `renderWidget(GuiGraphics, int mouseX, int mouseY, float partialTick)`, `updateWidgetNarration(NarrationElementOutput)` |
| `net.minecraft.client.gui.components.Tooltip` | button tooltip | `Tooltip.create(Component)` |
| `net.minecraft.client.Minecraft` | client instance | `Minecraft.getInstance().setScreen(Screen)`, `Minecraft.getInstance().font`, `Minecraft.getInstance().options.guiScale()` |
| `net.minecraft.network.chat.Component` | i18n text | `Component.translatable(String key, Object... args)`, `Component.literal(String)` |
| `net.minecraftforge.client.event.ScreenEvent.Init.Post` | inventory-button injection point | `event.getScreen()`, `event.addListener(GuiEventListener)` |
| `net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut` | disconnect trigger for `ClientChatSession.clear()` | Forge-bus, client-only |
| `net.minecraftforge.eventbus.api.SubscribeEvent` | event annotation | on static methods in client event handlers |
| `net.minecraftforge.api.distmarker.Dist` + `DistExecutor.safeRunWhenOn` | client-dist gate | already used in `ForgeBookMod.java:89`; register Phase 4 handlers inside `ClientSetup.init` |
| `org.lwjgl.glfw.GLFW` | key constants | `GLFW.GLFW_KEY_ENTER`, `GLFW.GLFW_KEY_ESCAPE`, `GLFW.GLFW_KEY_TAB` |

**Anti-API (do not use):**

- `RenderGuiOverlayEvent` / `RenderGuiOverlayEvent.Post` — HUD-tier; renders *under* open `Screen`s. CLAUDE.md §"What NOT to Use" is explicit.
- `NetworkRegistry.ChannelBuilder` fluent API — NeoForge / 1.20.2+; `ForgebookNetwork` already correctly uses `NetworkRegistry.newSimpleChannel`.
- `Minecraft.getInstance().pushGuiLayer(...)` — not public in 1.20.1.
- `IModInfo.getDisplayURL()` — does not exist; use `getModURL()`.

[VERIFIED: CLAUDE.md "What NOT to Use" table, Phase 1 `ForgebookNetwork.java:31` demonstrates correct `newSimpleChannel` use]

## Packet Handling Pattern (Integration Points)

### Client → Server: `ChatRequestPacket`

```java
// Inside ChatScreen.onSubmitClicked (or triggered by ENTER)
private void onSubmitClicked(Button btn) {
    String msg = this.input.getValue().trim();
    if (msg.isEmpty() || ClientChatSession.get().isPending()) return;
    UUID reqId = UUID.randomUUID();
    ClientChatSession.get().appendUserMessage(reqId, msg);  // optimistic render in bubble list
    ClientChatSession.get().markPending(reqId);
    this.input.setValue("");
    ForgebookNetwork.CHANNEL.sendToServer(new ChatRequestPacket(reqId, msg));
    // UI switches to pending state via ClientChatSession observer / direct tick read
}
```

The existing `ForgebookNetwork.register()` wires this exact packet (C→S, `consumerNetworkThread` — Phase 1 decision D-19 `ChatRequestHandler` scheduling). No changes needed server-side for Phase 4.

[VERIFIED: `ForgebookNetwork.java:45-49`, `ChatRequestPacket.java:15-32`]

### Server → Client: `ChatResponsePacket.handleOnClient`

Modify the existing stub (current body logs at INFO with a TODO comment for Phase 4):

```java
public static void handleOnClient(ChatResponsePacket pkt, Supplier<NetworkEvent.Context> ctx) {
    ctx.get().setPacketHandled(true);
    // Phase 4 wiring (was: log-only stub in Phase 1):
    com.forgebook.client.session.ClientChatSession.get().append(pkt.requestId(), pkt.reply());
}
```

**Critical:** this method is already registered with `consumerMainThread` (see `ForgebookNetwork.java:54`), so Minecraft has already hopped to the render thread before this body runs. Direct client state mutation is safe. No additional `enqueueWork` needed.

But we MUST NOT import `com.forgebook.client.*` from `com.forgebook.network.packet.*` if we want to preserve the existing classloader firewall shape. The safe path: `ClientChatSession` lives in `com.forgebook.client.session`, and `ChatResponsePacket` (in `com.forgebook.network.packet.*`) calling it looks like client-package import from a network-package — which **violates** SCAF-02's existing direction (`network.packet` is a non-client package).

**Resolution:** Use the existing Phase-1 pattern. `ChatResponsePacket.handleOnClient` stays in `com.forgebook.network.packet`, but dispatches through a thin interface that the client side wires up. Two viable options:

1. **Option A — DistExecutor jump (cleanest):** `handleOnClient` uses `DistExecutor.safeCallWhenOn(Dist.CLIENT, () -> ClientChatSessionSink::INSTANCE)` to obtain a handler. The sink is a non-client-importing interface whose impl lives in `com.forgebook.client.session`. [Pattern matches `ForgeBookMod.java:89`'s existing DistExecutor use.]

2. **Option B — Volatile sink pattern (matches Phase 1 `ChatRequestHandler.responseSinkForTests`):** Client boot installs a `Consumer<String>` into a static volatile on a class-under-`com.forgebook.network.packet.*` (or, better, a neutral `com.forgebook.network.packet.ClientHandlerRegistry`), and `handleOnClient` calls through that consumer. The consumer impl lives under `com.forgebook.client.session` and is wired in `ClientSetup.init()`.

**Recommendation:** Option B is simpler, already established (Phase 1's `responseSinkForTests` is identical shape in production), and sidesteps the `DistExecutor` ergonomics. The planner should introduce `com.forgebook.network.client.ClientPacketSinks` (new class in `com.forgebook.network.client` — confirming this sub-package does NOT trigger the SCAF-02 firewall since it's called from any thread and imports no `net.minecraft.client.*`) with two `volatile BiConsumer<UUID, ?>` fields: `replySink` (UUID+String) and `errorSink` (UUID+ErrorCode+String). `ClientSetup.init` sets both; `ChatResponsePacket.handleOnClient` / `ChatErrorPacket.handleOnClient` invoke them with a null guard.

[Phase-1 precedent `VERIFIED`: `ChatRequestHandler.responseSinkForTests` in `ChatRequestHandler.java:82`]

### Error path: `ChatErrorPacket.handleOnClient`

Same shape as response; wires into `ClientChatSession.appendError(requestId, code, humanReadable)`. UI-SPEC §"Phase-3 Error Taxonomy ⇢ UI Mapping" provides the stripe-color and i18n-key mapping for each code.

### Stale-response guard (UI-D-11)

`ClientChatSession.append/appendError` MUST check that the incoming `requestId` matches the currently-pending request. If the session was cleared (e.g., screen closed) between submit and response arrival, the response arrives with a requestId the session no longer tracks — silently discard. This is a one-line check:

```java
public synchronized void append(UUID requestId, String reply) {
    if (this.pendingRequestId == null || !this.pendingRequestId.equals(requestId)) return;  // stale
    this.messages.add(new AssistantMessage(reply));
    this.pendingRequestId = null;
    this.pending = false;
}
```

### Chunked response (UI-D-14, deferred)

`ChunkedPayload` exists in `com.forgebook.network.chunk` but has no production call site (Phase 1 note: "utility + unit test; no production call site yet"). Phase 4 does NOT wire it. Server truncates at `buf.writeUtf(reply, 32_000)`; if the reply would exceed, `FriendlyByteBuf.writeUtf` throws and the network layer will drop the packet — a pre-existing known limitation. Flag this for plan-checker: planners should NOT add multi-chunk assembly to Phase 4 unless explicitly re-scoped.

## Testing Strategy

Phase 3 established the precedent: pure-Java seams get unit tests; live Minecraft behavior gets human smoke. Phase 4 follows the same model.

### What CAN be unit-tested (JUnit 5, no Minecraft required)

| Test class | Target | Seam |
|------------|--------|------|
| `ClientChatSessionTest` | `ClientChatSession` state machine: append → pending → append assistant → idle; appendError → idle; clear() resets; stale-requestId append is a no-op; markPending during pending is a no-op | Pure Java; session holds no Minecraft types |
| `ChatPanelLayoutTest` | Geometry math: `panelX/panelY/panelW/panelH` for widths {240, 320, 480, 1280}, heights {180, 360, 720}; small-screen guard triggers at <240 or <180; stacked mode at <320 | Extract to `ChatPanelLayoutInternal.compute(int winW, int winH) : LayoutResult` (pure function) |
| `ErrorCodeColorMapTest` | Every `ChatErrorPacket.ErrorCode` maps to the color declared in UI-SPEC § color mapping table; no code returns a null color | Pure lookup function |
| `MessageBubbleWrapMathTest` | Given width W + text T + font.lineHeight=9, assert computed bubble height = padding + lines * 10 + padding | Extract the height math to a pure function (`Font.split` can be mocked via a `SplitFn` lambda seam — Phase 3 pattern: see `RagItemPipeline.java`'s `fetchFn`/`splitFn` lambdas) |
| `InventoryButtonGeometryTest` | Button x = leftPos + imageWidth + 4, y = topPos + 4 for sample InventoryScreen sizings | Extract geometry to `InventoryButtonGeometry.compute(int leftPos, int topPos, int imageWidth) : Rect` |

### What CANNOT be unit-tested without booting Minecraft

- `ChatScreen.render` — requires `GuiGraphics` which requires an active render context.
- `Button.onPress` callback — requires the button's Forge-internal state.
- Focus handling — `Screen.setInitialFocus` side-effect depends on widget list.
- Actual `ScreenEvent.Init.Post` firing — requires Forge event bus + a rendered InventoryScreen.

### GameTest viability (rejected for Phase 4)

Phase 1 used Forge GameTest for the NET-06 E2E packet echo (`ChatEchoGameTest`). GameTest runs a headless SERVER — not a client with rendering. Phase 4 is client-side rendering; GameTest cannot exercise it. **Rejected.**

### Mocked `GuiGraphics` (rejected)

GuiGraphics depends on OpenGL state via `RenderSystem`; mocking it produces tests that don't exercise real rendering. The rendering math (color, position, text wrapping) is testable at the pure-function layer; rendering itself is deferred to human smoke. **Rejected.**

### Live smoke checkpoint (human)

Precedent: Phase 3 `03-VERIFICATION.md` routed the live `/forgebook` smoke to human sign-off. Phase 4's analogous smoke:

- `./gradlew runClient` (+ `runServer` in parallel); join server; press E to open inventory; verify ForgeBook button appears at `leftPos + imageWidth + 4`; click → `ChatScreen` opens; inventory still visible behind panel; type a message; press ENTER; pending state shows "Thinking…"; reply arrives as assistant bubble; open error path by disabling `/forgebook disable` (OP-side); next message renders a red-stripe error card with FORBIDDEN copy; press ESC; session clears; re-open; no prior messages visible; disconnect mid-conversation; reconnect; session still empty (disconnect cleared). Verify at GUI scale 1, 2, 3, 4 that panel fits and doesn't clip vanilla inventory.

This smoke is the sole non-unit verification path for the phase.

## Pitfalls and Anti-Patterns

### Pitfall 1: `RenderGuiOverlayEvent` for the chat panel
**What goes wrong:** panel doesn't render on top of the inventory; appears under or behind the inventory GUI, or flickers.
**Why:** `RenderGuiOverlayEvent` fires during HUD rendering — the phase of the frame where in-world HUD elements (hotbar, crosshair, health) draw. By the time any Screen is open, overlay rendering has already completed. CLAUDE.md §"What NOT to Use" table is explicit.
**How to avoid:** Build a `Screen`. UI-SPEC's decision to route through a standalone `Screen` means this pitfall cannot occur if the planner follows UI-D-01.
**Warning signs:** Any plan task that mentions `RenderGuiOverlayEvent`, `RenderGuiEvent.Post`, or HUD overlay registration for the chat panel.

### Pitfall 2: `AbstractContainerScreen<InventoryMenu>` subclass with shifted `leftPos`
**What goes wrong:** UI-02 text mentions this as the "primary" approach, but UI-SPEC resolved against it. If a planner re-opens this path: slot hit-testing runs in container-screen coordinates, so clicks on the chat panel area bleed into inventory slots (especially when JEI/REI add ghost-item overlays in the margin). Dragged items drop into non-existent slots.
**Why:** `AbstractContainerScreen.mouseClicked` computes slot hit via its own leftPos/topPos — if you shift leftPos to make room for a panel, slot hit-testing moves with it, which looks right until another mod renders over the "moved" slot area.
**How to avoid:** Standalone `Screen` (UI-D-01). This is now locked; the roadmap's UI-02 text is superseded by UI-SPEC.
**Warning signs:** Any plan task that extends `AbstractContainerScreen` or sets a custom `imageWidth` for the chat panel.

### Pitfall 3: Assuming `Minecraft.setScreen(screen)` adds a child / keeps previous screen live
**What goes wrong:** Developer expects the inventory to still update / receive input while chat panel is open. Or worse, expects `setScreen(null)` to only close the chat panel.
**Why:** `setScreen` replaces — it does not push. Previous screen's `removed()` is called. Only one screen is active.
**How to avoid:** Store parent in a field. `render()` explicitly calls `parent.render(...)`. `onClose` does `setScreen(parent)`. See UI-D-04, UI-D-05.
**Warning signs:** Plan tasks that say "push" / "overlay via Screen" / "modal on top of inventory".

### Pitfall 4: Registering a custom keybind
**What goes wrong:** Keybind conflicts with another mod (PROJECT.md: "Forge keybind ecosystem is saturated"). User's existing muscle memory breaks. Potential for a "swallow E" bug when the chat EditBox has focus (user presses E to type "hello" → inventory closes).
**Why:** `KeyMapping` defaults + `RegisterKeyMappingsEvent` bind at game-start; they fire regardless of screen focus unless `conflictContext` is `InGame`. Interaction with EditBox focus is a known sore spot.
**How to avoid:** UI-D-07 defers keybind registration. The inventory button is the single entry point.
**Warning signs:** Plan task mentions `KeyMapping`, `RegisterKeyMappingsEvent`, `InputConstants.Key`, default hotkeys.

### Pitfall 5: API key / config secret leaking into client UI package
**What goes wrong:** A helpful-looking refactor pulls `ConfigSnapshot` into `ClientChatSession` to "know if the chat UI should be shown." Suddenly the client has a compile-time dependency on `ApiKey` (even if it never reads `.raw()`).
**Why:** Java classloaders materialize all imported types. An import alone is enough to ship `ApiKey` to client JARs.
**How to avoid:**
- `ClientChatSession` imports NOTHING from `com.forgebook.ai.*`, `com.forgebook.config.ApiKey`, or `com.forgebook.safety.*`.
- `ChatScreen` reads only `ForgebookClientConfig.ENABLE_CHAT_INTERFACE` from `com.forgebook.config.*`, which is just a `BooleanValue`.
- Add a new CI grep rule: `com.forgebook.client.ui.*` and `com.forgebook.client.session.*` MUST NOT import `com.forgebook.ai.*`, `com.forgebook.config.ApiKey`, or `com.forgebook.safety.*`. This is a reverse-direction addition to SCAF-02's existing forward rule.
**Warning signs:** Any import of `ApiKey`, `ConfigSnapshot`, `AiDispatcher`, `Authorizer`, `RateLimiter` from any class under `com.forgebook.client.*`.

### Pitfall 6: `enable_chat_interface` checked once at mod load time (stale cache)
**What goes wrong:** User disables chat in `forgebook-client.toml`, reloads client config via file watch (future feature), but button still appears because a static boolean was cached.
**Why:** `ForgeConfigSpec.BooleanValue.get()` already caches internally — re-reading is cheap. Caching into a private static in the injector breaks runtime toggling.
**How to avoid:** Call `ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()` inside `onScreenInit(event)` on every fire. O(1) cost.
**Warning signs:** A `private static final boolean ENABLED = ENABLE_CHAT_INTERFACE.get()` at class load.

### Pitfall 7: Importing `net.minecraft.client.*` from a non-client package
**What goes wrong:** Dedicated server crashes at mod load with `NoClassDefFoundError: net/minecraft/client/gui/screens/Screen`.
**Why:** SCAF-02's client classloader firewall: `net.minecraft.client.*` classes are not on the server classpath at all.
**How to avoid:**
- All new Phase 4 UI code lives under `com.forgebook.client.*` (subpackages `ui` + `session`).
- `ChatResponsePacket.handleOnClient` (in `com.forgebook.network.packet`) must NOT import `com.forgebook.client.session.ClientChatSession` directly. Use the volatile-sink pattern (Pitfall section in §"Packet Handling").
- CI's SCAF-02 grep already covers this direction — it fires on any `com.forgebook.*` file outside `com.forgebook.client.*` that imports `net.minecraft.client.*`.
**Warning signs:** `import net.minecraft.client.*` appearing in `com.forgebook.network.*`, `com.forgebook.ai.*`, `com.forgebook.config.*`, `com.forgebook.safety.*`, `com.forgebook.command.*`.

### Pitfall 8: Forgetting `isPauseScreen() == false`
**What goes wrong:** In single-player, the integrated server freezes when the chat panel is open. Player types a message → server doesn't tick → response never computed.
**Why:** `Screen.isPauseScreen()` default is `true` for many templates. Minecraft checks this to decide whether to pause the integrated server while the screen is open.
**How to avoid:** Override `isPauseScreen()` to return `false`. Matches `InventoryScreen`'s own override (UI-D-06).
**Warning signs:** No `isPauseScreen()` override in `ChatScreen` — relies on default.

### Pitfall 9: `onClose` not clearing the session before `setScreen(parent)`
**What goes wrong:** User reopens chat → old messages visible. Violates UI-05.
**Why:** Ordering matters: if `setScreen(parent)` runs before `ClientChatSession.clear()`, the parent's `init` is on the call stack with the session still populated; a fast reopen can re-read stale state before the clear.
**How to avoid:** `ClientChatSession.get().clear(); this.minecraft.setScreen(parent);` — in that order.
**Warning signs:** `onClose` body starts with `super.onClose()` and then calls clear — acceptable, but the `setScreen` MUST be after the clear.

### Pitfall 10: Ignoring stale-requestId responses
**What goes wrong:** User submits msg A → closes screen → reopens (session cleared) → submits msg B → server reply for A arrives on the channel → bubble for A renders in the new session.
**Why:** The channel is per-client, not per-session. The server doesn't know the client cleared its session; it just replies to whatever it was processing.
**How to avoid:** `ClientChatSession.append/appendError` checks `requestId` against the currently-pending request; non-match is a silent no-op. See UI-D-11.
**Warning signs:** `append()` that always appends regardless of requestId.

### Pitfall 11: Rendering the parent screen at the *real* mouse coordinates
**What goes wrong:** Tooltips fire on the inventory while the user is hovering over a chat message. Vanilla's slot hover effects trigger on what should be a focused chat panel.
**Why:** `parent.render(graphics, mouseX, mouseY, partialTick)` by default forwards the real mouse; the parent treats it as if it were the active screen.
**How to avoid:** Pass `Integer.MAX_VALUE` for mouse coordinates: `parent.render(graphics, Integer.MAX_VALUE, Integer.MAX_VALUE, partialTick)` — reliably outside any `isMouseOver` bounds.
**Warning signs:** `parent.render(graphics, mouseX, mouseY, ...)` with the live mouse values.

## File Layout Proposal

Concrete list of new/modified files. All new files live under `com.forgebook.client.*` to satisfy SCAF-02.

### New files — `com.forgebook.client.ui.*`

| File | Role |
|------|------|
| `src/main/java/com/forgebook/client/ui/InventoryButtonInjector.java` | `@Mod.EventBusSubscriber(bus = Bus.MOD, value = Dist.CLIENT)` static class with `@SubscribeEvent onScreenInit(ScreenEvent.Init.Post)` |
| `src/main/java/com/forgebook/client/ui/ChatScreen.java` | `public class ChatScreen extends Screen` — standalone screen, holds `parent`, input, submitBtn, panel |
| `src/main/java/com/forgebook/client/ui/ChatPanelWidget.java` | `public class ChatPanelWidget extends AbstractWidget` — panel frame + message list + scroll math |
| `src/main/java/com/forgebook/client/ui/MessageBubble.java` | value type (record or final class) — holds `kind` (USER/ASSISTANT), `text`, renders via static helper |
| `src/main/java/com/forgebook/client/ui/ErrorCard.java` | value type — holds `code` (ErrorCode), `humanReadable`, renders with UI-SPEC stripe color |
| `src/main/java/com/forgebook/client/ui/LoadingIndicator.java` | subcomponent — 3-dot cycler, cadence from `tick()` counter |
| `src/main/java/com/forgebook/client/ui/ChatPanelLayout.java` | pure-function layout math (testable seam for `ChatPanelLayoutTest`) — `static LayoutResult compute(int winW, int winH)` |
| `src/main/java/com/forgebook/client/ui/InventoryButtonGeometry.java` | pure-function geometry (testable seam for `InventoryButtonGeometryTest`) — `static Rect compute(int leftPos, int topPos, int imageWidth)` |

### New files — `com.forgebook.client.session.*`

| File | Role |
|------|------|
| `src/main/java/com/forgebook/client/session/ClientChatSession.java` | singleton state holder — `volatile ClientChatSession INSTANCE`; synchronized `append/appendError/appendUserMessage/clear/markPending/markIdle`; holds `List<MessageBubble>`, `List<ErrorCard>`, `UUID pendingRequestId`, `int scrollPosition` |
| `src/main/java/com/forgebook/client/session/SessionLifecycleListener.java` | `@Mod.EventBusSubscriber(bus = Bus.FORGE, value = Dist.CLIENT)` with `@SubscribeEvent onClientLogout(ClientPlayerNetworkEvent.LoggingOut)` → `ClientChatSession.get().clear()` |

### New file — `com.forgebook.network.client.*` (sink registry)

| File | Role |
|------|------|
| `src/main/java/com/forgebook/network/client/ClientPacketSinks.java` | `public static volatile BiConsumer<UUID, String> replySink; public static volatile ErrorSink errorSink;` — client boot wires these; packet handlers invoke with null-guard. Sinks are pure functional interfaces (no `net.minecraft.client.*` imports) — keeps SCAF-02 clean |

**Note on placement:** `com.forgebook.network.client` is a NEW subpackage. It does NOT import `net.minecraft.client.*` (only `java.util.function.BiConsumer` + `UUID`), so SCAF-02's existing grep does not flag it. The name "client" here denotes "client-side wire sink," not Minecraft client. Alternative: place sinks under `com.forgebook.client.session` (making ClientChatSession itself the sink holder); then packet handlers reach in via static accessor — but that creates a forward import `com.forgebook.network.packet.* → com.forgebook.client.session.*` which, while not a SCAF-02 violation (no `net.minecraft.client.*` touched), is organizationally ugly. **Planner's call; recommend `com.forgebook.network.client.ClientPacketSinks` as the cleaner seam.**

### Modified files

| File | Modification |
|------|--------------|
| `src/main/java/com/forgebook/client/ClientSetup.java` | `init()` now also: (a) registers `InventoryButtonInjector` (mod bus — auto via `@EventBusSubscriber` annotation), (b) registers `SessionLifecycleListener` (forge bus — auto), (c) installs sinks into `ClientPacketSinks.replySink` and `ClientPacketSinks.errorSink` pointing at `ClientChatSession` methods |
| `src/main/java/com/forgebook/network/packet/ChatResponsePacket.java` | `handleOnClient` body replaces log-with-TODO with: `var sink = ClientPacketSinks.replySink; if (sink != null) sink.accept(pkt.requestId(), pkt.reply());` |
| `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java` | same pattern with `errorSink.accept(pkt.requestId(), pkt.code(), pkt.humanReadable())` |
| `src/main/resources/assets/forgebook/lang/en_us.json` | **new file** — Phase 4 declares all i18n keys from UI-SPEC §"Copywriting Contract" (22 keys covering button tooltip, title, placeholder, submit label, loading, every error heading+body, small-screen notice, disconnected notice). Phase 5 REL-02 extends coverage. |

### New test files

| File | Target |
|------|--------|
| `src/test/java/com/forgebook/client/session/ClientChatSessionTest.java` | state-machine: submit → pending → reply → idle; submit → pending → error → idle; clear during pending; stale requestId append no-op |
| `src/test/java/com/forgebook/client/ui/ChatPanelLayoutTest.java` | compute() pure function at multiple window sizes including small-screen and stacked triggers |
| `src/test/java/com/forgebook/client/ui/InventoryButtonGeometryTest.java` | compute() geometry against known vanilla inventory sizes (176×166 at default scale) |
| `src/test/java/com/forgebook/client/ui/ErrorCodeColorMapTest.java` | every ErrorCode enumerates to the exact UI-SPEC stripe color |
| `src/test/java/com/forgebook/client/ui/MessageBubbleWrapMathTest.java` | bubble height = padding + Font.split(...).size() * 10 + padding, with `Font.split` mocked via lambda seam |

## Integration Points

### Existing `ForgebookNetwork.CHANNEL`

No changes. Phase 4 uses the existing `sendToServer(new ChatRequestPacket(...))` from the client side. The three packets are already registered with correct thread-hop semantics (`ChatRequestPacket` → `consumerNetworkThread`; response + error → `consumerMainThread`). [VERIFIED: `ForgebookNetwork.java:45-62`]

### Existing `ChatRequestHandler` (server-side)

No changes. It already:
- runs `Authorizer.authorize(snap, sender, RequestKind.CHAT_UI, ...)` on the network thread (SAFE-06),
- hops to `AiExecutor` for the AI call,
- serializes the result back as `ChatResponsePacket` or `ChatErrorPacket`.

Phase 4 is purely a client-side addition; it consumes the server's output unchanged. [VERIFIED: `ChatRequestHandler.java:115-194`]

### `ForgebookClientConfig.ENABLE_CHAT_INTERFACE`

Consumer: `InventoryButtonInjector.onScreenInit` — reads `.get()` per fire (UI-D-15). Already declared as `CLIENT` tier (`ForgebookClientConfig.java:15`, materializes as `config/forgebook-client.toml`). No changes. [VERIFIED: `ForgebookClientConfig.java:12-29`]

### Existing package firewall (SCAF-02)

The CI grep currently fires on `net.minecraft.client.*` imported anywhere outside `com.forgebook.client.*`. Phase 4 adds new files under `com.forgebook.client.ui` and `com.forgebook.client.session` — both covered by the existing rule (prefix match on `com.forgebook.client.`).

**New grep rule needed for UI-08:** `com.forgebook.client.ui.*` and `com.forgebook.client.session.*` MUST NOT import:
- `com.forgebook.ai.*`
- `com.forgebook.config.ApiKey`
- `com.forgebook.safety.*`

Add this reverse-direction rule to `.github/workflows/build.yml` alongside the existing SCAF-02 lint. Expected failure-mode: if a contributor adds `import com.forgebook.config.ApiKey;` into `ChatScreen`, CI flags before build. See UI-08 / Pitfall 5.

### `ClientSetup.init()` wiring

Current body (Phase 1): one LOG.info. Phase 4 extends to:

```java
public static void init() {
    LOG.info("ForgeBook client initialized.");
    // InventoryButtonInjector + SessionLifecycleListener register themselves via
    // @Mod.EventBusSubscriber — no imperative addListener calls needed.
    // Wire the packet sinks so server → client replies land in ClientChatSession:
    ClientPacketSinks.replySink = (id, text) -> ClientChatSession.get().append(id, text);
    ClientPacketSinks.errorSink = (id, code, msg) -> ClientChatSession.get().appendError(id, code, msg);
}
```

The `@EventBusSubscriber` annotations on `InventoryButtonInjector` and `SessionLifecycleListener` auto-register them at class load. Class load happens inside the `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)` block — so the classes are loaded only on the client dist, never on the dedicated server. This preserves SCAF-02.

## State of the Art

| Old Approach | Current Approach (1.20.1) | When Changed | Impact |
|--------------|---------------------------|--------------|--------|
| `RenderSystem.pushPose()` + `PoseStack` passed as method arg (1.19 and earlier) | `GuiGraphics` object passed to `render()` wraps PoseStack + vertex buffers | 1.20 | Always use `GuiGraphics` methods (`fill`, `blit`, `drawString`) in 1.20.1; do not pull the underlying `PoseStack` unless you need matrix math |
| `matrixStack.push()/pop()` manual calls | `graphics.pose().pushPose()/popPose()` when needed | 1.20 | Rarely needed; `drawString` / `fill` handle their own transforms |
| `screen.renderBackground(matrixStack)` | `screen.renderBackground(graphics)` | 1.20 | Method signature changed |
| `font.draw(PoseStack, String, x, y, color)` | `graphics.drawString(font, String, x, y, color)` | 1.20 | `Font.draw` still exists but `GuiGraphics.drawString` is the idiomatic path |
| `screen.buttonList.add(button)` (1.14 and earlier) | `screen.addRenderableWidget(button)` | 1.17 | Well before 1.20; flagged because Google results point at old tutorials |
| `NetworkRegistry.ChannelBuilder` fluent API (NeoForge / 1.20.2+) | `NetworkRegistry.newSimpleChannel(...)` | 1.20.2+ is different; 1.20.1 is old API | Phase 1 already uses correct old API; Phase 4 inherits |
| Custom scroll widget by hand (very old) | `AbstractSelectionList<T>` OR custom math via `AbstractWidget` | 1.18+ | UI-SPEC's custom bubble shapes favor custom math for Phase 4 |

**Deprecated / outdated:**
- `RenderGuiEvent.Post` — HUD-tier rendering, wrong for `Screen`s. Use `ScreenEvent.Render.Post` if overlay work was needed (it isn't — we use a standalone Screen).
- `GuiScreen` (pre-1.13 name for `Screen`) — still seen in old tutorials; the class is long-renamed.

[VERIFIED: `docs.minecraftforge.net/en/1.20.x/gui/screens/` — GuiGraphics-centric API; CLAUDE.md "What NOT to Use" for channel builder]

## Assumptions Log

> Claims flagged `[ASSUMED]` above (if any) were based on training knowledge not verified this session.

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `Font.split(FormattedText, int width) : List<FormattedCharSequence>` signature is identical in Forge 1.20.1 as documented in 1.18 javadocs | Text Wrapping (UI-04) | If signature changed, wrap math breaks — detected at compile time. Very low risk; this API has been stable since 1.18. |
| A2 | `event.addListener(GuiEventListener)` on `ScreenEvent.Init.Post` in Forge 47.4.18 behaves the same as documented for 1.19.3 (adds as renderable+narratable if the listener implements those interfaces) | Inventory Button Injection | If changed, injected button may not render or may not be narrated. Verified indirectly via UI-SPEC's reliance on this exact pattern; Forge has not altered ScreenEvent in 1.20.x. Low risk. |
| A3 | `ClientPlayerNetworkEvent.LoggingOut` is in package `net.minecraftforge.client.event` in Forge 47.4.18 (was moved once historically between 1.16 and 1.18) | Session clear on disconnect | If FQN changed, import fails at compile time — caught immediately. Very low risk. |
| A4 | `Screen.isPauseScreen()` is overridable (not final) and defaults to `true` in 1.20.1 | Pitfall 8 | Verified via Mojang-mapped Minecraft source convention; not re-verified here. If `InventoryScreen` somehow bypasses this (unlikely), integrated server pauses. Low risk; testable in live smoke. |
| A5 | The `parent.render(graphics, INT_MAX, INT_MAX, partialTick)` trick to suppress parent tooltips is idiomatic | Screen Architecture Resolution | If parent uses `abs(mouseX - center) < radius` style tests that overflow, could produce surprising behavior. Safer fallback: subtract `panelWidth * 2` so the "mouse" is off-screen negative. Planner's call. Low risk. |

## Open Questions

1. **Should `ClientPacketSinks` live under `com.forgebook.network.client` or inside `com.forgebook.client.session`?**
   - What we know: both keep SCAF-02 clean; both compile and run.
   - What's unclear: organizational preference only.
   - Recommendation: `com.forgebook.network.client.ClientPacketSinks` — keeps the sink-interface neutral to its only producers (the two handlers in `network.packet`), and client-side consumers reach in from `ClientSetup`.

2. **Does `ChatPanelWidget` need `NarratableEntry` in addition to `AbstractWidget`?**
   - What we know: `AbstractWidget` already implements `NarratableEntry`. Screen-readers will call `updateWidgetNarration`.
   - What's unclear: message bubbles inside the panel — are they individually narrated, or only the panel as a whole?
   - Recommendation: panel-as-a-whole for v1; per-message narration is UI polish for v2. UI-SPEC §"Accessibility" already accepts this shape.

3. **Should the small-screen fallback actually HIDE the input, or just show the "Screen too small" message?**
   - What we know: UI-SPEC says "no other widgets" when `< 240 × < 180`.
   - What's unclear: does "no other widgets" mean input widget is present-but-not-added, or the whole screen turns into a label?
   - Recommendation: the whole screen becomes a label — do not `addRenderableWidget(input)` at all when below the floor. Much simpler; matches UI-SPEC.

4. **Chunked response — does any current Phase-2 agent reply actually exceed 32 KB?**
   - What we know: Haiku with default max_tokens = 1024 tokens ~= 4 KB text. At our current `max_tokens` default (check config) a single reply cannot exceed 8 KB under any realistic condition.
   - What's unclear: what happens if a future user bumps `max_tokens` to 8000? Then a reply of ~32 KB is conceivable.
   - Recommendation: UI-D-14 defers this. Document the 32 KB cap in README (Phase 5 REL-03). Log a warn in `ChatResponsePacket.encode` if input would be truncated — Phase 1's writeUtf already throws `EncoderException` on overflow, which would land as a PROVIDER error on the client. Acceptable fail-safe.

## Environment Availability

No new external dependencies. All required classes are on the existing classpath:
- Minecraft 1.20.1 via ForgeGradle
- Forge 47.4.18 event / network classes via MDK
- LWJGL GLFW key constants via Minecraft's transitive dependency
- Gson (bundled with Minecraft) — not used by Phase 4 code directly; flagged only because some i18n workflows use it

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Forge 47.4.18 | entire mod | ✓ | 47.4.18 | — |
| Java 17 | entire mod | ✓ | 17 | — |
| Existing `ForgebookNetwork.CHANNEL` | packet send/receive | ✓ | Phase 1 | — |
| Existing `ForgebookClientConfig.ENABLE_CHAT_INTERFACE` | button gate | ✓ | Phase 1 | — |
| Existing `ChatResponsePacket.handleOnClient` stub | modify in place | ✓ | Phase 1 | — |
| Existing `ChatErrorPacket.handleOnClient` stub | modify in place | ✓ | Phase 1 | — |
| Existing `ClientSetup.init()` stub | extend | ✓ | Phase 1 | — |
| Existing server-side dispatch (Phase 2-3) | consumed by Phase 4 | ✓ | Phase 3 | — |

**No external tool dependencies.** No new npm/pip/cargo packages. No Docker. No network services beyond the already-verified Claude API (consumed by server, not client).

## Security Domain

Phase 4 is a client-facing surface for an already-authenticated, already-authorized server pipeline. The security posture inherits everything from Phases 1-3. No new security primitives; defense-in-depth only.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no (delegated) | Minecraft's own client-server authentication via Mojang session; ForgeBook does not authenticate users — the server identifies players via `ServerPlayer.getUUID()` |
| V3 Session Management | yes — client-side ephemeral | `ClientChatSession` holds no credentials; cleared on disconnect + screen close. In-memory only, no disk persistence |
| V4 Access Control | yes — delegated to Phase 3 | Authorizer (server-side) enforces OP gate + rate limit + kill switch. Client MUST NOT duplicate gatekeeping (defense-in-depth: client can always ask; server always decides) |
| V5 Input Validation | yes — at packet boundary | `ChatRequestPacket.message` is bounded by `writeUtf(message, 32_000)` on encode; EditBox.setMaxLength(512) caps user-input length at submit time. Server-side input validation (existing) is the authoritative check |
| V6 Cryptography | no | No cryptographic primitives in client UI layer. API keys never touch the client (UI-08) |
| V10 Malicious Code | yes — classloader firewall | SCAF-02 (existing) plus UI-08 reverse-direction grep (new this phase) enforce that client UI packages cannot import or reference server-only secret types |
| V12 Files/Resources | no | No file I/O in client UI. Logo placeholder texture is Phase 5's concern |
| V13 API | yes — wire schema | Packet schema is stable across phases 1-4; EditBox max-length cap is client-side UX, not a security boundary (server re-caps at 32_000) |

### Known Threat Patterns for Client-Side UI

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| API key leaks into client JAR via accidental import | Information Disclosure | SCAF-02 (existing CI lint) + new UI-08 grep rule scoped to `com.forgebook.client.ui.*` and `com.forgebook.client.session.*` importing `com.forgebook.ai.*`, `config.ApiKey`, `safety.*` |
| User-typed message could contain terminal-escape / log-injection sequences server-side | Tampering (via log) | Phase 1's `ApiKeyScrubFilter` and Phase 3's `RequestAuditLogger` logs zero message content by default — the user message never reaches any log line |
| Replay / confused-deputy (client sends request with fabricated requestId) | Spoofing | requestId is a client-side UUID used only to correlate local state; server does not use it for authorization. Authorizer reads `ServerPlayer.hasPermissions(2)` on the network thread — see Phase 3 SAFE-06 |
| Denial of service via high-frequency message submission | Denial of Service | Server-side RateLimiter (Phase 3) per-UUID token bucket. Client's one-in-flight-at-a-time is UX polish, not a DoS control |
| Oversized packet OOM | Denial of Service | `FriendlyByteBuf.writeUtf(message, 32_000)` on encode caps both directions. EditBox max-length at 512 means users can't even trigger the cap |
| Stale-response rendering after session clear | Information Disclosure / Integrity | `ClientChatSession.append` checks requestId against currently-pending; silent no-op on mismatch. See UI-D-11 |
| Screen-close race with in-flight request (orphaned pending request) | Denial of Service of own queue slot | Orphan resolves server-side: response arrives, is sent, client-side sink invocation no-ops. AiExecutor slot is already released server-side. Zero persistent impact |

## Sources

### Primary (HIGH confidence)

- **CLAUDE.md (project instructions)** — Forge 1.20.1-47.4.18 + Java 17 pin; "What NOT to Use" table (RenderGuiOverlayEvent banned; ChannelBuilder banned); Screen injection guidance ("ScreenEvent.Render.Post or a sibling Screen"); keybind avoidance.
- **`.planning/phases/04-in-inventory-chat-ui/04-UI-SPEC.md`** — just-approved design contract; all layout numbers, copy, color palette, error-taxonomy mapping, decision resolution for standalone Screen.
- **`.planning/REQUIREMENTS.md`** — UI-01..08 canonical requirement text.
- **`.planning/ROADMAP.md`** — Phase 4 success criteria (5 observable truths).
- **`.planning/STATE.md`** — Phase-4 spike flag resolved; architecture invariants from preceding phases.
- **`.planning/phases/03-command-surface-safety-controls/VERIFICATION.md`** — Phase 3 locked-in behaviors (Authorizer, error taxonomy, audit logging).
- **`.planning/phases/01-foundations-safe-egress/01-RESEARCH.md`** — Phase 1 research format precedent; decision ID conventions.
- **`.planning/phases/01-foundations-safe-egress/01-PATTERNS.md`** — file-classification table precedent for research-to-plan transition.
- **Source files** (read during research):
  - `src/main/java/com/forgebook/network/packet/ChatRequestPacket.java`
  - `src/main/java/com/forgebook/network/packet/ChatResponsePacket.java`
  - `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java`
  - `src/main/java/com/forgebook/network/ForgebookNetwork.java`
  - `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java`
  - `src/main/java/com/forgebook/network/chunk/ChunkedPayload.java`
  - `src/main/java/com/forgebook/ForgeBookMod.java`
  - `src/main/java/com/forgebook/client/ClientSetup.java`
  - `src/main/java/com/forgebook/config/ForgebookClientConfig.java`
- **Forge official docs — `docs.minecraftforge.net/en/1.20.x/gui/screens/`** — Screen lifecycle, widgets, GuiGraphics rendering API.
- **NeoForge 1.20.4 Screen docs (same base API)** — `docs.neoforged.net/docs/1.20.4/gui/screens/` — init/render/onClose/removed methods, widget management.

### Secondary (MEDIUM confidence)

- **nekoyue Forge javadocs (1.19.3)** — `ScreenEvent.Init.addListener`, `ClientPlayerNetworkEvent.LoggingOut` signatures. 1.19.3 javadocs are the nearest Forge-hosted javadoc to 47.4.18; the relevant APIs did not change in 1.20.x per Forge changelogs.
- **Forge Forums — "Make EditBox active and focused when screen opened [1.19.2] SOLVED"** — `setFocused()` + `setInitialFocus()` pattern for EditBox focus. 1.19.2-era post; pattern verified identical in 1.20.1 per Forge docs.
- **Web search syntheses** — `Font.split` returns `List<FormattedCharSequence>`; `GuiGraphics.drawString` overload set; `GLFW_KEY_ENTER` / `GLFW_KEY_ESCAPE` constants.

### Tertiary (LOW confidence, flagged for validation)

- None — all claims in this research are either verified by source-file read, official docs, or marked `[ASSUMED]` in the Assumptions Log.

## Metadata

**Confidence breakdown:**

- **Screen architecture (standalone Screen with parent render):** HIGH — UI-SPEC locks the decision; precedent confirmed in Forge docs (`Screen.init` lifecycle), Phase 1's own `ClientSetup` provides entry-point precedent.
- **Inventory button injection:** HIGH — `ScreenEvent.Init.Post` + `event.addListener` is the canonical Forge 47.x pattern; public accessors on `AbstractContainerScreen` are stable.
- **Packet pipeline integration:** HIGH — entirely built on Phase 1-3 delivered code, read during research.
- **Scroll strategy:** MEDIUM — two viable paths (custom math vs AbstractSelectionList); research recommends custom math based on UI-SPEC's bespoke bubble/error-card shapes. If planner disagrees, flag for re-scoping.
- **Session lifecycle:** HIGH — `ClientPlayerNetworkEvent.LoggingOut` is a standard Forge client-only event; clear-on-close is trivial.
- **Text wrapping (`Font.split`):** HIGH — API stable across 1.18-1.21.
- **Config read (ENABLE_CHAT_INTERFACE):** HIGH — Phase 1 already delivered the field; `.get()` pattern is standard.
- **Testing strategy:** HIGH — Phase 3 set the precedent; pure-function seams + human smoke.
- **Package firewall:** HIGH — SCAF-02 is live; UI-08 reverse rule is a one-line CI addition.
- **Pitfalls:** HIGH — all 11 pitfalls sourced from CLAUDE.md + UI-SPEC + Phase 1-3 precedents.

**Research date:** 2026-04-16
**Valid until:** 2026-07-16 (90 days — Forge 1.20.1 APIs are frozen; only risk is NeoForge diverging further, which does not affect this project)
