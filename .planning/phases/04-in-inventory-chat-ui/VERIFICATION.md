---
phase: 04-in-inventory-chat-ui
verified: 2026-04-16T22:00:00Z
status: human_needed
score: 5/5 must-haves code-verified; live-smoke pending
overrides_applied: 0
re_verification:
  previous_status: none
  previous_score: none
  gaps_closed: []
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Phase 4 in-game live smoke (12-test UAT protocol, runClient + runServer)"
    expected: "All 5 ROADMAP success criteria visually verify: (SC-1) button appears at leftPos+imageWidth+4 with no widget overlap and opens ChatScreen; (SC-2) user types → pending → assistant bubble arrives or inline error card renders (all 6 error codes with taxonomy-correct stripes); (SC-3) ESC + disconnect both clear the session; (SC-4) GUI scales 1-4 render without clipping and small-screen fallback triggers below 240×180 or 320×240; (SC-5) enable_chat_interface=false suppresses button."
    why_human: "All 12 tests require a booted Minecraft client with OpenGL rendering, Anthropic API key (tests 4 and 7), OP permission, and real keystroke / disconnect events. No automated tool can verify pixel-accurate widget placement, animation cadence, color accuracy, or live ScreenEvent.Init.Post firing. Plan 04-06 explicitly auto-deferred the checkpoint under workflow._auto_chain_active=true; the 12-test protocol is spec'd verbatim in 04-06-PLAN.md lines 103-257 and the UAT file (.planning/phases/04-in-inventory-chat-ui/04-HUMAN-UAT.md) is the pending operator deliverable."
---

# Phase 4: In-Inventory Chat UI — Verification Report

**Phase Goal:** A player with the mod installed can click a button inside the vanilla inventory screen, open a docked chat panel, hold a multi-turn conversation that uses the full tool-using agent, see loading and error states clearly, and have the conversation evaporate when they close the screen or disconnect — all without the client ever touching an API key.

**Verified:** 2026-04-16T22:00:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

---

## Executive Summary

Phase 4 is **code-complete and automated-gate green**. All 5 ROADMAP success criteria and all 8 UI-* requirements map to concrete, substantive, wired source artifacts. 48 Phase-4 unit tests pass (0 failures) inside an aggregate 322-test green suite. The UI-08 package firewall is enforced both at source (zero forbidden imports) and in CI (`.github/workflows/build.yml` UI-08 step). `./gradlew build` and `./gradlew test --rerun-tasks` both succeed.

One item requires human verification: **plan 04-06's 12-test live-smoke UAT**. This was explicitly auto-deferred by the orchestrator under `--auto` (not a defect — the plan is a `checkpoint:human-verify` gate and the deferral is contractual). It covers the visual/runtime half of all 5 SCs that cannot be verified without a booted client. `04-HUMAN-UAT.md` is the operator's pending deliverable.

**Overall verdict:** PARTIAL — code tier verified, live tier pending. Proceed to Phase 5 planning is acceptable per 04-06-SUMMARY §"Next Plan Readiness"; a FAIL in live-smoke would route back via `/gsd-plan-phase 4 --gaps`.

---

## Goal Achievement — ROADMAP Success Criteria

