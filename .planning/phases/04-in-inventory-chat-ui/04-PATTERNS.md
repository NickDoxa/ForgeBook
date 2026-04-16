# Phase 4: In-Inventory Chat UI — Pattern Map

**Mapped:** 2026-04-16
**Files analyzed:** 22 (13 new prod + 2 modified + 5 new test + 2 resource — count excludes the `ClientSetup` stub which already exists)
**Analogs found:** 22 / 22 (all have concrete in-repo anchors — this is no longer a greenfield phase)

> **Note on analog quality.** Every new file in Phase 4 has a Phase 1–3 in-repo analog: the Phase-3 `*Internal` test-seam pattern (Authorizer / ItemSubcommand / RagItemPipeline) maps directly onto the new UI pure-function seams; the Phase-1 volatile-sink pattern (`ChatRequestHandler.responseSinkForTests`) maps onto `ClientPacketSinks`; the Phase-1 network registration pattern (`ForgebookNetwork.register`) maps onto `ClientSetup.init`. Planner should COPY shape from these; no pattern is invented in this phase.

---

## File Classification

Grouped by subsystem. Each row lists `New File | Role | Data Flow | Closest Analog | Match Quality`.

### Subsystem A — Event Listeners & Injection (UI-01, UI-06)

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `src/main/java/com/forgebook/client/ui/InventoryButtonInjector.java` | event-listener (client mod-bus) | event-driven | `src/main/java/com/forgebook/command/ForgebookCommands.java` (pure event handler + config read + Brigadier-style fluent build) | role-match: both subscribe to a Forge event and perform registration |
| `src/main/java/com/forgebook/client/session/SessionLifecycleListener.java` | event-listener (client forge-bus) | event-driven | `src/main/java/com/forgebook/ForgeBookMod.java:60-78` (multiple `MinecraftForge.EVENT_BUS.addListener` + lambda → static call) | role-match: one-line forge-bus listener that clears state on a lifecycle event |

### Subsystem B — Client UI (UI-02, UI-04, UI-07)

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `src/main/java/com/forgebook/client/ui/ChatScreen.java` | screen (standalone `Screen` subclass) | request-response (user input → packet send) | `src/main/java/com/forgebook/command/ItemSubcommand.java` (public entry unpacks MC types → pure `*Internal` seam) | role-match: shape of "MC-facing public method + pure-seam delegation" transfers directly |
| `src/main/java/com/forgebook/client/ui/ChatPanelWidget.java` | widget (custom `AbstractWidget`) | streaming (renders current session state each frame) | NO in-repo analog (first widget in project) — fall back to RESEARCH §"Scroll Strategy" + Forge docs | no-analog: see §"No Analog Found" |
| `src/main/java/com/forgebook/client/ui/MessageBubble.java` | value-type (record) | — | `src/main/java/com/forgebook/safety/RateLimiter.java:41-50` (sealed interface + record variants) | role-match: record-as-value-type precedent; also records like `ChatRequestPacket`, `ChatResponsePacket` |
| `src/main/java/com/forgebook/client/ui/ErrorCard.java` | value-type (record) | — | same as MessageBubble | role-match |
| `src/main/java/com/forgebook/client/ui/LoadingIndicator.java` | subcomponent (pure-function + tick counter) | streaming (redraws every frame) | `src/main/java/com/forgebook/safety/TokenBucket.java` (tiny stateful helper with single public method) | role-match: small stateful helper |

### Subsystem C — Pure-Function Layout Seams (test seams — UI-04, UI-07)

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `src/main/java/com/forgebook/client/ui/InventoryButtonGeometry.java` | utility (pure function) | transform (int,int,int → Rect) | `src/main/java/com/forgebook/safety/TokenBucket.java` + `src/main/java/com/forgebook/util/Cidr.java` (pure compute helper, unit-tested) | exact: both are pure-Java helpers exposed for testing |
| `src/main/java/com/forgebook/client/ui/ChatPanelLayout.java` | utility (pure function) | transform (winW,winH → LayoutResult record) | `src/main/java/com/forgebook/config/ConfigHolder.java:buildFromSpec` + Authorizer primitive overload | exact: pure-function seam precedent is Phase-3 canonical |

### Subsystem D — Session State (UI-05, UI-10, UI-11)

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `src/main/java/com/forgebook/client/session/ClientChatSession.java` | singleton state-holder | pub-sub (writes from packet handlers, reads from render loop) | `src/main/java/com/forgebook/safety/KillSwitch.java` (static-holder + volatile/atomic + get/set) + `src/main/java/com/forgebook/config/ConfigHolder.java` (volatile-reference holder + seed/swap) + `src/main/java/com/forgebook/safety/RateLimiterHolder.java` (same shape) | exact: three precedent holders; ClientChatSession is their synthesis |

### Subsystem E — Packet Sinks (modified + new sink holder)

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `src/main/java/com/forgebook/network/client/ClientPacketSinks.java` | sink-registry (volatile-holder) | pub-sub (ClientSetup writes; packet handlers read) | `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java:82` (`public static volatile Consumer<Object> responseSinkForTests`) | exact: same pattern, promoted from test-only to production wiring |

