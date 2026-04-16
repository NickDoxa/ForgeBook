---
phase: 04-in-inventory-chat-ui
plan: 05
subsystem: ui
tags: [ui, screen, injector, i18n, ci-firewall, wiring, forge-1.20.1]

# Dependency graph
requires:
  - phase: 04-in-inventory-chat-ui plan 01
    provides: ChatEntry / MessageBubble / ErrorCard / LoadingIndicator value types (transitively via ChatPanelWidget)
  - phase: 04-in-inventory-chat-ui plan 02
    provides: ChatPanelLayout.compute (Screen layout), InventoryButtonGeometry.compute (button placement), ClientChatSession (submit flow, clear-on-close)
  - phase: 04-in-inventory-chat-ui plan 03
    provides: ClientPacketSinks.replySink / errorSink fields (installed by ClientSetup.init)
  - phase: 04-in-inventory-chat-ui plan 04
    provides: ChatPanelWidget (embedded in ChatScreen.init via addRenderableWidget)
  - phase: 01-foundations
    provides: ForgebookNetwork.CHANNEL (sendToServer), ChatRequestPacket (UUID + msg record), ForgebookClientConfig.ENABLE_CHAT_INTERFACE
provides:
  - ChatScreen — the central standalone Screen (EditBox + submit Button + embedded ChatPanelWidget, parent-render trick, clear-before-setScreen, isPauseScreen=false, ESC/ENTER key handlers, try/catch on sendToServer)
  - InventoryButtonInjector — @Mod.EventBusSubscriber(Bus.MOD, Dist.CLIENT) handler for ScreenEvent.Init.Post that gates on ENABLE_CHAT_INTERFACE per-fire and opens ChatScreen on click
  - en_us.json — the full 21-key i18n lock for Phase 4 copy (button tooltip/narration, title, empty body, input placeholder, submit, loading, screen_too_small, 6 × error heading + body, no_server body)
  - ClientSetup.init extension — installs replySink + errorSink lambdas pointing at ClientChatSession.append / .appendError
  - .github/workflows/build.yml UI-08 reverse firewall — fails PRs that add com.forgebook.ai.* / com.forgebook.safety.* / com.forgebook.config.ApiKey imports under com.forgebook.client.{ui,session}
affects: [04-06 live-smoke]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Parent-render + INT_MAX mouse-coord trick: standalone Screen over InventoryScreen; parent redraws below us with mouse=MAX so isMouseOver never fires — keeps inventory visible without vanilla slot tooltips bleeding through."
    - "parent.init(minecraft, width, height) forward-call on chatScreen.init — Mojang's own 'back button' screen idiom; lets the parent resize its widgets at our window dimensions."
    - "@Mod.EventBusSubscriber(bus = Bus.MOD, value = Dist.CLIENT) + explicit bus — ScreenEvent is mod-bus; the explicit bus prevents the 'event never fires' class of bug CLAUDE.md §\"What NOT to Use\" calls out."
    - "ENABLE_CHAT_INTERFACE.get() re-read per event fire (never cached into a class constant) — runtime config toggling takes effect on next inventory open."
    - "CI reverse-firewall grep (UI-08): complements SCAF-02 forward rule by forbidding the opposite direction — chat UI files cannot transitively reach secret types."

key-files:
  created:
    - src/main/java/com/forgebook/client/ui/ChatScreen.java
    - src/main/java/com/forgebook/client/ui/InventoryButtonInjector.java
    - src/main/resources/assets/forgebook/lang/en_us.json
  modified:
    - src/main/java/com/forgebook/client/ClientSetup.java
    - .github/workflows/build.yml