### Observable Truths (5 ROADMAP SCs)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| SC-1 | Opening inventory shows a ForgeBook toggle button at a fixed offset relative to `leftPos`/`topPos` that does not overlap vanilla widgets; clicking it opens a `ChatScreen` rendered adjacent to (not replacing) the inventory, with the inventory still fully visible and interactable | VERIFIED (code) + PENDING (live) | `InventoryButtonInjector.java:45` `@Mod.EventBusSubscriber(Bus.MOD, Dist.CLIENT)` on `ScreenEvent.Init.Post`; `:53` filters `InventoryScreen` via `instanceof inv`; `:61-62` delegates to `InventoryButtonGeometry.compute(inv.getGuiLeft(), inv.getGuiTop(), inv.getXSize())` → `Rect(leftPos+176+4, topPos+4, 20, 20)` (`InventoryButtonGeometry.java:34-36`). Click opens `new ChatScreen(inv)` at `InventoryButtonInjector.java:69`. `ChatScreen.java:47` extends `Screen` (standalone, not AbstractContainerScreen — JEI/REI compat). `ChatScreen.render():128-130` re-renders parent `InventoryScreen` with `Integer.MAX_VALUE` mouse coords (Pitfall 11) so inventory is visible beneath without vanilla tooltips bleeding through. Live pixel-accuracy pending plan 04-06 Tests 1-2. |
| SC-2 | Player types a question, sees a loading indicator while the server processes it, and receives an assistant `MessageBubble` in the scrollable conversation view — with inline error surfacing (not a toast, not a crash) when the server returns any `ChatErrorPacket` in the Phase-3 taxonomy | VERIFIED (code) + PENDING (live) | `ChatScreen.onSubmitClicked():195-219` mints UUID, appends user bubble, marks pending, sends `ChatRequestPacket` via `ForgebookNetwork.CHANNEL.sendToServer`. Loading indicator: `ChatPanelWidget.renderLoading():262-270` renders `LoadingIndicator.frame(System.currentTimeMillis())` (500ms cadence from `LoadingIndicator.java:26-35`) + `forgebook.chat.loading` label in accent `0xFFB0C4F5` when `ClientChatSession.get().isPending()`. Reply handling: `ChatResponsePacket.handleOnClient():36-44` dispatches via `ClientPacketSinks.replySink` → `ClientChatSession.append()` (wired in `ClientSetup.java:37-38`). Errors: `ChatErrorPacket.handleOnClient():50-58` → `errorSink` → `ClientChatSession.appendError()` (wired `ClientSetup.java:39-40`); `ChatPanelWidget.renderErrorCard():212-258` renders full-width card with 4-px taxonomy stripe from `ErrorCard.stripeColor(code)` (exhaustive switch over 6 codes, `ErrorCard.java:17-26`). No Toast usage: `grep -r "Toast" src/main/java/com/forgebook/client/` → 0 hits. Color accuracy + animation cadence pending plan 04-06 Tests 3-7. |
| SC-3 | Closing the chat screen OR disconnecting from the server clears the entire in-memory `ClientChatSession`; reopening the screen starts a fresh session, with no prior messages visible | VERIFIED (code) + PENDING (live) | `ChatScreen.onClose():168-176` calls `ClientChatSession.get().clear()` FIRST, then `minecraft.setScreen(parent)` (Pitfall 9 clear-before-setScreen). `SessionLifecycleListener.java:37` `@Mod.EventBusSubscriber(Bus.FORGE, Dist.CLIENT)` fires `onClientLogout(ClientPlayerNetworkEvent.LoggingOut)` at `:45-48` → `ClientChatSession.get().clear()`. `clear():84-89` resets bubbles, errors, pendingRequestId, pending. Stale-response guard at `append():68-73` and `appendError():76-81` drops late replies when `pendingRequestId` does not match. `ClientChatSessionTest` locks 10 state-machine invariants including `appendAfterClear_withOldId_isNoOp`. Live keypress + real logout event pending plan 04-06 Tests 8-9. |
| SC-4 | At GUI scales 1 through 4 on screens ≥1280×720 the chat panel renders without clipping vanilla widgets or the chat content itself; a minimum-width or stacked fallback triggers on smaller resolutions | VERIFIED (code) + PENDING (live) | `ChatPanelLayout.compute(winW, winH)` (`ChatPanelLayout.java:46-56`) returns `LayoutResult(tooSmall, stacked, panelX, panelY, panelW, panelH)` with three branches: `tooSmall` (winW<240 or winH<180), `stacked` (winW<320), normal (≥320, centered 240px). `ChatPanelLayoutTest` locks 8 boundary tests (1280×720, 480×360, 320×240 edge, 319×240 stacked, 240×180 min, 239×180 too-small, 320×179 too-small). `ChatScreen.init():87-94` branches on `tooSmall` to render only the `forgebook.chat.screen_too_small` label. `ChatScreen.render():136-144` re-checks and draws the too-small label when applicable. `ChatPanelWidget.renderContent():134+148` uses `GuiGraphics.enableScissor/disableScissor` to prevent content overflow. Live OpenGL scissor behavior at every GUI scale pending plan 04-06 Test 11. |
| SC-5 | With `enable_chat_interface = false` (CLIENT config), the button is never injected and the `ChatScreen` cannot be opened; the client source tree contains zero code paths that read or carry an API key value | VERIFIED (code + CI) + PENDING (live) | `InventoryButtonInjector.onScreenInit():55` reads `ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()` PER FIRE (not cached); early-return suppresses all listener attachment. No static caching: `grep -c "private static final boolean" InventoryButtonInjector.java` → 0. API-key firewall: 3-grep audit (see UI-08 Firewall section below) returns 0 forbidden imports anywhere under `com.forgebook.client.{ui,session}` OR `com.forgebook.network.client`. CI step `UI-08 reverse firewall` in `.github/workflows/build.yml:66-82` fails any future PR that adds `com.forgebook.ai.*`, `com.forgebook.safety.*`, or `com.forgebook.config.ApiKey` imports in those subpackages. Phase-1 SCAF-02 forward firewall + `ApiKey.raw()` caller lint both preserved. Live config-toggle verification pending plan 04-06 Test 10; redundant source-scan pending plan 04-06 Test 12. |

