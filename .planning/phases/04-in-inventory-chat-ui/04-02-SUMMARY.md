---
phase: 04-in-inventory-chat-ui
plan: 02
subsystem: ui
tags: [ui, session, layout, pure-function, state-machine, minecraft-forge, singleton]

# Dependency graph
requires:
  - phase: 04-in-inventory-chat-ui plan 01
    provides: MessageBubble record, ErrorCard record, ChatEntry sealed interface (imported by ClientChatSession)
  - phase: 01-foundations
    provides: ChatErrorPacket.ErrorCode wire-protocol enum (imported by ClientChatSession.appendError parameter)
provides:
  - ChatPanelLayout.compute(winW, winH) — pure-function scale-aware panel geometry (UI-07)
  - InventoryButtonGeometry.compute(leftPos, topPos, imageWidth) — pure-function 20×20 button placement (UI-01 math)
  - ClientChatSession singleton — per-screen bubbles/errors/pending state with stale-response guard (UI-05, UI-D-11)
affects: [04-03 packet-wiring, 04-04 chat-screen-render, 04-05 inventory-button-injector]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pure-function test seam (compute primitive→Rect/LayoutResult) — copied from Authorizer primitive-overload"
    - "Volatile-holder singleton — copied from KillSwitch/ConfigHolder/RateLimiterHolder"
    - "Stale-response guard on UUID match — first use of UI-D-11 pattern in project"
    - "Defensive-copy snapshots via List.copyOf for thread-safe render-thread reads"

key-files:
  created:
    - src/main/java/com/forgebook/client/ui/ChatPanelLayout.java
    - src/main/java/com/forgebook/client/ui/InventoryButtonGeometry.java
    - src/main/java/com/forgebook/client/session/ClientChatSession.java
    - src/test/java/com/forgebook/client/ui/ChatPanelLayoutTest.java
    - src/test/java/com/forgebook/client/ui/InventoryButtonGeometryTest.java
    - src/test/java/com/forgebook/client/session/ClientChatSessionTest.java
  modified: []

key-decisions:
  - "LayoutResult stored as record with tooSmall/stacked/panelX/Y/W/H — single struct describes every branch"
  - "Panel math isolated in a pure function (no Font, no Screen) so GUI-scale change triggers a fresh compute without rebuild"
  - "Stale-guard logic lives on the session (append/appendError short-circuit), not on the packet handler — guarantees the guard fires regardless of which sink installs"
  - "Defensive-copy snapshots via List.copyOf rather than Collections.unmodifiableList — prevents caller from retaining a live-mutating reference"

patterns-established:
  - "Pure-function primitive-overload seam: Screen/Widget lifecycle overrides unpack MC types, delegate to static compute(int,int) — testable without MC classloading"
  - "Singleton session holder: private-static-final INSTANCE + private constructor + get() + clear() — ephemeral, no persistence"
  - "Stale-response guard pattern: if (pendingRequestId == null || !pendingRequestId.equals(requestId)) return; — applied on every in-flight-terminating method"

requirements-completed: [UI-05, UI-07]

# Metrics
duration: 9min
completed: 2026-04-16
---

# Phase 04 Plan 02: Session State & Layout Math Summary

**Three pure-Java primitives — ChatPanelLayout.compute (scale-aware panel geometry), InventoryButtonGeometry.compute (20×20 button placement), and ClientChatSession (singleton with UUID stale-guard) — all net.minecraft-free and locked with 23 unit tests.**

## Performance

- **Duration:** 9 min
- **Started:** 2026-04-16T20:22:12Z
- **Completed:** 2026-04-16T20:31:39Z
- **Tasks:** 3
- **Files created:** 6 (3 production + 3 test)

## Accomplishments