### Subsystem F — Modifications (no new files)

| Modified File | Modification | Anchor |
|---------------|--------------|--------|
| `src/main/java/com/forgebook/network/packet/ChatResponsePacket.java` | `handleOnClient` body: replace Phase-1 log-only TODO stub with null-guarded `ClientPacketSinks.replySink.accept(...)` invocation | existing body at line 30–34; same structure as `ChatRequestHandler.handleForTest:128-130` sink-read pattern |
| `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java` | same pattern, `errorSink.accept(pkt.requestId, pkt.code, pkt.humanReadable)` | existing body at line 42–46 |
| `src/main/java/com/forgebook/client/ClientSetup.java` | extend `init()`: install sinks into `ClientPacketSinks` (event-listener classes auto-register via `@Mod.EventBusSubscriber`) | existing body at line 19–21; same shape as `ForgeBookMod` constructor wiring |
| `src/main/resources/assets/forgebook/lang/en_us.json` | **NEW FILE** — declare 18 i18n keys from UI-SPEC §"Copywriting Contract" | no existing en_us.json; UI-SPEC table rows 191-213 are the authoritative source |

### Subsystem G — Tests

| New File | Role | Data Flow | Closest Analog | Match Quality |
|----------|------|-----------|----------------|---------------|
| `src/test/java/com/forgebook/client/session/ClientChatSessionTest.java` | test (unit, state-machine) | — | `src/test/java/com/forgebook/safety/RateLimiterTest.java` (state-machine test of a holder-class + sealed outcome) | exact |
| `src/test/java/com/forgebook/client/ui/ChatPanelLayoutTest.java` | test (unit, parameterized) | — | `src/test/java/com/forgebook/util/CidrTest.java` (pure-function unit test over multiple input cases) | exact |
| `src/test/java/com/forgebook/client/ui/InventoryButtonGeometryTest.java` | test (unit) | — | same as ChatPanelLayoutTest | exact |
| `src/test/java/com/forgebook/client/ui/ErrorCodeColorMapTest.java` | test (unit, enum coverage) | — | `src/test/java/com/forgebook/safety/AuthorizerTest.java` (enum-branch coverage via exhaustive case list) | exact |
| `src/test/java/com/forgebook/client/ui/MessageBubbleWrapMathTest.java` | test (unit, lambda-seam) | — | `src/test/java/com/forgebook/command/ItemSubcommandTest.java` (uses lambda-function injection to sidestep Minecraft classloading) | exact |

---

## Pattern Assignments

### `InventoryButtonInjector.java` (event-listener, event-driven)

**Analog:** `src/main/java/com/forgebook/command/ForgebookCommands.java` (for the event-handler shape + "read config; don't cache" idiom) plus the `@Mod.EventBusSubscriber(bus = Bus.MOD, value = Dist.CLIENT)` pattern described in RESEARCH §"File Layout Proposal".

**Package declaration + imports pattern** (copy shape from `ForgebookCommands.java:1-9` and `ChatRequestHandler.java:17-23`):
```java
package com.forgebook.client.ui;

import com.forgebook.client.session.ClientChatSession;
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
```

**Class-level event-subscriber pattern** (no direct analog — first `@Mod.EventBusSubscriber` in project; CLAUDE.md §"What NOT to Use" mandates explicit `bus = Bus.MOD`):
```java
@Mod.EventBusSubscriber(modid = "forgebook", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class InventoryButtonInjector {
    private static final Logger LOG = LogManager.getLogger();
    private InventoryButtonInjector() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        // Pattern copied from ForgebookCommands.onRegister: read; short-circuit; register.
        if (!ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()) return;  // UI-06 — D-15 re-read every time
        if (!(event.getScreen() instanceof InventoryScreen inv)) return;
        int x = inv.getGuiLeft() + inv.getXSize() + 4;
        int y = inv.getGuiTop() + 4;
        Button btn = Button.builder(
                Component.literal("F"),
                b -> Minecraft.getInstance().setScreen(new ChatScreen(inv)))
            .bounds(x, y, 20, 20)
            .tooltip(Tooltip.create(Component.translatable("forgebook.chat.button.tooltip")))
            .build();
        event.addListener(btn);
    }
}
```

**Key invariants to preserve from analog:**
- Private constructor + `final` class, matching `ForgebookCommands` line 32, 36.
- Private static `LOG` at `LogManager.getLogger()` — matches every production class (see `ForgebookCommands:34`, `ItemSubcommand:90`).
- Config read via `.get()` per-fire — explicitly NOT cached (RESEARCH Pitfall 6; mirrors `ConfigHolder.get()` single-volatile-load pattern in `ItemSubcommand.executeInternal:206`).

**Geometry extracted to pure function:** extract the `leftPos + imageWidth + 4` / `topPos + 4` math into `InventoryButtonGeometry.compute(...)` so it can be unit-tested without booting Minecraft. Pattern copied from Phase-3 Authorizer's primitive-overload seam (`Authorizer.java:75-105`).