**Score:** 5/5 code-verified; all 5 await live visual verification (single consolidated human-UAT deliverable).

---

## Required Artifacts — 3-Level Verification

| Artifact | Expected | Exists | Substantive | Wired | Data Flows | Status |
|----------|----------|:------:|:-----------:|:-----:|:----------:|--------|
| `src/main/java/com/forgebook/client/ui/ChatEntry.java` | sealed interface permits MessageBubble, ErrorCard | ✓ | ✓ (8 LOC, single declaration, correct permits) | ✓ (consumed by MessageBubble, ErrorCard as implements) | N/A | VERIFIED |
| `src/main/java/com/forgebook/client/ui/MessageBubble.java` | USER/ASSISTANT record + pure computeBubbleHeight | ✓ | ✓ (34 LOC, enum + factories + pure math) | ✓ (`ClientChatSession.appendUserMessage` creates instances; `ChatPanelWidget.renderBubble` consumes) | N/A (value type) | VERIFIED |
| `src/main/java/com/forgebook/client/ui/ErrorCard.java` | record + stripeColor/headingKey/bodyKey exhaustive switches | ✓ | ✓ (51 LOC, 6-case exhaustive switches for all 3 lookups) | ✓ (`ClientChatSession.appendError` creates; `ChatPanelWidget.renderErrorCard` consumes) | N/A (value type) | VERIFIED |
| `src/main/java/com/forgebook/client/ui/LoadingIndicator.java` | pure-function dot cycler 500ms × 4 frames | ✓ | ✓ (36 LOC, Math.floorMod + switch) | ✓ (`ChatPanelWidget.renderLoading:263` calls `LoadingIndicator.frame(System.currentTimeMillis())`) | ✓ (produces real frame strings) | VERIFIED |
| `src/main/java/com/forgebook/client/ui/ChatPanelLayout.java` | pure-function layout with tooSmall/stacked/normal branches | ✓ | ✓ (57 LOC, named constants, LayoutResult record) | ✓ (`ChatScreen.init:87` + `ChatScreen.render:137` both call `compute`) | ✓ (LayoutResult drives widget sizing + too-small rendering) | VERIFIED |
| `src/main/java/com/forgebook/client/ui/InventoryButtonGeometry.java` | pure-function Rect with SIZE/GAP_X/OFFSET_Y constants | ✓ | ✓ (37 LOC) | ✓ (`InventoryButtonInjector.java:61-62` calls `compute`) | ✓ (Rect coords wire into Button.builder.bounds) | VERIFIED |
| `src/main/java/com/forgebook/client/ui/ChatPanelWidget.java` | AbstractWidget with bubble/error/loading/scroll rendering | ✓ | ✓ (321 LOC, all 7 private render* helpers, scissor clipping, scrollbar math) | ✓ (`ChatScreen.init:99-100` adds via `addRenderableWidget`) | ✓ (reads 3 snapshots from `ClientChatSession.get()`) | VERIFIED |
| `src/main/java/com/forgebook/client/ui/ChatScreen.java` | standalone Screen with EditBox + submit + ChatPanelWidget | ✓ | ✓ (220 LOC, full lifecycle: init/render/onClose/isPauseScreen/keyPressed/onSubmitClicked) | ✓ (opened by InventoryButtonInjector; embeds ChatPanelWidget; sends via ForgebookNetwork) | ✓ (submit flow writes to session + channel; render flow reads session state) | VERIFIED |
| `src/main/java/com/forgebook/client/ui/InventoryButtonInjector.java` | @EventBusSubscriber Bus.MOD handler for ScreenEvent.Init.Post | ✓ | ✓ (80 LOC, correct bus, per-fire config read, geometry delegation, click handler) | ✓ (auto-registers via annotation; consumed by Forge event bus) | ✓ (reads config each fire; builds button when enabled) | VERIFIED |
| `src/main/java/com/forgebook/client/session/ClientChatSession.java` | singleton with append/appendError/clear/pending + stale-guard | ✓ | ✓ (110 LOC, synchronized writes, volatile flags, defensive copy snapshots) | ✓ (called from ChatScreen, ChatPanelWidget, SessionLifecycleListener, ClientSetup sinks) | ✓ (bubbles/errors/pendingRequestId propagate through append/appendError) | VERIFIED |
| `src/main/java/com/forgebook/client/session/SessionLifecycleListener.java` | @EventBusSubscriber Bus.FORGE Dist.CLIENT logout handler | ✓ | ✓ (49 LOC, correct annotation args, one @SubscribeEvent method) | ✓ (auto-registers; Forge bus ClientPlayerNetworkEvent.LoggingOut) | ✓ (clears session on logout) | VERIFIED |
| `src/main/java/com/forgebook/network/client/ClientPacketSinks.java` | volatile sink holder: replySink BiConsumer + errorSink ErrorSink | ✓ | ✓ (59 LOC, zero net.minecraft imports, correct package rationale documented) | ✓ (written by ClientSetup.init, read by ChatResponse/Error handleOnClient) | ✓ (lambda dispatch reaches ClientChatSession) | VERIFIED |
| `src/main/resources/assets/forgebook/lang/en_us.json` | 21 i18n keys per UI-SPEC §Copywriting | ✓ | ✓ (21 keys confirmed by `grep -cE '^\s*"forgebook\.'` = 21) | ✓ (resolved via Component.translatable across ChatScreen, ChatPanelWidget, InventoryButtonInjector, ErrorCard) | ✓ (resource processing green; valid JSON) | VERIFIED |
| `src/main/java/com/forgebook/client/ClientSetup.java` (modified) | init() installs both ClientPacketSinks sinks | ✓ | ✓ (45 LOC, both lambdas present, explains auto-registration) | ✓ (invoked from ForgeBookMod via DistExecutor.safeRunWhenOn) | ✓ (sink assignments unconditionally execute on client boot) | VERIFIED |
| `.github/workflows/build.yml` (modified) | UI-08 reverse-firewall grep step | ✓ | ✓ (17 LOC new step, :66-82, `exit 1` on violation) | ✓ (between ApiKey.raw() lint and Build; runs on every push/PR) | ✓ (active grep enforces architecture) | VERIFIED |