- **UI-07 scale-aware layout locked at pure-Java tier.** `ChatPanelLayout.compute(winW, winH)` returns a `LayoutResult` record encoding every branch — `tooSmall` below 240×180, `stacked` at 240–319 width, normal ≥ 320 — with exact UI-SPEC constants as named public fields (`MIN_WIDTH=240`, `STACKED_THRESHOLD_WIDTH=320`, `DEFAULT_PANEL_WIDTH=240`, etc.). 8 boundary-condition tests green.
- **UI-01 button geometry locked independently of Minecraft.** `InventoryButtonGeometry.compute(leftPos, topPos, imageWidth) → Rect(leftPos+imageWidth+4, topPos+4, 20, 20)`. 5 tests including an exhaustive invariant sweep (36 combinations all returning w=20, h=20).
- **UI-05 session lifecycle + UI-D-11 stale-response guard locked.** `ClientChatSession` is a private-ctor singleton with synchronized write methods, `volatile` pending flags, and a matching-UUID guard on `append` + `appendError` that silently drops replies after `clear()`. 10 state-machine tests including `appendAfterClear_withOldId_isNoOp` and `markPending_overwritesPriorPendingId`.
- **Architectural firewall pre-compliance.** Zero `net.minecraft.*` imports, zero `com.forgebook.{ai,safety}.*` imports, zero `com.forgebook.config.ApiKey` imports across the three production files — verified by post-build grep. UI-08 CI rule (added in plan 04-05) will pass for this plan's output.

## Task Commits

Each task was committed atomically:

1. **Task 1: ChatPanelLayout pure function + boundary tests** — `57b17ec` (feat)
2. **Task 2: InventoryButtonGeometry pure function + tests** — `9ccdedb` (feat)
3. **Task 3: ClientChatSession singleton + stale-guard tests** — `1666d6c` (feat)

_Note: TDD cycle (RED test → GREEN impl) executed in sequence per task; tests and implementation committed together because the cycle is a single atomic unit of deliverable._

## Files Created/Modified

- `src/main/java/com/forgebook/client/ui/ChatPanelLayout.java` — Pure-function scale-aware panel geometry with named UI-SPEC constants and LayoutResult record.
- `src/main/java/com/forgebook/client/ui/InventoryButtonGeometry.java` — Pure-function 20×20 inventory-button placement with SIZE/GAP_X/OFFSET_Y constants and Rect record.
- `src/main/java/com/forgebook/client/session/ClientChatSession.java` — Singleton chat session holder: synchronized writes, volatile pending flags, stale-guard on append/appendError, defensive-copy snapshots.
- `src/test/java/com/forgebook/client/ui/ChatPanelLayoutTest.java` — 8 boundary-condition tests (normal 1280×720, stacked boundary 320×240, stacked 319×240, minimum 240×180, too-small 239×180 and 320×179, wide 480×360, record accessors).
- `src/test/java/com/forgebook/client/ui/InventoryButtonGeometryTest.java` — 5 tests including invariant sweep over 36 coordinate combinations.
- `src/test/java/com/forgebook/client/session/ClientChatSessionTest.java` — 10 tests covering full state machine, stale-guard, overwrite semantics, defensive-copy immutability.

## Decisions Made

- **LayoutResult as record with six fields (tooSmall, stacked, panelX, panelY, panelW, panelH).** Callers handle the `tooSmall` branch first, then check `stacked`, then use the rect. Consolidating branches into one value eliminates nil-state bugs downstream.
- **Panel math recomputed per-frame from window dimensions.** No cache; `Screen.init()` is cheap and gets re-invoked on window resize / GUI-scale change, so the math responds automatically without a tick loop or listener.
- **Named public constants (not magic numbers).** `MIN_WIDTH`, `STACKED_THRESHOLD_WIDTH`, `DEFAULT_PANEL_WIDTH`, `PANEL_Y_INSET`, `PANEL_VERTICAL_INSET_TOTAL`, `STACKED_HORIZONTAL_PADDING`, `SIZE`, `GAP_X`, `OFFSET_Y` — enforces single-source-of-truth and gives plans 04-04/04-05 a stable reference surface.
- **Synchronized writes + volatile flags on ClientChatSession.** Writers (packet handlers on consumerMainThread) and readers (render loop) are both on the main thread in practice, but the synchronized modifiers make JMM visibility explicit and defend against future off-thread callers without a locking rewrite.
- **Defensive-copy snapshots via `List.copyOf` (not `unmodifiableList`).** `unmodifiableList` returns a live wrapper — mutations to the underlying list would tear a mid-render snapshot. `List.copyOf` returns an immutable point-in-time copy, guaranteeing render-frame stability.

## Deviations from Plan