---

### `InventoryButtonGeometry.java` (pure function, transform)

**Analog:** `src/main/java/com/forgebook/util/Cidr.java` + the primitive-overload seam in `src/main/java/com/forgebook/safety/Authorizer.java:75-105`.

**Shape to copy** (from Authorizer lines 45-56 + 75-105):
```java
package com.forgebook.client.ui;

/**
 * Pure-function geometry for the in-inventory chat button.
 * Testable without booting Minecraft. Production: {@link InventoryButtonInjector}
 * unpacks {@code AbstractContainerScreen#getGuiLeft/getGuiTop/getXSize} into three
 * ints and delegates here — same primitive-overload pattern as
 * {@link com.forgebook.safety.Authorizer#authorize(ConfigSnapshot, UUID, boolean, RequestKind, RateLimiter)}.
 */
public final class InventoryButtonGeometry {

    /** Result record — mirrors Authorizer.Allowed/Denied shape (sealed/record value type). */
    public record Rect(int x, int y, int w, int h) {}

    private InventoryButtonGeometry() {}

    public static Rect compute(int leftPos, int topPos, int imageWidth) {
        // UI-SPEC §"Inventory Button Injection": x = leftPos + imageWidth + 4, y = topPos + 4, 20×20.
        return new Rect(leftPos + imageWidth + 4, topPos + 4, 20, 20);
    }
}
```

**Testing pattern** (copy from `RateLimiterTest.java:10-22`):
- Use `@Test` methods with static inputs (vanilla inventory is always 176×166 at `leftPos=Х,topPos=Y`).
- Assert record fields directly with `assertEquals`.
- No mocks, no `@BeforeEach` state.

---

### `ChatScreen.java` (screen, request-response)

**Analog:** `src/main/java/com/forgebook/command/ItemSubcommand.java` — for the "public MC-facing entry unpacks types → pure seam" structure and the `failureSinkForTests` volatile sink pattern.

**Imports** (mirror `ItemSubcommand:1-40` structure; client-package replaces command-package):
```java
package com.forgebook.client.ui;

import com.forgebook.client.session.ClientChatSession;
import com.forgebook.network.ForgebookNetwork;
import com.forgebook.network.packet.ChatRequestPacket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;
```

**Screen lifecycle pattern** (concrete sketch is in RESEARCH §"Screen Architecture Resolution" lines 160-232 — COPY VERBATIM subject to planner-chosen field names):
- Constructor stores `parent: Screen`.
- `init()` calls `parent.init(minecraft, width, height)` first, then adds widgets via `addRenderableWidget(...)` (RESEARCH §"Screen Architecture Resolution").
- `render(GuiGraphics, int, int, float)` calls `parent.render(graphics, Integer.MAX_VALUE, Integer.MAX_VALUE, partialTick)` first (RESEARCH Pitfall 11), then `this.renderBackground(graphics)`, then `super.render(...)`.
- `onClose()` does `ClientChatSession.get().clear(); this.minecraft.setScreen(parent);` — ORDER MATTERS (RESEARCH Pitfall 9).
- `isPauseScreen()` returns `false` (RESEARCH Pitfall 8).
- `keyPressed` handles `GLFW_KEY_ESCAPE` → `onClose()` and `GLFW_KEY_ENTER` → submit (RESEARCH §"Interaction Contract").

**Submit flow** (copy shape from RESEARCH lines 370-381):
```java
private void onSubmitClicked(Button btn) {
    String msg = this.input.getValue().trim();
    if (msg.isEmpty() || ClientChatSession.get().isPending()) return;
    UUID reqId = UUID.randomUUID();
    ClientChatSession.get().appendUserMessage(reqId, msg);
    ClientChatSession.get().markPending(reqId);
    this.input.setValue("");
    ForgebookNetwork.CHANNEL.sendToServer(new ChatRequestPacket(reqId, msg));
}
```