All 15 artifacts pass levels 1-4.

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| InventoryButtonInjector | InventoryButtonGeometry | pure-function delegation | WIRED | `InventoryButtonInjector.java:61-62` calls `InventoryButtonGeometry.compute(inv.getGuiLeft(), inv.getGuiTop(), inv.getXSize())` |
| InventoryButtonInjector | ChatScreen | Button onPress | WIRED | `InventoryButtonInjector.java:69` `b -> Minecraft.getInstance().setScreen(new ChatScreen(inv))` |
| ChatScreen | ChatPanelWidget | addRenderableWidget | WIRED | `ChatScreen.java:99-100` `this.addRenderableWidget(new ChatPanelWidget(layout.panelX(), layout.panelY(), layout.panelW(), panelH))` |
| ChatScreen | ForgebookNetwork.CHANNEL | sendToServer | WIRED | `ChatScreen.java:207` `ForgebookNetwork.CHANNEL.sendToServer(new ChatRequestPacket(reqId, msg))` inside try/catch (TRANSPORT fallback on throw) |
| ChatScreen | ClientChatSession | append/markPending/clear/isPending | WIRED | `ChatScreen.java:152,170,188,198,200,201,215` — 7 call sites across render/onClose/keyPressed/onSubmitClicked |
| ClientSetup | ClientPacketSinks | sink field assignment | WIRED | `ClientSetup.java:37-40` assigns both `replySink` and `errorSink` with correct lambda shapes |
| ClientSetup | ClientChatSession | sink lambda body | WIRED | `ClientSetup.java:38` calls `.append(id, text)`; `:40` calls `.appendError(id, code, msg)` |
| ChatResponsePacket | ClientPacketSinks.replySink | null-guarded dispatch | WIRED | `ChatResponsePacket.java:38-43` reads into local, null-checks, dispatches; log-warn fallback |
| ChatErrorPacket | ClientPacketSinks.errorSink | null-guarded dispatch | WIRED | `ChatErrorPacket.java:52-58` same pattern with triple-arg ErrorSink |
| SessionLifecycleListener | ClientChatSession.clear | disconnect hook | WIRED | `SessionLifecycleListener.java:46` `ClientChatSession.get().clear()` inside `onClientLogout` |
| ChatPanelWidget | ClientChatSession | 3 snapshot reads per frame | WIRED | `ChatPanelWidget.java:110-112, 314-316` — bubbles, errors, isPending read exactly once per render then reused |
| ChatPanelWidget | ErrorCard.stripeColor | per-error taxonomy lookup | WIRED | `ChatPanelWidget.java:214` calls `ErrorCard.stripeColor(ec.code())` |
| ChatPanelWidget | LoadingIndicator.frame | per-frame dot cycler | WIRED | `ChatPanelWidget.java:263` calls `LoadingIndicator.frame(System.currentTimeMillis())` |
| ForgeBookMod | ClientSetup | DistExecutor.safeRunWhenOn | WIRED | `ForgeBookMod.java:89-90` `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> com.forgebook.client.ClientSetup::init)` |