key-decisions:
  - "INPUT_MAX_LENGTH=512 in ChatScreen: UI-SPEC didn't pin a number; 512 leaves comfortable headroom below the ChatRequestPacket 32 000-byte wire cap while being tight enough that a runaway paste into the EditBox can't lock the input."
  - "try/catch around CHANNEL.sendToServer in onSubmitClicked — if the channel is unavailable (vanilla server, LAN race), surface a TRANSPORT error card carrying the 'ForgeBook isn't installed on this server.' copy via the session itself; no raw Throwable reaches the render layer."
  - "Per-frame recheck of ClientChatSession.isPending() in render() to flip input.setEditable + submitBtn.active — simpler than a separate state observer and trivially cheap (one volatile read per frame)."
  - "21 i18n keys, not 22: UI-SPEC lists a 22nd 'session-ended notice' row with no copy (session clears silently per UI-05) so there is no key to emit — plan 04-05 locks the key count at 21."
  - "Ellipses and em-dashes encoded as \\u2026 and \\u2014 escapes inside en_us.json rather than literal UTF-8 characters — robust across Windows/Unix line-ending conversions and editors that default to latin-1."
  - "ClientSetup references the sinks + session via fully-qualified names (no new import lines) — keeps the existing import list stable and surfaces the package relationship at the call site."

patterns-established:
  - "Standalone-Screen-over-parent composition: new Screen holds 'parent' field, re-invokes parent.init(...) in its own init, calls parent.render(g, MAX, MAX, partialTick) first in its own render, restores via setScreen(parent) in onClose. Reusable for any future 'modal-ish' Screen the mod ships."
  - "CI-as-architecture-lock: the UI-08 reverse grep pairs with SCAF-02's forward grep to make the client-secret firewall fully bidirectional. Future subsystems that need similar guarantees (e.g. 'safety rules never imported from command package') can clone the pattern."

requirements-completed: [UI-01, UI-02, UI-03, UI-05, UI-06, UI-08]

# Metrics
duration: 8min
completed: 2026-04-16
---

# Phase 04 Plan 05: Chat UI Final Assembly Summary

**The centerpiece assembly — ChatScreen (standalone Screen over InventoryScreen with parent-render trick, clear-before-setScreen, submit flow through ForgebookNetwork), InventoryButtonInjector (Bus.MOD Dist.CLIENT gated on ENABLE_CHAT_INTERFACE per-fire), 21-key en_us.json, ClientSetup sink installation, and the UI-08 reverse-firewall CI grep — all four tasks green with zero deviations in Tasks 1, 3, 4 and one Rule-3 blocking fix in Task 2.**

## Performance

- **Duration:** ~8 min
- **Started:** 2026-04-16T21:02:50Z
- **Completed:** 2026-04-16T21:11:05Z
- **Tasks:** 4 (all auto, non-TDD — Screen rendering deferred to plan 04-06 live smoke per RESEARCH §"What CANNOT be unit-tested without booting Minecraft")
- **Files created:** 3 (ChatScreen.java + InventoryButtonInjector.java + en_us.json)
- **Files modified:** 2 (ClientSetup.java + .github/workflows/build.yml)

## Accomplishments