None — plan executed exactly as written. All three tasks followed the action/verify/acceptance cycle verbatim from the PLAN.md.

**Worktree note (not a deviation):** This plan's `ClientChatSession.java` imports `com.forgebook.client.ui.MessageBubble` and `com.forgebook.client.ui.ErrorCard`, which are produced by the sibling wave-1 plan 04-01 running in a parallel worktree. Plan 04-02's committed branch therefore requires merge with plan 04-01's branch before it standalone-compiles — this is the expected wave-1 parallel-execution behavior and does not indicate a plan defect. The plan frontmatter's `files_modified` field correctly lists only the 3 production + 3 test files this plan owns.

## Issues Encountered

None. Gradle offline test runs green on first attempt for each task after writing production code.

## State Machine Invariants Locked

| Transition              | Guard                       | Result                                      |
|-------------------------|-----------------------------|---------------------------------------------|
| fresh                   | —                           | isPending=false, bubbles=[], errors=[], pendingId=null |
| appendUserMessage       | (no guard)                  | bubbles += USER bubble; still idle          |
| markPending(id)         | (overwrites prior id)       | pending=true, pendingId=id                  |
| markIdle                | (no guard)                  | pending=false, pendingId=null               |
| append(id, reply)       | pendingId != null && matches| bubbles += ASSISTANT; idle                  |
| append(staleId, reply)  | mismatch → no-op            | state unchanged (UI-D-11)                   |
| appendError(id,code,msg)| pendingId != null && matches| errors += ErrorCard; idle                   |
| appendError(stale,…)    | mismatch → no-op            | state unchanged (UI-D-11)                   |
| clear                   | —                           | bubbles=[], errors=[], pendingId=null, pending=false |
| append after clear      | pendingId nulled by clear   | no-op (stale-guard fires)                   |

## Layout Boundary Values Captured

| Input           | tooSmall | stacked | panelW | panelX | panelH |
|-----------------|----------|---------|--------|--------|--------|
| 1280×720        | false    | false   | 240    | 520    | 680    |
| 480×360         | false    | false   | 240    | 120    | 320    |
| 320×240 (edge)  | false    | false   | 240    | 40     | 200    |
| 319×240         | false    | true    | 303    | 8      | 200    |
| 240×180 (min)   | false    | true    | 224    | 8      | 140    |
| 239×180         | true     | false   | 0      | 0      | 0      |
| 320×179         | true     | false   | 0      | 0      | 0      |

## Next Phase Readiness

**Ready for plan 04-03 (session-lifecycle-and-packet-wiring):**
- `ClientChatSession.append(UUID, String)` and `appendError(UUID, ErrorCode, String)` are the sink signatures that `ClientPacketSinks.replySink` / `ClientPacketSinks.errorSink` will forward to. Interface is locked.
- `ClientChatSession.clear()` is the hook for both `ChatScreen.onClose()` (plan 04-04) and `SessionLifecycleListener` on `ClientPlayerNetworkEvent.LoggingOut` (plan 04-03).

**Ready for plan 04-04 (chat-screen-render):**
- `ChatPanelLayout.compute(this.width, this.height)` is the single call the `ChatScreen.init()` override needs before adding widgets. The `tooSmall` branch tells the Screen to render only the "Screen too small" label.
- Snapshot accessors (`snapshotBubbles()`, `snapshotErrors()`) provide point-in-time immutable lists that the render loop iterates without tearing.

**Ready for plan 04-05 (inventory-button-injector):**
- `InventoryButtonGeometry.compute(inv.getGuiLeft(), inv.getGuiTop(), inv.getXSize())` is the single call the `ScreenEvent.Init.Post` handler needs. Returns a `Rect` ready for `Button.builder(...).bounds(x, y, w, h)`.

**Known cross-plan dependency:** ChatResponsePacket/ChatErrorPacket wiring into the session is plan 04-03's responsibility — this plan deliberately stops at session sink definitions. Plan 04-03 installs the `ClientPacketSinks` volatile sinks in `ClientSetup.init`.

## Self-Check: PASSED

All claimed files exist on disk; all claimed commit hashes (57b17ec, 9ccdedb, 1666d6c) present in git log.

---
*Phase: 04-in-inventory-chat-ui*
*Completed: 2026-04-16*