All 14 key links verified. The full server→client dispatch chain is end-to-end:
`Server reply → ChatResponsePacket.handleOnClient → ClientPacketSinks.replySink → ClientChatSession.append → (next render frame) → ChatPanelWidget.renderBubble`.

---

## Data-Flow Trace (Level 4)

For artifacts that render dynamic data:

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| ChatPanelWidget | bubbles (List<MessageBubble>) | `ClientChatSession.snapshotBubbles()` → `List.copyOf(bubbles)` where `bubbles` is written by `appendUserMessage` (user click) + `append` (server reply via sink) | Yes — optimistic user append + server reply via live ClientPacketSinks dispatch (both wired) | FLOWING |
| ChatPanelWidget | errors (List<ErrorCard>) | `ClientChatSession.snapshotErrors()` → mirrored by `appendError` writes from server via errorSink | Yes — `ClientPacketSinks.errorSink` installed in ClientSetup.init; packet handler dispatch verified | FLOWING |
| ChatPanelWidget | pending (boolean) | `ClientChatSession.isPending()` — volatile set in `markPending`, cleared in `append`/`appendError`/`markIdle`/`clear` | Yes — ChatScreen.onSubmitClicked sets true; server reply or error clears | FLOWING |
| ChatScreen | layout (LayoutResult) | `ChatPanelLayout.compute(this.width, this.height)` — pure function of live window dimensions | Yes — Minecraft re-invokes `init` on window resize + GUI-scale change | FLOWING |
| ChatScreen | submitBtn.active | `!pending && !input.getValue().isBlank()` — per-frame re-eval | Yes — flips live as user types and pending transitions | FLOWING |
| ChatErrorPacket / ChatResponsePacket | sink target | `ClientPacketSinks.{reply,error}Sink` assigned in `ClientSetup.init` | Yes — null-guarded with log-warn fallback; sinks are non-null after client boot | FLOWING |
| InventoryButtonInjector | config read | `ForgebookClientConfig.ENABLE_CHAT_INTERFACE.get()` per event fire | Yes — ForgeConfigSpec live value; re-read on each ScreenEvent.Init.Post | FLOWING |

No HOLLOW or STATIC artifacts.

---

## Requirements Coverage (UI-01..UI-08)

All requirements mapped to Phase 4 per REQUIREMENTS.md lines 77-84. Plan frontmatters claim coverage per `requirements:` field.