- **ChatScreen is the centerpiece.** 220 LOC, extends `net.minecraft.client.gui.screens.Screen` (NOT `AbstractContainerScreen` — JEI/REI compat per UI-SPEC §"ChatScreen Layout"). `init()` calls `parent.init(minecraft, width, height)` first so the InventoryScreen's widgets are re-sized at our window dimensions, then embeds a `ChatPanelWidget` for the top portion and lays out an `EditBox` + "Ask" `Button` in the input row. `render()` draws the parent with `Integer.MAX_VALUE` mouse coords (Pitfall 11), then our dim overlay, then our widgets, then per-frame toggles `input.setEditable(!pending)` and `submitBtn.active` based on `ClientChatSession.isPending()`. `isPauseScreen()` returns `false` (Pitfall 8). `onClose()` calls `ClientChatSession.clear()` FIRST, then `setScreen(parent)` (Pitfall 9). `keyPressed` handles ESC (close) and ENTER (submit when focused, non-blank, not pending). `onSubmitClicked` mints a UUID, optimistically appends the user bubble, marks pending, clears the EditBox, calls `panel.scrollToBottom()`, and sends via `ForgebookNetwork.CHANNEL.sendToServer(new ChatRequestPacket(reqId, msg))` wrapped in a try/catch that surfaces a TRANSPORT `forgebook.error.no_server.body` card if the channel is unreachable.
- **InventoryButtonInjector is the sole discoverable entry point.** 80 LOC, `@Mod.EventBusSubscriber(modid = "forgebook", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)` auto-registers at class load. On every `ScreenEvent.Init.Post` fire it re-reads `ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()` (Pitfall 6 — never cached), filters to `InventoryScreen` via `instanceof inv` pattern, delegates geometry to `InventoryButtonGeometry.compute(inv.getGuiLeft(), inv.getGuiTop(), inv.getXSize())` (plan 04-02 pure function), builds a `Button` with `Component.literal("F")` label + `Tooltip.create(Component.translatable("forgebook.chat.button.tooltip"))`, and attaches via `event.addListener(btn)`. Click opens `new ChatScreen(inv)`.
- **en_us.json locks every i18n key.** Exactly 21 keys, verified by `grep -cE '^\s*"forgebook\.' en_us.json → 21`. Covers all UI-SPEC §"Copywriting Contract" rows: button tooltip + narration, chat title, empty body, input placeholder, submit label, loading caption, screen_too_small notice, 6 × (error heading + body) for TRANSPORT/RATE_LIMITED/FORBIDDEN/PROVIDER/DISABLED/OVERLOADED, plus `forgebook.error.no_server.body`. Ellipses and em-dashes are encoded as `\u2026` and `\u2014` escapes for robust transport across editors.
- **ClientSetup.init installs both sinks without new imports.** Fully-qualified `com.forgebook.network.client.ClientPacketSinks.replySink = (id, text) -> com.forgebook.client.session.ClientChatSession.get().append(id, text);` (and the symmetrical errorSink). No imperative `MinecraftForge.EVENT_BUS.register` calls — `InventoryButtonInjector` (Bus.MOD) and `SessionLifecycleListener` (Bus.FORGE) auto-register at class load, which happens inside `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)` from `ForgeBookMod` so the classes stay off dedicated servers.
- **UI-08 reverse-firewall grep now lives in CI.** `.github/workflows/build.yml` gained a step between "ApiKey.raw() caller lint" and "Build" that fails the job if any `.java` file under `src/main/java/com/forgebook/client/ui/` or `src/main/java/com/forgebook/client/session/` imports `com.forgebook.ai.*`, `com.forgebook.safety.*`, or `com.forgebook.config.ApiKey`. SCAF-02 forward rule and ApiKey.raw() lint are both preserved unchanged. Local dry-run across plans 04-01..04-05 outputs: zero violations.
- **All Phase-4 requirements satisfied at code + CI tier.** UI-01 (button injection), UI-02 (ChatScreen opens), UI-03 (text-glyph + vanilla palette, no new textures), UI-05 (session clear on close + logout), UI-06 (ENABLE_CHAT_INTERFACE per-fire gate), UI-08 (package firewall enforced at CI) — all land with this plan. UI-04 (rendering surface) completed with plan 04-04; UI-07 (scale-aware layout) via plan 04-02's `ChatPanelLayout.compute`. Only remaining item is the live smoke test in plan 04-06.

## Task Commits

| # | Task                                                                                    | Commit    |
| - | --------------------------------------------------------------------------------------- | --------- |
| 1 | ChatScreen (standalone Screen with EditBox, submit Button, ChatPanelWidget, ESC/ENTER)  | `fa9e11f` |
| 2 | InventoryButtonInjector (ScreenEvent.Init.Post @ Bus.MOD Dist.CLIENT)                   | `611026e` |
| 3 | en_us.json with 21 i18n keys + ClientSetup.init sink installation                       | `50c39bb` |
| 4 | UI-08 reverse-direction firewall grep in .github/workflows/build.yml                    | `38f6d4b` |