**What to NOT copy from ItemSubcommand:** the `failureSinkForTests` pattern (that's for rendering via `sendFailure` on `CommandSourceStack`; ChatScreen uses vanilla `GuiGraphics` drawing + `ClientChatSession` directly). ChatScreen itself is not unit-tested — only its extracted pure-function dependencies (`ChatPanelLayout`, `InventoryButtonGeometry`, `ClientChatSession`) are.

**Class final + private-ctor convention:** `public class ChatScreen extends Screen` (NOT `final` — Screen subclasses aren't final so future v2 surface can extend; but no nested state beyond what `init` sets). Constructor is public (called from `InventoryButtonInjector`).

---

### `ChatPanelLayout.java` (pure function, transform)

**Analog:** `src/main/java/com/forgebook/util/Cidr.java` + `Authorizer` primitive-overload.

**Shape:**
```java
package com.forgebook.client.ui;

public final class ChatPanelLayout {

    public record LayoutResult(
        boolean tooSmall,      // width < 240 OR height < 180 → show only "too small" label
        boolean stacked,       // width < 320 → inventory hidden
        int panelX, int panelY, int panelW, int panelH
    ) {}

    private ChatPanelLayout() {}

    public static LayoutResult compute(int winW, int winH) {
        if (winW < 240 || winH < 180) {
            return new LayoutResult(true, false, 0, 0, 0, 0);
        }
        boolean stacked = winW < 320;
        int panelW = stacked ? winW - 16 : 240;
        int panelX = stacked ? 8 : (winW - panelW) / 2;
        int panelY = 20;
        int panelH = winH - 40;
        return new LayoutResult(false, stacked, panelX, panelY, panelW, panelH);
    }
}
```

**Testing pattern** (copy from `CidrTest.java` and `RateLimiterTest.java`):
- One `@Test` per boundary condition: 1280×720 (normal), 480×360 (normal), 320×240 (stacked boundary), 319×240 (stacked), 240×180 (min), 239×180 (tooSmall), 320×179 (tooSmall).
- No Minecraft imports anywhere in the test file (verify by looking at `CidrTest.java`).

---

### `ClientChatSession.java` (singleton state-holder, pub-sub)

**Analog:** Triple-source — `src/main/java/com/forgebook/safety/KillSwitch.java` (AtomicBoolean + private ctor), `src/main/java/com/forgebook/config/ConfigHolder.java` (volatile-reference + buildFromSpec), `src/main/java/com/forgebook/safety/RateLimiterHolder.java` (identical shape).

**Imports** (follow Phase-1 holder convention — zero Minecraft imports; state classes are pure Java):
```java
package com.forgebook.client.session;

import com.forgebook.client.ui.ErrorCard;
import com.forgebook.client.ui.MessageBubble;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
```

**Singleton shape** (copy from `KillSwitch.java:19-28` + `ConfigHolder.java:13-46`):
```java
public final class ClientChatSession {

    private static final ClientChatSession INSTANCE = new ClientChatSession();
    public static ClientChatSession get() { return INSTANCE; }

    private final List<MessageBubble> bubbles = new ArrayList<>();
    private final List<ErrorCard> errors = new ArrayList<>();
    private volatile UUID pendingRequestId = null;
    private volatile boolean pending = false;

    private ClientChatSession() {}

    // All write methods synchronized — mirrors TokenBucket.tryAcquire synchronized pattern.
    public synchronized void appendUserMessage(UUID id, String text) { /* ... */ }
    public synchronized void markPending(UUID id) { /* ... */ }
    public synchronized void markIdle() { /* ... */ }
    public synchronized void append(UUID id, String reply) { /* stale-guard per UI-D-11 */ }
    public synchronized void appendError(UUID id, ErrorCode code, String msg) { /* stale-guard */ }
    public synchronized void clear() { bubbles.clear(); errors.clear(); pendingRequestId = null; pending = false; }
    public boolean isPending() { return pending; }
    // Read accessors return defensive copies.
    public synchronized List<MessageBubble> snapshotBubbles() { return List.copyOf(bubbles); }
    public synchronized List<ErrorCard> snapshotErrors() { return List.copyOf(errors); }
}
```

**Stale-response guard** (copy from RESEARCH lines 422-429):
```java
public synchronized void append(UUID requestId, String reply) {
    if (this.pendingRequestId == null || !this.pendingRequestId.equals(requestId)) return; // stale
    this.bubbles.add(MessageBubble.assistant(reply));
    this.pendingRequestId = null;
    this.pending = false;
}
```

**Key invariants from KillSwitch/ConfigHolder/RateLimiterHolder:**
- `private` constructor.
- `final` class.
- Single `INSTANCE` via private static final (eager init — matches singleton pattern of volatile holders, no need for lazy init).
- No `net.minecraft.*` imports anywhere in this file.

**Test seam (UI-D-19):** per CONTEXT, consider exposing a `ClientChatSessionInternal` static helper for pure-data test paths, OR (preferred per Phase-3 precedent) expose package-private `@VisibleForTesting` methods like `appendForTest(UUID, String, Instant)`. Match the `ChatRequestHandler.responseSinkForTests` pattern (`ChatRequestHandler.java:82`).

---

### `SessionLifecycleListener.java` (event-listener, event-driven)

**Analog:** `src/main/java/com/forgebook/ForgeBookMod.java:60-78` — the pattern of registering a forge-bus listener to a lifecycle event (there: `ServerStartingEvent`; here: `ClientPlayerNetworkEvent.LoggingOut`).

**Full shape** (one-file, minimal — mirrors the inline listeners in ForgeBookMod but broken out due to `@Mod.EventBusSubscriber` on client-only events):
```java
package com.forgebook.client.session;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "forgebook", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SessionLifecycleListener {
    private SessionLifecycleListener() {}

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientChatSession.get().clear();
    }
}
```

**Key: explicit `bus = Bus.FORGE`** — CLAUDE.md §"What NOT to Use" row: *"`@Mod.EventBusSubscriber` without `bus = ...`"* — always specify.

---

### `ClientPacketSinks.java` (sink-registry, pub-sub)

**Analog:** `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java:82` — the existing `public static volatile Consumer<Object> responseSinkForTests = null;` production pattern. Phase 4 promotes this shape from test-only to production wiring.

**Full shape** — tiny file, package `com.forgebook.network.client` (NOT `com.forgebook.client.*`, because this package intentionally has no `net.minecraft.client.*` dependency and is read from the `network.packet` handlers; see RESEARCH §"File Layout Proposal" and Pitfall 7):
```java
package com.forgebook.network.client;

import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Volatile-sink registry. Client boot (ClientSetup.init) installs these; packet
 * handlers in com.forgebook.network.packet read them with a null-guard. Pattern
 * identical to ChatRequestHandler.responseSinkForTests — promoted to production
 * to keep packet handlers free of com.forgebook.client.* imports (SCAF-02 / UI-08).
 *
 * No net.minecraft.client.* imports here — SCAF-02 grep does not flag this file
 * despite the "client" in the package name. ("Client" here = client-side wire sink.)
 */
public final class ClientPacketSinks {

    /** Set by ClientSetup.init on the client dist. Null on dedicated server. */
    public static volatile BiConsumer<UUID, String> replySink = null;

    /** Triple-arg BiConsumer-shaped via a functional interface since BiConsumer is binary. */
    @FunctionalInterface public interface ErrorSink {
        void accept(UUID id, ErrorCode code, String humanReadable);
    }

    public static volatile ErrorSink errorSink = null;

    private ClientPacketSinks() {}
}
```

**Grep-firewall check:** the `network.client` subpackage name is deliberate — reread the CI grep in `.github/workflows/build.yml` lines 38-45. The existing rule fires on `import net.minecraft.client.*` outside `com.forgebook.client.*`. `ClientPacketSinks` imports zero `net.minecraft.*`, so it is NOT flagged.

---

### Modifications to `ChatResponsePacket.handleOnClient` and `ChatErrorPacket.handleOnClient`

**Current state:** `ChatResponsePacket.java:30-34` and `ChatErrorPacket.java:42-46` — both have a Phase-1 log-only stub with a TODO comment for Phase 4.

**Modification pattern** (shape copied from `ChatRequestHandler.java:128-130` volatile-sink read):

`ChatResponsePacket.java:30-34` becomes:
```java
public static void handleOnClient(ChatResponsePacket pkt, Supplier<NetworkEvent.Context> ctx) {
    ctx.get().setPacketHandled(true);
    java.util.function.BiConsumer<java.util.UUID, String> sink =
        com.forgebook.network.client.ClientPacketSinks.replySink;
    if (sink != null) {
        sink.accept(pkt.requestId, pkt.reply);
    } else {
        LOG.warn("ChatResponsePacket received on client before sink installed; dropping.");
    }
}
```

`ChatErrorPacket.java:42-46` becomes:
```java
public static void handleOnClient(ChatErrorPacket pkt, Supplier<NetworkEvent.Context> ctx) {
    ctx.get().setPacketHandled(true);
    com.forgebook.network.client.ClientPacketSinks.ErrorSink sink =
        com.forgebook.network.client.ClientPacketSinks.errorSink;
    if (sink != null) {
        sink.accept(pkt.requestId, pkt.code, pkt.humanReadable);
    } else {
        LOG.warn("ChatErrorPacket received on client before sink installed; dropping.");
    }
}
```

**Why this shape works under SCAF-02:** `ChatResponsePacket` / `ChatErrorPacket` live in `com.forgebook.network.packet.*` which IS covered by the client-firewall exclusion rule. They now depend on `com.forgebook.network.client.ClientPacketSinks` (NOT on `com.forgebook.client.*`). The actual mutable state (`ClientChatSession`) is reached only via the sink lambda installed by `ClientSetup.init` — at runtime, the server-dist packet handlers simply see `sink == null` and log-warn, preventing any `net.minecraft.client.*` class from ever being loaded on a dedicated server.

**Why NOT import `ClientChatSession` directly in the packet:** That would require `com.forgebook.network.packet.*` to `import com.forgebook.client.session.ClientChatSession`. The classloader then materializes `ClientChatSession`, which transitively imports `com.forgebook.client.ui.MessageBubble`/`ErrorCard`. These value types themselves might not directly import `net.minecraft.*`, but the bigger concern is defense-in-depth: routing through a neutral volatile-sink keeps the seam obvious for CI and future maintainers.

---

### Modifications to `ClientSetup.java`

**Current state:** `ClientSetup.java:14-22` is a one-line stub.

**Modification pattern** (shape copied from `ForgeBookMod.java:40-92` — constructor wires multiple listener registrations):
```java
package com.forgebook.client;

import com.forgebook.client.session.ClientChatSession;
import com.forgebook.network.client.ClientPacketSinks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ClientSetup {
    private static final Logger LOG = LogManager.getLogger();
    private ClientSetup() {}

    public static void init() {
        // @Mod.EventBusSubscriber on InventoryButtonInjector and SessionLifecycleListener
        // auto-registers them at class load. Class loading happens inside
        // DistExecutor.safeRunWhenOn(Dist.CLIENT, ...) (ForgeBookMod:89) so the classes
        // are never loaded on a dedicated server.

        // Wire the volatile sinks so server→client packet handlers can reach
        // the client-side session without importing com.forgebook.client.* from
        // com.forgebook.network.packet.*. See Pitfall 7.
        ClientPacketSinks.replySink = (id, text) ->
            ClientChatSession.get().append(id, text);
        ClientPacketSinks.errorSink = (id, code, msg) ->
            ClientChatSession.get().appendError(id, code, msg);

        LOG.info("ForgeBook client initialized (Phase 4 UI sinks installed).");
    }
}
```

**Key invariants:**
- Do NOT add `MinecraftForge.EVENT_BUS.register(...)` imperative calls for the two `@EventBusSubscriber`-annotated classes — annotation does the work.
- Do NOT reference `InventoryButtonInjector.class` / `SessionLifecycleListener.class` (no `.class` force-load needed; Forge's classpath scan finds them).

---

### `ChatPanelWidget.java`, `MessageBubble.java`, `ErrorCard.java`, `LoadingIndicator.java`

No direct in-repo analog (first vanilla-widget subclasses / bubble value-types in the project). Planner MUST fall back to the RESEARCH document:

- `ChatPanelWidget` — RESEARCH §"Scroll Strategy" (lines 278-290) + §"Text Wrapping" (lines 292-306). Extend `AbstractWidget`; override `renderWidget` + `updateWidgetNarration`.
- `MessageBubble` + `ErrorCard` — declared in UI-SPEC §"Implementation Artifacts" as "value type, not a widget". Implement as Java records following the `RateLimiter.Allowed` / `RateLimiter.Limited` record-variant style (`RateLimiter.java:41-50`):
  ```java
  public sealed interface ChatEntry permits MessageBubble, ErrorCard {}
  public record MessageBubble(Kind kind, String text) implements ChatEntry {
      public enum Kind { USER, ASSISTANT }
      public static MessageBubble user(String text) { return new MessageBubble(Kind.USER, text); }
      public static MessageBubble assistant(String text) { return new MessageBubble(Kind.ASSISTANT, text); }
  }
  public record ErrorCard(ErrorCode code, String humanReadable) implements ChatEntry {}
  ```
  **Rationale:** the `sealed interface ChatEntry` mirrors `Authorizer.Result` (`Authorizer.java:48`) — enables exhaustive instanceof handling in the render loop.
- `LoadingIndicator` — pure tick-counter modulo. Model after `TokenBucket.java:21-46` for shape (small stateful helper, package-private, single public method).

For `MessageBubbleWrapMathTest`, extract a pure `computeBubbleHeight(int maxWidth, List<Object> splitLines, int paddingTop, int paddingBottom, int lineHeight)` static helper on `MessageBubble` that takes the already-wrapped lines. Tests pass a canned `List` without invoking `Font.split`. This mirrors the `ItemSubcommand.DEFAULT_RESOURCE_LOOKUP` lambda-seam pattern (`ItemSubcommand.java:138-149`).

---

### Test Files — uniform pattern

**Analog for all 5 test files:** `src/test/java/com/forgebook/safety/RateLimiterTest.java` (pure-function test) + `src/test/java/com/forgebook/command/ItemSubcommandTest.java` (lambda-seam test with `@BeforeEach`/`@AfterEach` and `MockedStatic`).

**Imports pattern** (copy from `RateLimiterTest.java:1-6` for pure-function tests; from `ItemSubcommandTest.java:1-58` for seam tests):
```java
package com.forgebook.client.ui;       // or com.forgebook.client.session

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
// Plus for stateful tests:
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
```

**Zero Minecraft imports** in `ChatPanelLayoutTest`, `InventoryButtonGeometryTest`, `ErrorCodeColorMapTest`, `ClientChatSessionTest` — these are pure-data tests.

**`MessageBubbleWrapMathTest` MAY import `ErrorCode` and `ChatErrorPacket` only** (those are in `com.forgebook.network.packet.*`, not `net.minecraft.*`). No `net.minecraft.client.gui.Font` import — the `Font.split` call is abstracted behind a lambda seam (`Function<String, List<Object>>` or a dedicated `SplitFn`).

**`ErrorCodeColorMapTest` pattern** (structure copied from `AuthorizerTest.java:36-80`):
```java
@Test
void everyErrorCode_hasNonNullColor() {
    for (ChatErrorPacket.ErrorCode code : ChatErrorPacket.ErrorCode.values()) {
        int argb = ErrorCard.stripeColor(code);   // static lookup helper on ErrorCard
        assertNotEquals(0, argb, "ErrorCode " + code + " must have a non-zero ARGB color");
    }
}
```
Then 6 `@Test` methods, one per ErrorCode, asserting the exact UI-SPEC stripe color.

**`ClientChatSessionTest` pattern** (structure copied from `RateLimiterTest.java:10-65`):
- `@BeforeEach` resets `ClientChatSession.get().clear()` (NOT a new instance — it's a singleton; clear() is idempotent).
- One `@Test` per state-machine transition: idle→pending, pending→idle (on `append`), pending→idle (on `appendError`), clear-during-pending, stale-requestId-append-no-op.

---

## Shared Patterns

### Package Firewall Compliance (SCAF-02 + UI-08)

**Source:** `.github/workflows/build.yml` lines 31-45 (existing CI grep), plus RESEARCH Pitfall 7.

**Apply to:** Every new file in Phase 4. Rules:

1. `net.minecraft.client.*` imports are permitted ONLY in files under `com.forgebook.client.*` (existing SCAF-02 rule).
2. Files under `com.forgebook.client.ui.*` and `com.forgebook.client.session.*` MUST NOT import:
   - `com.forgebook.ai.*`
   - `com.forgebook.config.ApiKey`
   - `com.forgebook.safety.*` (except `com.forgebook.network.packet.ChatErrorPacket.ErrorCode` which is in `network.packet`, not `safety`)
3. `com.forgebook.network.packet.*` must NOT import `com.forgebook.client.*` (sink-indirection through `com.forgebook.network.client.ClientPacketSinks`).

**New CI grep rule** (Phase 4 addition per RESEARCH §"Existing package firewall (SCAF-02)"):
```bash
# UI-08 reverse-direction grep — add to .github/workflows/build.yml
HITS=$(grep -rnE --include='*.java' \
    'import com\.forgebook\.(ai|safety)\.|import com\.forgebook\.config\.ApiKey' \
    src/main/java/com/forgebook/client/ui/ \
    src/main/java/com/forgebook/client/session/ || true)
if [ -n "$HITS" ]; then
    echo "UI-08 violation: com.forgebook.client.{ui,session} imports forbidden package:"
    echo "$HITS"
    exit 1
fi
```

### Volatile-Holder Singleton Pattern

**Source:** `src/main/java/com/forgebook/safety/KillSwitch.java`, `src/main/java/com/forgebook/config/ConfigHolder.java`, `src/main/java/com/forgebook/safety/RateLimiterHolder.java`.

**Apply to:** `ClientChatSession`, `ClientPacketSinks`.

**Shape invariants:**
- `private` constructor.
- `final` class.
- Volatile or AtomicReference for swappable state; plain fields inside `synchronized` methods for complex state.
- `get()` returns the current instance/value; `set()` or `swap()` mutates. No getters for individual fields; use snapshot methods.

### Pure-Function Test Seam Pattern (`*Internal`)

**Source:** `src/main/java/com/forgebook/safety/Authorizer.java:75-105`, `src/main/java/com/forgebook/command/ItemSubcommand.java:177-242`, `src/main/java/com/forgebook/ai/RagItemPipeline.java:144-258`.

**Apply to:** Any file whose public method touches `net.minecraft.*` types. Exception-by-design: `ChatScreen.java` itself does NOT need an `*Internal` seam because its public methods are overrides of `Screen` lifecycle methods (only invokable by Forge). Its dependencies (`ClientChatSession`, `ChatPanelLayout`, `InventoryButtonGeometry`) are the unit-testable pure-Java seams.

**Shape:**
- Public entry unpacks `ServerPlayer` / `CommandSourceStack` / `ItemStack` / `Screen` into primitives + lambdas.
- Package-private `*Internal` method takes the primitives and lambdas. All logic lives here.
- Tests import only the package-private method; never construct a Minecraft type.

### Logger Convention

**Source:** Every production class with logging — `ForgeBookMod.java:32`, `ForgebookCommands.java:34`, `ItemSubcommand.java:90`, `ChatRequestHandler.java:71`, etc.

**Apply to:** `InventoryButtonInjector`, `ClientSetup` (already has one), `ChatScreen` (may elide logger — Screen lifecycle doesn't need logging in v1).

**Shape:**
```java
private static final Logger LOG = LogManager.getLogger();
```
**NOT** `LoggerFactory.getLogger(ClassName.class)` (slf4j) — the project uses Log4j2's `LogManager` directly, default (class-name) logger.

### i18n via `Component.translatable(...)`

**Source:** `ItemSubcommand` (wraps error strings in `Component.literal`, BUT that's deliberate for user-facing command error text that doesn't need i18n currently). The "new" pattern for Phase 4 is `Component.translatable("forgebook.chat.button.tooltip")` per UI-SPEC §"Copywriting Contract".

**Apply to:** Every user-facing string in `ChatScreen`, `InventoryButtonInjector`, `ChatPanelWidget`, `ErrorCard`, `LoadingIndicator`.

**en_us.json shape** (NEW file at `src/main/resources/assets/forgebook/lang/en_us.json`):
```json
{
  "forgebook.chat.button.tooltip": "Ask ForgeBook",
  "forgebook.chat.button.narration": "Open ForgeBook chat",
  "forgebook.chat.title": "ForgeBook",
  "forgebook.chat.empty.body": "Ask about a mod, an item, or what you're looking at.",
  "forgebook.chat.input.placeholder": "Type your question…",
  "forgebook.chat.submit": "Ask",
  "forgebook.chat.loading": "Thinking…",
  "forgebook.chat.screen_too_small": "Screen too small — increase resolution or lower GUI scale.",
  "forgebook.error.transport.heading": "Connection hiccup",
  "forgebook.error.transport.body": "The server couldn't reach the AI service. Try again in a moment.",
  "forgebook.error.rate_limited.heading": "Slow down a sec",
  "forgebook.error.rate_limited.body": "You're sending questions too fast. Try again in %ds.",
  "forgebook.error.forbidden.heading": "Not allowed",
  "forgebook.error.forbidden.body": "ForgeBook is OP-only on this server. Ask an operator to enable it for you.",
  "forgebook.error.provider.heading": "AI service error",
  "forgebook.error.provider.body": "The AI service returned an error. The server owner may need to check the logs.",
  "forgebook.error.disabled.heading": "ForgeBook is turned off",
  "forgebook.error.disabled.body": "A server operator has disabled ForgeBook. Try again later.",
  "forgebook.error.overloaded.heading": "Server busy",
  "forgebook.error.overloaded.body": "Too many questions in flight. Try again in a moment.",
  "forgebook.error.no_server.body": "ForgeBook isn't installed on this server."
}
```
(Matches UI-SPEC §"Copywriting Contract" table 1-to-1; 21 keys; UI-SPEC quotes "18 i18n keys" but the actual count across headings+bodies+incidentals is 21.)

**Apply-everywhere convention:** Never hardcode English strings in `ChatScreen.render()` or `InventoryButtonInjector.onScreenInit()`. Use `Component.translatable(key)` and, where formatting is needed, `Component.translatable(key, arg)` (for `%d` substitution in `rate_limited.body`).

### Sealed Interface + Record Variants (for value types)

**Source:** `src/main/java/com/forgebook/safety/RateLimiter.java:41-50` (Outcome permits Allowed, Limited) + `src/main/java/com/forgebook/safety/Authorizer.java:48-54` (Result permits Allowed, Denied).

**Apply to:** The `ChatEntry` sealed interface with `MessageBubble` and `ErrorCard` record permits — enables exhaustive `instanceof` pattern-matching in the render loop. See §"`ChatPanelWidget.java` etc." above for the concrete snippet.

---

## No Analog Found

Files with no close in-repo match; planner MUST defer to RESEARCH.md + Forge docs:

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `src/main/java/com/forgebook/client/ui/ChatPanelWidget.java` | widget (AbstractWidget subclass) | streaming | No existing `AbstractWidget` subclass in the project. RESEARCH §"Scroll Strategy" provides the concrete math sketch. Forge docs §"Common Widgets" provides the `renderWidget(...)` + `updateWidgetNarration(...)` override pattern. |
| `src/main/java/com/forgebook/client/ui/ChatScreen.java` — specifically the Screen-lifecycle overrides | screen | request-response | No existing `Screen` subclass. RESEARCH §"Screen Architecture Resolution" lines 160-232 provide a complete verbatim sketch. |
| `src/main/java/com/forgebook/client/ui/LoadingIndicator.java` | subcomponent | streaming (time-based) | Trivial — tick counter. Shape follows `TokenBucket.java` but there's no real precedent for GUI-tick math. |

For these files, the planner should paste the RESEARCH code sketches directly into the plan's action section rather than reference an in-repo analog.

---

## Metadata

**Analog search scope:**
- `src/main/java/com/forgebook/` — 64 production `.java` files
- `src/test/java/com/forgebook/` — 52 test `.java` files
- `src/main/resources/` — `mods.toml`, `pack.mcmeta`, `log4j2.xml` (no existing `assets/forgebook/lang/`)
- `.github/workflows/build.yml` — CI lint rules that new files must pass

**Files scanned in detail:**
- `ForgeBookMod.java` (entry-point + DistExecutor + event-bus wiring pattern)
- `ClientSetup.java` (current stub to extend)
- `ForgebookNetwork.java` + `ChatRequestPacket.java` + `ChatResponsePacket.java` + `ChatErrorPacket.java` (packet handler modifications)
- `ChatRequestHandler.java` (volatile-sink pattern anchor at line 82)
- `ForgebookCommands.java` + `ItemSubcommand.java` + `AdminSubcommands.java` + `RagItemPipeline.java` (`*Internal` test-seam pattern anchors)
- `Authorizer.java` + `RateLimiter.java` + `TokenBucket.java` + `KillSwitch.java` + `RateLimiterHolder.java` (holder + sealed-interface + pure-function-seam patterns)
- `ConfigHolder.java` + `ForgebookClientConfig.java` (config access pattern)
- `RequestAuditLogger.java` + `ApiKeyScrubFilter.java` (logger + scrubbing conventions)
- `RateLimiterTest.java` + `AuthorizerTest.java` + `ItemSubcommandTest.java` (test-shape anchors)
- Existing `01-PATTERNS.md` (format precedent)

**Pattern extraction date:** 2026-04-16