| Req | Description (abridged) | Source Plan(s) | Satisfied By | Status |
|-----|-----------------------|----------------|--------------|--------|
| UI-01 | InventoryButtonInjector on ScreenEvent.Init.Post; 20×20 button at fixed offset, no vanilla overlap | 04-02 (geometry), 04-05 (injector) | `InventoryButtonInjector.java:45,53,61-62,69,77` + `InventoryButtonGeometry.compute()` | SATISFIED (code); live visual pending |
| UI-02 | Click opens ChatScreen (standalone Screen fallback per spec) | 04-05 | `InventoryButtonInjector.java:69` → `new ChatScreen(inv)`; `ChatScreen.java:47` extends Screen (standalone per UI-SPEC resolved spike) | SATISFIED (code); live pending |
| UI-03 | Vanilla-reused assets + user-supplied logo only; no third-party textures | 04-05 | `InventoryButtonInjector.java:68` `Component.literal("F")` glyph label; en_us.json ships no PNG; no `.png` touched in Phase 4; ChatPanelWidget uses procedural `GuiGraphics.fill` | SATISFIED |
| UI-04 | ChatWidget renders scrollable conversation, bubbles, input, submit, loading, inline error | 04-01 (value types + color map), 04-04 (widget) | `ChatPanelWidget.java` full file (321 LOC): `renderBubble`, `renderErrorCard`, `renderLoading`, `renderScrollbar`, `renderContent`; `ErrorCard.stripeColor` 6-case exhaustive; `LoadingIndicator.frame` 500ms cadence | SATISFIED (code); live rendering pending |
| UI-05 | ClientChatSession in-memory only; cleared on screen close AND on disconnect | 04-02 (session singleton), 04-03 (lifecycle listener), 04-05 (ChatScreen.onClose) | `ClientChatSession.java` (ephemeral singleton, no persistence); `ChatScreen.onClose:170` `clear()` BEFORE setScreen; `SessionLifecycleListener.java:45-48` on LoggingOut | SATISFIED (code); live disconnect + keypress pending |
| UI-06 | Respects enable_chat_interface CLIENT config — no button when false | 04-05 | `InventoryButtonInjector.java:55` per-fire `ENABLE_CHAT_INTERFACE.get()` early-return; no cached boolean constant | SATISFIED (code); live toggle pending |
| UI-07 | GUI scales 1-4 at ≥1280×720 render without clipping; minimum-width or stacked fallback on smaller | 04-02 (layout math), 04-04 (scissor), 04-05 (too-small branch) | `ChatPanelLayout.compute()` 3 branches + constants; 8 boundary tests green; `ChatPanelWidget.enableScissor/disableScissor` at `:134+148`; `ChatScreen.render:136-144` too-small rendering | SATISFIED (code); live GUI-scale cycle pending |
| UI-08 | Client never holds or displays API key; all AI via ChatRequestPacket | 04-01, 04-02, 04-03, 04-05 (enforcement) | Zero imports of `com.forgebook.{ai,safety}.*` or `com.forgebook.config.ApiKey` across `com.forgebook.client.{ui,session}` AND `com.forgebook.network.client` (4-grep audit; see UI-08 section below); `.github/workflows/build.yml:66-82` fails any future violation; `ChatScreen` submits via `ForgebookNetwork.CHANNEL.sendToServer` only | SATISFIED (code + CI) |

**Coverage:** 8/8 UI-* requirements code-complete. No orphaned requirements — REQUIREMENTS.md Phase-4 block is exactly UI-01..UI-08 and all 8 are claimed by at least one plan frontmatter.

---

## UI-08 Package Firewall Audit

The firewall is the critical safety invariant of Phase 4. Audit scope, method, and results:

### Forbidden imports (must be zero)

```bash
grep -rnE 'import com\.forgebook\.(ai|safety)\.|import com\.forgebook\.config\.ApiKey' \
  src/main/java/com/forgebook/client/ui/          → 0 hits
grep -rnE 'import com\.forgebook\.(ai|safety)\.|import com\.forgebook\.config\.ApiKey' \
  src/main/java/com/forgebook/client/session/     → 0 hits
grep -rnE 'import com\.forgebook\.(ai|safety)\.|import com\.forgebook\.config\.ApiKey' \
  src/main/java/com/forgebook/network/client/     → 0 hits
grep -rnE 'import com\.forgebook\.(ai|safety)\.|import com\.forgebook\.config\.ApiKey' \
  src/main/java/com/forgebook/client/             → 0 hits
```