All commits use `--no-verify` per parallel-worktree executor protocol.

## Files Created

- `src/main/java/com/forgebook/client/ui/ChatScreen.java` — Standalone `Screen` subclass with parent-render trick, scale-aware layout delegation (`ChatPanelLayout.compute`), embedded `ChatPanelWidget`, `EditBox` (maxLength=512) + "Ask" `Button`, ESC/ENTER key handlers, `ClientChatSession` submit flow, try/catch on `sendToServer`, `isPauseScreen()=false`, `onClose` clear-before-setScreen.
- `src/main/java/com/forgebook/client/ui/InventoryButtonInjector.java` — `@Mod.EventBusSubscriber(Bus.MOD, Dist.CLIENT)` static handler for `ScreenEvent.Init.Post`; gates on `ENABLE_CHAT_INTERFACE.get()` per fire; filters to `InventoryScreen`; builds a 20×20 "F" button with i18n tooltip; click opens `new ChatScreen(inv)`.
- `src/main/resources/assets/forgebook/lang/en_us.json` — 21-key i18n lock covering every UI-SPEC §"Copywriting Contract" row. Valid JSON (resource processing in `./gradlew build` succeeds). Ellipses + em-dashes as `\u2026` / `\u2014`.

## Files Modified

- `src/main/java/com/forgebook/client/ClientSetup.java` — `init()` body rewritten: installs `ClientPacketSinks.replySink` + `errorSink` lambdas pointing at `ClientChatSession.append` / `.appendError`; comment block explains why `@Mod.EventBusSubscriber` classes auto-register without imperative calls; `LOG.info` message updated to reflect Phase 4 wiring. Existing `private static final Logger LOG` + private constructor preserved. Imports unchanged — sinks + session reached via fully-qualified names.
- `.github/workflows/build.yml` — New step "UI-08 reverse firewall - no forbidden imports in com.forgebook.client.{ui,session}" inserted between "ApiKey.raw() caller lint" and "Build". Greps for `import com.forgebook.(ai|safety).` or `import com.forgebook.config.ApiKey` under the two client subpackages; on any hit prints "UI-08 violation:" and fails the job. SCAF-02 + ApiKey.raw() rules preserved.

## Acceptance-Criteria Grep Evidence

```
# Task 1 (ChatScreen)
test -f src/main/java/com/forgebook/client/ui/ChatScreen.java                                              → YES ✓
grep -c "public class ChatScreen extends Screen"                                                            → 1   ✓
grep -c "public boolean isPauseScreen() {"                                                                  → 1   ✓
grep -c "ClientChatSession.get().clear();"                                                                  → 1   ✓
grep -c "Integer.MAX_VALUE, Integer.MAX_VALUE, partialTick"                                                 → 1   ✓
grep -c "ForgebookNetwork.CHANNEL.sendToServer(new ChatRequestPacket(reqId, msg))"                          → 1   ✓
grep -c "ChatPanelLayout.compute"                                                                           → 2   ✓ (init + render)
grep -c "new ChatPanelWidget"                                                                               → 1   ✓
grep -c "GLFW.GLFW_KEY_ESCAPE"                                                                              → 1   ✓
grep -c "GLFW.GLFW_KEY_ENTER"                                                                               → 1   ✓
grep -c "parent.init(this.minecraft, this.width, this.height)"                                              → 1   ✓
grep -c "setInitialFocus"                                                                                   → 1   ✓
grep -cE "import com\\.forgebook\\.(ai|safety)\\.|import com\\.forgebook\\.config\\.ApiKey"                 → 0   ✓

# Task 2 (InventoryButtonInjector)
grep -c "@Mod.EventBusSubscriber(modid = \"forgebook\", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)" → 1 ✓
grep -c "public static void onScreenInit(ScreenEvent.Init.Post event)"                                      → 1   ✓
grep -c "ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()"                                                 → 1   ✓
grep -c "event.getScreen() instanceof InventoryScreen inv"                                                  → 1   ✓
grep -c "InventoryButtonGeometry.compute"                                                                   → 1   ✓
grep -c "new ChatScreen(inv)"                                                                               → 1   ✓
grep -c "Component.translatable(\"forgebook.chat.button.tooltip\")"                                         → 1   ✓
grep -c "event.addListener(btn)"                                                                            → 1   ✓
grep -cE "import com\\.forgebook\\.(ai|safety)\\.|import com\\.forgebook\\.config\\.ApiKey"                 → 0   ✓
grep -c "private static final boolean"                                                                      → 0   ✓ (after javadoc rewrite)

# Task 3a (en_us.json)
grep -cE '^\s*"forgebook\.' en_us.json                                                                      → 21  ✓
grep -c "forgebook.chat.button.tooltip"                                                                     → 1   ✓
grep -c "forgebook.chat.title"                                                                              → 1   ✓
grep -c "forgebook.chat.input.placeholder"                                                                  → 1   ✓
grep -c "forgebook.chat.submit"                                                                             → 1   ✓
grep -c "forgebook.chat.loading"                                                                            → 1   ✓
grep -c "forgebook.chat.screen_too_small"                                                                   → 1   ✓
grep -cE "forgebook\\.error\\.(transport|rate_limited|forbidden|provider|disabled|overloaded)\\.heading"    → 6   ✓
grep -cE "forgebook\\.error\\.(transport|rate_limited|forbidden|provider|disabled|overloaded)\\.body"       → 6   ✓
grep -c "forgebook.error.no_server.body"                                                                    → 1   ✓

# Task 3b (ClientSetup)
grep -c "ClientPacketSinks.replySink"                                                                       → 1   ✓
grep -c "ClientPacketSinks.errorSink"                                                                       → 1   ✓
grep -c "ClientChatSession.get().append(id, text)"                                                          → 1   ✓
grep -c "ClientChatSession.get().appendError(id, code, msg)"                                                → 1   ✓
grep -c "MinecraftForge.EVENT_BUS.register"                                                                 → 0   ✓

# Task 4 (build.yml)
grep -c "UI-08 reverse firewall"                                                                            → 1   ✓
grep -c "UI-08 violation"                                                                                   → 1   ✓
grep -c "src/main/java/com/forgebook/client/ui/"                                                            → 1   ✓
grep -c "src/main/java/com/forgebook/client/session/"                                                       → 1   ✓
grep -c "Firewall lint - no net.minecraft.client"  (SCAF-02 preserved)                                      → 1   ✓
grep -c "ApiKey.raw() caller lint"  (preserved)                                                             → 1   ✓
UI-08 local dry-run across plans 04-01..04-05                                                               → CLEAN ✓

# Plan-level
./gradlew --no-daemon build                                                                                 → BUILD SUCCESSFUL ✓
./gradlew --no-daemon test                                                                                  → BUILD SUCCESSFUL ✓ (all existing tests green)
```

## Decisions Made