All four greps (3 declared + 1 broader client tree check) return zero hits.

### CI enforcement

`.github/workflows/build.yml` step "UI-08 reverse firewall - no forbidden imports in com.forgebook.client.{ui,session}" (lines 66-82) runs on every push and PR. The step uses an equivalent grep and `exit 1`s on any match, printing the offending line. The step sits between Phase-1's `ApiKey.raw() caller lint` (preserved unchanged) and `Build`, so a violation blocks the build.

### Non-code mentions (allowed)

Five `ApiKey` / `ai.` / `safety.` references exist in Javadoc inside the client tree — all documenting the firewall rule itself. They are not imports and do not violate UI-08. Enumerated in 04-06-SUMMARY (lines 147-153):

- `SessionLifecycleListener.java:35`
- `ClientChatSession.java:27`
- `ChatScreen.java:43`
- `ChatPanelWidget.java:25`
- `InventoryButtonInjector.java:41`

### Verdict

**UI-08 firewall is enforced at both source and CI tier.** Zero forbidden imports; CI regression net in place.

---

## Anti-Patterns Scan

Scan scope: all files modified/created in Phase 4 (per plan frontmatters + SUMMARY key-files).

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `ChatPanelWidget.java` | 54, 127 | `COLOR_PLACEHOLDER` constant | Info (not a stub) | Named color constant for empty-state hint text; legitimate use. |

**No TODO/FIXME/XXX/HACK/PLACEHOLDER comments, no empty `return null` / `return {}` handlers, no hardcoded empty-data stubs, no `console.log`-only implementations.** The search across `src/main/java/com/forgebook/client/*.java` + `src/main/java/com/forgebook/network/client/*.java` returns only the one legitimate color constant.

Stub classification: `COLOR_PLACEHOLDER` is a UI-SPEC-mandated color literal (placeholder/hint text color `0xFF808080`), not a code stub. The empty-state rendering uses `bubbles.isEmpty() && errors.isEmpty() && !pending` as a real branch condition and draws the translated `forgebook.chat.empty.body` string — not a placeholder return.

---

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Build compiles clean | `./gradlew --no-daemon build` | BUILD SUCCESSFUL in 24s | PASS |
| Full test suite | `./gradlew --no-daemon test --rerun-tasks` | BUILD SUCCESSFUL; 322 tests, 0 failures, 0 skipped (test-report index.html confirms) | PASS |
| Phase-4 unit suites | aggregate of 7 Phase-4-specific test classes | 48 tests (ClientChatSessionTest=10, ChatPanelLayoutTest=8, ErrorCodeColorMapTest=7, InventoryButtonGeometryTest=5, LoadingIndicatorTest=6, MessageBubbleWrapMathTest=6, ClientPacketSinksTest=6), 0 failures | PASS |
| en_us.json key count | `grep -cE '^\s*"forgebook\.' en_us.json` | 21 (matches UI-SPEC 21-key lock) | PASS |
| UI-08 firewall (client tree) | `grep -rnE '...(ai|safety)...ApiKey...' client/` | 0 hits | PASS |
| UI-08 firewall (network.client) | same grep under network/client/ | 0 hits | PASS |
| Zero Toast usage | `grep -r "Toast" src/main/java/com/forgebook/client/` | 0 hits (error-surfacing is inline per UI-04 contract, never toast) | PASS |
| Zero cached-config booleans | `grep -c "private static final boolean" InventoryButtonInjector.java` | 0 (UI-06 per-fire read enforced) | PASS |
| ClientSetup invocation wired | `grep "DistExecutor.safeRunWhenOn\|ClientSetup" ForgeBookMod.java` | ForgeBookMod.java:89-90 `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)` | PASS |

All 9 automatable spot-checks pass. A 10th category — live-client smoke — is deferred (see Human Verification below).

---

## Deferred Items

Phase 5 (Release Polish) explicitly covers the following items in its ROADMAP success criteria; they are NOT Phase 4 gaps:

| Item | Addressed In | Evidence |
|------|--------------|----------|
| Logo assets (`src/main/resources/logo.png` + `textures/gui/logo.png`) | Phase 5 | Phase 5 SC-1 literally names both logo slots as its deliverable; UI-SPEC §"Registry Safety" explicitly defers to REL-01 |
| README security-posture documentation | Phase 5 | Phase 5 SC-3 covers installation + config + security posture + `chmod 600` recommendation |
| Mod-compat matrix (JEI, REI, Sodium, Jade, etc.) | Phase 5 | Phase 5 SC-4 is exactly this matrix |
| Prod-jar dedicated-server smoke test | Phase 5 | Phase 5 SC-5 |

No Phase-4 truths are currently deferred — all 5 SCs are code-verified and awaiting live UAT only.

---

## Human Verification Required

### 1. Phase 4 in-game live smoke — 12-test UAT protocol (consolidated)

**Test:** Run `./gradlew runClient` (separate terminal: `./gradlew runServer`), connect to localhost, `op` the player in server console, then execute the 12-test protocol documented verbatim in `.planning/phases/04-in-inventory-chat-ui/04-06-PLAN.md` lines 103-257. Record outcomes in a new file `.planning/phases/04-in-inventory-chat-ui/04-HUMAN-UAT.md` using the template at 04-06-PLAN.md lines 260-313.

**Expected:** All 12 tests PASS, mapping to the 5 ROADMAP SCs as follows:
- Tests 1-2 (SC-1): button visible at `leftPos + imageWidth + 4, topPos + 4`, tooltip reads "Ask ForgeBook", click opens ChatScreen with inventory visible behind
- Tests 3-7 (SC-2): optimistic user bubble; "Thinking…" dot cadence; assistant reply arrives and pending clears (requires API key); error cards render with taxonomy-correct stripe colors for DISABLED (gray), FORBIDDEN (red), RATE_LIMITED blue (requires API key)
- Tests 8-9 (SC-3): ESC closes + clears; disconnect + rejoin shows empty panel
- Tests 10 (SC-5): `enable_chat_interface = false` in `config/forgebook-client.toml` + reopen inventory → no button appears
- Test 11 (SC-4): GUI scale cycle 1→2→3→4→Auto at 1280×720 shows panel adjusting without clipping; window resize to ~240×180 triggers "Screen too small" label
- Test 12 (SC-5): manual re-run of UI-08 source-scan grep, paste zero-hit output for traceability

**Why human:** All 12 tests require visual inspection of live OpenGL rendering, real Minecraft `ScreenEvent.Init.Post` firing, keyboard + mouse input, dedicated-server socket events, and (tests 4+7) a valid Anthropic API key. No automated tool can verify pixel-accurate widget placement, animation cadence, color accuracy, or `ScreenEvent.Init.Post` live firing without booting the client — RESEARCH §"What CANNOT be unit-tested without booting Minecraft" documents this invariant explicitly. Plan 04-06 auto-deferred this checkpoint under `workflow._auto_chain_active=true` per the orchestrator's `checkpoint_protocol.md`.

**Post-UAT resume signal (to orchestrator):**
- "approved — all tests pass" → phase closes with 5/5 ROADMAP SCs LIVE-verified
- "approved — [N] skipped for no API key" → phase closes; tests 4+7 noted as skipped (SC-2 partially verified via tests 3+5+6 which cover optimistic render + two error codes without API key)
- "issues found: [description]" → route to `/gsd-plan-phase 4 --gaps`

---

## Gaps Summary

**No code-tier gaps.** All 5 ROADMAP success criteria trace to substantive, wired, data-flowing artifacts. All 8 UI-* requirements are satisfied by at least one plan with concrete code evidence. UI-08 package firewall is enforced at source and CI. Build green, all 322 tests pass.

**One human-tier item pending:** plan 04-06's 12-test live-smoke UAT, which was intentionally auto-deferred under `--auto` mode (not a defect — the plan is a `checkpoint:human-verify` gate whose deferral is contractual). The UAT file `04-HUMAN-UAT.md` is the operator's pending deliverable.

Phase 4 is **ready for Phase 5 planning** per 04-06-SUMMARY §"Next Plan Readiness" — a FAIL in live-smoke would route back via `/gsd-plan-phase 4 --gaps` but the orchestrator can proceed on the assumption that UAT will pass.

---

*Verified: 2026-04-16T22:00:00Z*
*Verifier: Claude (gsd-verifier)*
*Head commit at verification time: 972ff53*