- **INPUT_MAX_LENGTH=512 on the EditBox.** UI-SPEC's "Interaction Contract" doesn't pin a number; picking 512 leaves comfortable headroom below the `ChatRequestPacket` 32 000-byte wire cap while keeping the input reasonably bounded for a single question. A paste-attack runaway still fits in the wire envelope; nothing downstream needs to truncate.
- **Try/catch around `sendToServer` surfaces a TRANSPORT error card.** If `CHANNEL.sendToServer` ever throws (e.g., vanilla server with no ForgeBook installed, LAN race at join), the UI surfaces a `TRANSPORT` error card carrying the `forgebook.error.no_server.body` copy via `ClientChatSession.appendError`. No raw `Throwable` reaches the render layer. Mirrors UI-SPEC §"Client Vanilla Server Detection".
- **Per-frame recheck of `isPending()`.** `render()` reads `ClientChatSession.get().isPending()` once per frame and toggles `input.setEditable` + `submitBtn.active` accordingly. Simpler than a separate state observer, cheap (one volatile read), and keeps the "disable while pending" semantics automatic — when `markIdle` / `append` / `appendError` flips the flag, the next frame re-enables the input.
- **21 keys in en_us.json, not 22.** UI-SPEC §"Copywriting Contract" lists a 22nd "session-ended notice" row with `—` (no copy); the session clears silently per UI-05 so there is no key to emit. Plan 04-05 locks the key count at 21 and drops the placeholder row. A hypothetical 22nd `forgebook.chat.input.maxLength` key was considered (for future EditBox truncation UX) but also rejected — UI-SPEC is silent and EditBox truncation is handled at the widget level without copy.
- **`\u2026` and `\u2014` escapes for ellipses + em-dashes in en_us.json.** Defensive against editor / line-ending conversion damage. Minecraft's i18n system decodes the escapes at load time so the player still sees native `…` and `—` glyphs.
- **`ClientSetup` reaches sinks + session via fully-qualified names.** Avoids modifying the import list and keeps the sink-install pattern adjacent to the session it targets. A future reader grepping for `ClientPacketSinks` or `ClientChatSession` finds every call site at the point of use.
- **No imperative event-bus registration in `ClientSetup.init`.** Both `InventoryButtonInjector` (Bus.MOD) and `SessionLifecycleListener` (Bus.FORGE) auto-register at class load via `@Mod.EventBusSubscriber`; class loading is triggered by `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)` being called from `ForgeBookMod`. Adding imperative `MinecraftForge.EVENT_BUS.register(...)` would duplicate the subscription and would be an anti-pattern per RESEARCH §"ClientSetup.init() wiring" (lines 634-635).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Javadoc matched the `private static final boolean` acceptance-grep**

- **Found during:** Task 2 verification.
- **Issue:** The Task 2 acceptance criterion `grep -c "private static final boolean" InventoryButtonInjector.java → 0` is intended to verify the absence of a cached-config anti-pattern. My initial javadoc on `InventoryButtonInjector` explained the anti-pattern by quoting `{@code private static final boolean}` inline, which matched the grep and returned 1 instead of 0. Same class of issue plan 04-04 encountered with its `MessageBubble.computeBubbleHeight` javadoc links.
- **Fix:** Rewrote the javadoc prose to say "caching the value into a class constant would freeze the toggle at class-load time" — the anti-pattern is still explained, just without the literal forbidden substring. No semantic change; all other acceptance greps pass unchanged.
- **Files modified:** `src/main/java/com/forgebook/client/ui/InventoryButtonInjector.java`
- **Verification:** `grep -c "private static final boolean" InventoryButtonInjector.java → 0`; only `private static final Logger LOG` remains (which correctly does NOT match the `boolean` pattern).
- **Committed in:** `611026e` (edit happened before the Task 2 commit, so the fix is folded into the single feat commit rather than a separate one).

---

**Total deviations:** 1 auto-fixed (Rule 3 blocking — acceptance-grep compliance). Zero architectural or design changes; zero modifications to plan intent. Tasks 1, 3, and 4 executed exactly as written in `04-05-PLAN.md`.

## Issues Encountered

- **Pre-hook READ-BEFORE-EDIT nag on files already read in session.** The harness's `PreToolUse:Edit` / `PreToolUse:Write` hook fired reminders on two files (`InventoryButtonInjector.java` after initial Write, `ClientSetup.java` and `.github/workflows/build.yml` on modification) despite those files having been Read earlier in the session. The tool calls still succeeded — the reminders were advisory. No impact on deliverables. This matches the same environmental note plan 04-04 logged.
- **Python not available for JSON validation.** Attempted `python -c "import json..."` as a belt-and-suspenders JSON syntax check against `en_us.json`; neither `python` nor `python3` is on PATH in the Windows bash environment. Mitigated by `./gradlew build` succeeding — Gradle's `processResources` task runs the same parse path the game uses and would fail on malformed JSON.

## Threat Model Status

| Threat ID   | Mitigation delivered                                                                                                          |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------- |
| T-04-05-01  | ✓ InventoryButtonInjector gates only on CLIENT `ENABLE_CHAT_INTERFACE` — NOT on OP status; server-side `Authorizer` remains authoritative. |
| T-04-05-02  | ✓ UI-08 reverse-firewall grep live in `.github/workflows/build.yml`; local dry-run across plans 04-01..04-05 clean.           |
| T-04-05-03  | ✓ Inherited from plan 04-04 — scissors + int arithmetic + 32 KB wire cap bound panel content height.                          |
| T-04-05-04  | ✓ `ChatScreen.onClose()` calls `ClientChatSession.clear()` BEFORE `setScreen(parent)` (Pitfall 9); stale-response guard from plan 04-02 handles late replies. |
| T-04-05-05  | ✓ Inherited from plan 04-04 — `scrollAmount` is a `double`, bounded by per-packet caps × one-in-flight policy.                |
| T-04-05-06  | ✓ All 21 i18n keys locked in en_us.json; acceptance-grep on each key returns 1; plan 04-06 live smoke verifies resolved strings visually. |
| T-04-05-07  | ✓ `InventoryButtonInjector` explicit `Bus.MOD`; `SessionLifecycleListener` (plan 04-03) explicit `Bus.FORGE`; acceptance greps verify both. |
| T-04-05-08  | ✓ `onSubmitClicked` wraps `CHANNEL.sendToServer` in try/catch; on failure, `appendError` surfaces a TRANSPORT error card with `forgebook.error.no_server.body`. Pending state clears when session.appendError fires. |

## Requirements Coverage Matrix

| Req   | Description                           | Satisfied by                                                                |
| ----- | ------------------------------------- | --------------------------------------------------------------------------- |
| UI-01 | Inventory button injection            | `InventoryButtonInjector.onScreenInit` + `InventoryButtonGeometry.compute`  |
| UI-02 | ChatScreen opens from button          | `Button.builder(...).onPress(b -> setScreen(new ChatScreen(inv)))`           |
| UI-03 | Text-glyph button; no new textures    | `Component.literal("F")` label; zero `.png` shipped in this plan            |
| UI-04 | Rendering surface                     | Plan 04-04 (`ChatPanelWidget`) — embedded here via `addRenderableWidget`    |
| UI-05 | Session clears on close + logout      | `ChatScreen.onClose() → clear()` (this plan) + `SessionLifecycleListener` (04-03) |
| UI-06 | `ENABLE_CHAT_INTERFACE` per-fire gate | `InventoryButtonInjector` re-reads `.get()` every event fire                |
| UI-07 | Scale-aware layout                    | Plan 04-02 (`ChatPanelLayout.compute`) — invoked in `ChatScreen.init/render` |
| UI-08 | Package firewall at CI                | `.github/workflows/build.yml` UI-08 reverse-firewall step                   |

All eight UI-* requirements for Phase 4 now land at code + CI tier. Plan 04-06's remaining work is the human smoke checkpoint (live in-game verification).

## Phase-4 Final Assembly Status

After this plan, Phase 4 production code is complete:

| Plan  | Contribution                                                                 | Status     |
| ----- | ---------------------------------------------------------------------------- | ---------- |
| 04-01 | ChatEntry / MessageBubble / ErrorCard / LoadingIndicator value types         | ✓ Complete |
| 04-02 | ChatPanelLayout / InventoryButtonGeometry / ClientChatSession singleton      | ✓ Complete |
| 04-03 | ClientPacketSinks + packet-handler wiring + SessionLifecycleListener         | ✓ Complete |
| 04-04 | ChatPanelWidget rendering surface                                            | ✓ Complete |
| 04-05 | ChatScreen + InventoryButtonInjector + en_us.json + ClientSetup + UI-08 CI   | ✓ Complete |
| 04-06 | Live smoke checkpoint + polish                                               | Pending    |

## Next Plan Readiness

**Ready for plan 04-06 (polish + live smoke):**

- `./gradlew runClient` can now launch a full chat cycle: open inventory → click "F" button → ChatScreen opens → type a question → ENTER → pending state → server reply arrives → bubble renders. The smoke tester can exercise:
  - UI-01: button appears at (leftPos + imageWidth + 4, topPos + 4)
  - UI-02: button click opens ChatScreen
  - UI-05: ESC clears session; reopening is a fresh panel
  - UI-06: toggling `enable_chat_interface = false` in `config/forgebook-client.toml` and reopening inventory removes the button
  - UI-07: shrinking the window to ~240×180 triggers the "Screen too small" label; normal at ≥320
  - Error taxonomy: all 6 `ErrorCode` values can be triggered via test-mode injection to verify stripe colors + copy
- The disconnect-triggered clear path (plan 04-03's `SessionLifecycleListener`) can be smoke-tested by opening chat, typing a message, disconnecting from the server, rejoining, and verifying the panel is empty.
- If plan 04-06 adds any final UI tweaks (e.g., scrollbar polish, narration cadence), they should be additive and land under `com.forgebook.client.ui.*` to stay inside the UI-08 envelope — the CI grep is now live and will flag any drift.

## TDD Gate Compliance

Not applicable — this plan's four tasks are all `type="auto"` (non-TDD). The plan's TDD posture: rendering and wiring primitives that require a booted Minecraft to exercise (Screen lifecycle, button injection at game-time, i18n resolution) are deferred to plan 04-06's human smoke checkpoint, per RESEARCH §"What CANNOT be unit-tested without booting Minecraft". The pure-Java seams that CAN be unit-tested (bubble-height math, stripe color lookups, layout geometry, session state machine, dot cycler) were all TDD'd in plans 04-01 and 04-02.

## Self-Check: PASSED

All claimed files exist on disk; all claimed commits are present in `git log`.

- Files:
  - `src/main/java/com/forgebook/client/ui/ChatScreen.java` — FOUND
  - `src/main/java/com/forgebook/client/ui/InventoryButtonInjector.java` — FOUND
  - `src/main/resources/assets/forgebook/lang/en_us.json` — FOUND
  - `src/main/java/com/forgebook/client/ClientSetup.java` — MODIFIED (init body rewritten)
  - `.github/workflows/build.yml` — MODIFIED (UI-08 step inserted)
- Commits (verified via `git log --oneline ceec44a..HEAD`):
  - `fa9e11f` feat(04-05): ChatScreen standalone Screen with parent-render, ESC/ENTER, submit wiring — FOUND
  - `611026e` feat(04-05): InventoryButtonInjector ScreenEvent.Init.Post handler — FOUND
  - `50c39bb` feat(04-05): en_us.json i18n keys + ClientSetup installs packet sinks — FOUND
  - `38f6d4b` chore(04-05): add UI-08 reverse-firewall grep to CI build workflow — FOUND
- Build: `./gradlew --no-daemon build` — BUILD SUCCESSFUL (all tasks executed; all tests green; resource processing validated en_us.json).
- UI-08 local grep: 0 violations across plans 04-01..04-05 outputs.
- SCAF-02 local grep: 0 violations (unchanged from Phase 1).

---

*Phase: 04-in-inventory-chat-ui*
*Plan: 05*
*Completed: 2026-04-16*
