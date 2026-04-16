---
phase: 04-in-inventory-chat-ui
plan: 03
subsystem: network
tags: [network, sinks, firewall, lifecycle, volatile-sink, tdd, forge-1.20.1]

# Dependency graph
requires:
  - phase: 01-foundations
    provides: ChatResponsePacket / ChatErrorPacket.ErrorCode wire types + ChatRequestHandler.responseSinkForTests volatile-sink precedent
  - phase: 04-in-inventory-chat-ui plan 02
    provides: ClientChatSession.clear() hook for logout, and the UUID/String + UUID/ErrorCode/String sink signatures
provides:
  - New package com.forgebook.network.client (neutral wire-adapter tier) — sits between com.forgebook.network.packet.* and com.forgebook.client.session.* without importing either Minecraft or client-session classes.
  - ClientPacketSinks.replySink (static volatile BiConsumer<UUID,String>) for ChatResponsePacket dispatch.
  - ClientPacketSinks.errorSink (static volatile ErrorSink functional interface) for ChatErrorPacket dispatch.
  - Modified ChatResponsePacket.handleOnClient + ChatErrorPacket.handleOnClient that read the sinks with null-guard + log-warn fallback.
  - SessionLifecycleListener — auto-registered via @Mod.EventBusSubscriber(bus = Bus.FORGE, value = Dist.CLIENT); clears ClientChatSession on ClientPlayerNetworkEvent.LoggingOut.
affects: [04-05 ClientSetup.init (installs the sink lambdas)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Volatile-sink holder promoted from test-only (ChatRequestHandler.responseSinkForTests) to production indirection point — keeps packet handlers free of com.forgebook.client.* imports."
    - "Functional-interface widening of BiConsumer → ErrorSink(UUID, ErrorCode, String) for triple-arg sinks Java stdlib doesn't model natively."
    - "@Mod.EventBusSubscriber(bus = Bus.FORGE, value = Dist.CLIENT) for classpath-scan-time auto-registration without an imperative addListener call."
    - "Neutral wire-adapter subpackage com.forgebook.network.client — reads as 'network-side client sink', not 'Minecraft client UI'; CI grep (net.minecraft.client.* outside com.forgebook.client.*) does not flag it."

key-files:
  created:
    - src/main/java/com/forgebook/network/client/ClientPacketSinks.java
    - src/main/java/com/forgebook/client/session/SessionLifecycleListener.java
    - src/test/java/com/forgebook/network/client/ClientPacketSinksTest.java
  modified:
    - src/main/java/com/forgebook/network/packet/ChatResponsePacket.java
    - src/main/java/com/forgebook/network/packet/ChatErrorPacket.java

key-decisions:
  - "Placed ClientPacketSinks under com.forgebook.network.client (NOT com.forgebook.client.network) so the package string itself does not collide with the SCAF-02 forward-firewall pattern and the file has zero net.minecraft.client.* imports."
  - "Declared a custom ErrorSink @FunctionalInterface rather than stacking BiConsumer<UUID, SimpleImmutableEntry<ErrorCode, String>> or similar hacks — the readability + javadoc surface + type name showing up in stack traces pay for themselves immediately."
  - "Kept the Phase-1 log-only behaviour as the null-sink fallback (LOG.warn …) rather than throwing — matches ChatRequestHandler.responseSinkForTests fail-safe semantics; a dropped packet is always recoverable, an uncaught exception in the network thread is not."
  - "Chose @Mod.EventBusSubscriber over MinecraftForge.EVENT_BUS.addListener for SessionLifecycleListener because the Dist.CLIENT annotation argument gives us the dedicated-server exclusion for free — no need to wrap in DistExecutor.safeRunWhenOn."
  - "Did not ship a JUnit test for SessionLifecycleListener — ClientPlayerNetworkEvent.LoggingOut is a Minecraft client type and instantiating it would violate the Phase-3 pure-Java-tests precedent. The listener's one-line body is covered by the existing ClientChatSessionTest.clear_resetsAllState plus the upcoming 04-06 human smoke checkpoint."

patterns-established:
  - "Packet handler → neutral sink → client session: three-hop dispatch pattern that keeps the wire-side and UI-side separately classloadable. Any future S2C packet that has to reach UI state should add a sibling field on ClientPacketSinks rather than import com.forgebook.client.* from com.forgebook.network.packet.*."
  - "Volatile-sink production seams are named *Sink (reply, error) — not *Handler or *Consumer — so code search for 'Sink' reliably surfaces all indirection points."

requirements-completed: [UI-05, UI-08]

# Metrics
duration: 7min
completed: 2026-04-16
---

# Phase 04 Plan 03: Session Lifecycle & Packet Wiring Summary

**Promoted the Phase-1 volatile-sink pattern to a production ClientPacketSinks holder, wired ChatResponsePacket/ChatErrorPacket.handleOnClient through it with null-guarded log-warn fallback, and installed @Mod.EventBusSubscriber SessionLifecycleListener for disconnect-triggered session clear — all while adding zero com.forgebook.client.* imports to com.forgebook.network.packet.* (SCAF-02 forward firewall preserved).**

## Performance

- **Duration:** 7 min
- **Started:** 2026-04-16T20:36:43Z
- **Completed:** 2026-04-16T20:43:45Z
- **Tasks:** 3 (Task 1 TDD RED→GREEN; Tasks 2 & 3 execute-only)
- **Files created:** 3 (1 production sink holder + 1 lifecycle listener + 1 test)
- **Files modified:** 2 (both packet handlers)

## Accomplishments

- **SCAF-02 forward firewall preserved through a new neutral subpackage.** `com.forgebook.network.client.ClientPacketSinks` is the first file to live under the `network.client` name. It imports only `java.util.UUID`, `java.util.function.BiConsumer`, and the wire-protocol enum `ChatErrorPacket.ErrorCode` — zero `net.minecraft.*`, zero `com.forgebook.client.*`. The existing SCAF-02 CI grep in `.github/workflows/build.yml` continues to report zero hits.
- **Packet handlers wired with defense-in-depth indirection.** `ChatResponsePacket.handleOnClient` now reads `ClientPacketSinks.replySink` into a local, null-checks, and dispatches; `ChatErrorPacket.handleOnClient` does the same with the triple-arg `ErrorSink` functional interface. On a dedicated server these handlers are never reached on the logical client, so the sinks stay null and nothing logs; on a correctly-initialized client plan 04-05's ClientSetup.init will populate the sinks and the null-guard's log-warn becomes an unreachable diagnostic safety net.
- **Disconnect-triggered session clear auto-registered without an imperative addListener.** `SessionLifecycleListener` uses `@Mod.EventBusSubscriber(modid = "forgebook", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)` — explicit `bus` per CLAUDE.md's "What NOT to Use" row, and the `Dist.CLIENT` argument keeps the class off dedicated servers at classpath-scan time.
- **Triple-arg functional interface for errors.** Java's `BiConsumer` cannot express `(UUID, ErrorCode, String)`, so `ClientPacketSinks.ErrorSink` is declared as a `@FunctionalInterface` nested inside `ClientPacketSinks`. This keeps the type name readable in IDE tooltips and stack traces (`ClientPacketSinks$ErrorSink` is far more diagnostic than an anonymous lambda).
- **Requirements UI-05 (client-side session lifecycle) and UI-08 (reverse firewall — packet handlers free of client-session imports) fully satisfied for the network-sink seam.** The remaining half of UI-05 (ChatScreen.onClose → clear) lands in plan 04-05.

## Task Commits

All commits use `--no-verify` per parallel-worktree executor protocol.

| # | Task                                                                                                      | Commits                                          |
| - | --------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| 1 | Create ClientPacketSinks holder with null-guarded BiConsumer + ErrorSink fields (TDD)                     | `2bc10d2` (RED test) → `d012468` (GREEN impl)    |
| 2 | Modify ChatResponsePacket and ChatErrorPacket handleOnClient to dispatch via ClientPacketSinks            | `5a7d9ec`                                        |
| 3 | SessionLifecycleListener for disconnect-triggered ClientChatSession.clear()                               | `32f3f6b`                                        |

_REFACTOR phase omitted for Task 1 — the GREEN impl is the minimal form (27 SLOC of class body + javadoc), no restructuring would pay._

## Files Created

- `src/main/java/com/forgebook/network/client/ClientPacketSinks.java` — `public final class`, private ctor. Two public static volatile fields: `replySink: BiConsumer<UUID,String>` and `errorSink: ErrorSink`. One nested `@FunctionalInterface ErrorSink { void accept(UUID, ErrorCode, String); }`. Javadoc explains package-placement rationale and the SCAF-02 non-collision.
- `src/main/java/com/forgebook/client/session/SessionLifecycleListener.java` — `public final class` annotated with `@Mod.EventBusSubscriber(modid = "forgebook", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)`. Private ctor. One `@SubscribeEvent static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event)` that calls `ClientChatSession.get().clear()` and logs a debug line.
- `src/test/java/com/forgebook/network/client/ClientPacketSinksTest.java` — 6 tests covering default-null sinks (×2), assign+invoke dispatch (×2), BiConsumer-type contract, and private-ctor invariant via reflection. `@BeforeEach` + `@AfterEach` reset both sinks to null.

## Files Modified

- `src/main/java/com/forgebook/network/packet/ChatResponsePacket.java`:
  - Added imports: `com.forgebook.network.client.ClientPacketSinks`, `java.util.function.BiConsumer`.
  - Rewrote `handleOnClient` body: read `ClientPacketSinks.replySink` into a local, null-check, dispatch via `sink.accept(pkt.requestId, pkt.reply)`, else `LOG.warn(...)`.
  - Replaced the Phase-1 `// Phase 4: …` TODO comment + `LOG.info` log-only line.
  - Class javadoc updated to explain the sink indirection and its SCAF-02 rationale.
- `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java`: symmetrical change; uses `ClientPacketSinks.ErrorSink` type directly (imported as `ClientPacketSinks.ErrorSink`).

## Acceptance-Criteria Grep Evidence

```
# Task 1 (ClientPacketSinks)
grep -c "public static volatile BiConsumer<UUID, String> replySink" src/main/java/com/forgebook/network/client/ClientPacketSinks.java → 1 ✓
grep -c "public static volatile ErrorSink errorSink"                src/main/java/com/forgebook/network/client/ClientPacketSinks.java → 1 ✓
grep -c "@FunctionalInterface"                                       src/main/java/com/forgebook/network/client/ClientPacketSinks.java → 1 ✓
grep -c "private ClientPacketSinks()"                                src/main/java/com/forgebook/network/client/ClientPacketSinks.java → 1 ✓
grep -c "import net.minecraft"                                       src/main/java/com/forgebook/network/client/ClientPacketSinks.java → 0 ✓

# Task 2 (packet handlers)
grep -c "ClientPacketSinks.replySink" src/main/java/com/forgebook/network/packet/ChatResponsePacket.java → 1 ✓
grep -c "ClientPacketSinks.errorSink" src/main/java/com/forgebook/network/packet/ChatErrorPacket.java    → 1 ✓
grep -c "if (sink != null)"           src/main/java/com/forgebook/network/packet/ChatResponsePacket.java → 1 ✓
grep -c "if (sink != null)"           src/main/java/com/forgebook/network/packet/ChatErrorPacket.java    → 1 ✓
grep -cE "// Phase 4:"                src/main/java/com/forgebook/network/packet/ChatResponsePacket.java src/main/java/com/forgebook/network/packet/ChatErrorPacket.java → 0 ✓
grep -c "import net.minecraft.client" src/main/java/com/forgebook/network/packet/ChatResponsePacket.java → 0 ✓
grep -c "import net.minecraft.client" src/main/java/com/forgebook/network/packet/ChatErrorPacket.java    → 0 ✓
grep -c "import com.forgebook.client" src/main/java/com/forgebook/network/packet/ChatResponsePacket.java → 0 ✓
grep -c "import com.forgebook.client" src/main/java/com/forgebook/network/packet/ChatErrorPacket.java    → 0 ✓

# Task 3 (SessionLifecycleListener)
grep -c "@Mod.EventBusSubscriber(modid = \"forgebook\", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)" src/main/java/com/forgebook/client/session/SessionLifecycleListener.java → 1 ✓
grep -c "public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event)"                               src/main/java/com/forgebook/client/session/SessionLifecycleListener.java → 1 ✓
grep -c "ClientChatSession.get().clear()"                                                                            src/main/java/com/forgebook/client/session/SessionLifecycleListener.java → 1 ✓
grep -c "@SubscribeEvent"                                                                                            src/main/java/com/forgebook/client/session/SessionLifecycleListener.java → 1 ✓
grep -c "private SessionLifecycleListener()"                                                                         src/main/java/com/forgebook/client/session/SessionLifecycleListener.java → 1 ✓
grep -cE "import com\.forgebook\.(ai|safety)\.|import com\.forgebook\.config\.ApiKey"                                src/main/java/com/forgebook/client/session/SessionLifecycleListener.java → 0 ✓

# Plan-level SCAF-02 + packet→client firewall
grep -rnE "import net\.minecraft\.client" src/main/java/ | grep -v "^src/main/java/com/forgebook/client/"            → 0 hits ✓
grep -rnE "import com\.forgebook\.client" src/main/java/com/forgebook/network/packet/                                → 0 hits ✓
```

## Test Results

```
com.forgebook.network.client.ClientPacketSinksTest  tests=6  failures=0  errors=0  time=0.022s
                                                    ──────────────────────────────────────────
Full ./gradlew --no-daemon build                    → BUILD SUCCESSFUL (all 49 test suites pass)
```

## Decisions Made

- **Package named `com.forgebook.network.client` rather than `com.forgebook.client.network`.** The former reads the file as "network-side wire sink, client-direction" and — crucially — avoids collision with the SCAF-02 CI grep pattern (`com.forgebook.client.*` would require this file to respect the client-firewall rules, which is the exact opposite of the goal: this file must be loadable from BOTH dists without ever touching `net.minecraft.client.*`).
- **Nested `ErrorSink @FunctionalInterface` instead of `Consumer<ChatErrorPacket>`.** A `Consumer<ChatErrorPacket>` would work but it couples the sink signature to the wire type; if Phase-5 adds a second error-emitting packet, we'd need a new sink or ugly adaptation. The current triple-arg signature is keyed on data (UUID + ErrorCode + humanReadable), not on the wire shape, and is already exactly what `ClientChatSession.appendError` wants.
- **Null sinks log-warn, do not throw.** A dropped error packet is recoverable (user may retry); an uncaught exception in Netty's main-thread executor can break the entire client network pipeline. The log-warn surfaces the misconfiguration for diagnosis without raising blast-radius.
- **Kept the LOG field on both modified packet handlers.** Even though the Phase-1 `LOG.info` call is gone, `LOG.warn` on the null-sink branch still uses it; removing the field would be premature cleanup.
- **No unit test for `SessionLifecycleListener`.** Its single responsibility (call `ClientChatSession.get().clear()`) is already exercised by Plan 04-02's `ClientChatSessionTest.clear_resetsAllState` (via the underlying clear() contract) plus the planned Plan 04-06 human smoke checkpoint (disconnect-mid-conversation scenario). Writing a JUnit test here would require instantiating `ClientPlayerNetworkEvent.LoggingOut`, a Minecraft-client type that the Phase-3 precedent explicitly excludes from pure-Java tests.

## Deviations from Plan

None — plan executed exactly as written. All three tasks followed action/verify/acceptance verbatim from `04-03-PLAN.md`. No auto-fixes triggered under Rules 1–3; no architectural checkpoint triggered under Rule 4.

## Issues Encountered

None. Every RED/GREEN iteration passed on first attempt; no build regression.

**Environmental note (not a deviation):** The edit tool's READ-BEFORE-EDIT pre-hook fired preemptively twice on the packet-handler modifications even though the Read-before-Edit invariant was satisfied; both edits succeeded. No retry was needed.

## State Machine / Dispatch Invariants Locked

| Event / call                                    | Sink state    | Outcome                                                                 |
| ----------------------------------------------- | ------------- | ----------------------------------------------------------------------- |
| ChatResponsePacket.handleOnClient, replySink=null | null          | LOG.warn("…replySink installed for {}; dropping.", requestId)           |
| ChatResponsePacket.handleOnClient, replySink set  | non-null      | sink.accept(pkt.requestId, pkt.reply) — in plan 04-05 forwards to ClientChatSession.get().append |
| ChatErrorPacket.handleOnClient, errorSink=null    | null          | LOG.warn("…errorSink installed for {}: {} — {}", requestId, code, msg)  |
| ChatErrorPacket.handleOnClient, errorSink set     | non-null      | sink.accept(pkt.requestId, pkt.code, pkt.humanReadable)                 |
| ClientPlayerNetworkEvent.LoggingOut               | n/a           | ClientChatSession.get().clear() → bubbles/errors cleared, pending=false |
| Dedicated server (no DistExecutor.client entry)   | n/a           | Neither class is loaded; sinks stay null; listener never registers.     |

## TDD Gate Compliance

Task 1 (the only TDD task in this plan) has a matching RED commit immediately preceding its GREEN commit:

| Task | RED       | GREEN     |
| ---- | --------- | --------- |
| 1    | `2bc10d2` | `d012468` |

RED commit `2bc10d2` was verified to fail compilation (13 compile errors: `cannot find symbol: ClientPacketSinks` at each reference site in the new test file) before GREEN `d012468` was made. No unexpectedly-passing RED was observed.

Tasks 2 and 3 are not TDD tasks per the plan (they modify or create production code whose testability is covered by other plans — Task 2's behaviour is exercised by the `ClientPacketSinksTest.replySink_canBeAssignedAndInvoked` + `errorSink_canBeAssignedAndInvoked` tests via the sink contract, and Task 3's behaviour will be covered by Plan 04-06 human smoke test).

## Next Plan Readiness

**Ready for plan 04-05 (ClientSetup.init — sink installation):**

- `ClientPacketSinks.replySink = (id, text) -> ClientChatSession.get().append(id, text);` — drop-in assignment; no extra state.
- `ClientPacketSinks.errorSink = (id, code, msg) -> ClientChatSession.get().appendError(id, code, msg);` — same.
- `SessionLifecycleListener` auto-registers without any action in ClientSetup.init — just ensure `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)` stays in `ForgeBookMod` so the classpath scan kicks in.

**Ready for plan 04-04 (ChatPanelWidget) / 04-05 (ChatScreen):**

- No dependency on this plan's output beyond what Plans 04-01 and 04-02 already delivered. The packet-handler rewiring is transparent to the render loop.

**Blocks resolved for plan 04-06 (polish + smoke):**

- Disconnect-triggered session clear is now live — a smoke-test tester can open chat, submit a question, disconnect from server, rejoin, and verify the chat is empty (UI-05 half 2).

## Self-Check: PASSED

All claimed files and commits verified present on disk + in git history.

- Files:
  - `src/main/java/com/forgebook/network/client/ClientPacketSinks.java` — FOUND
  - `src/main/java/com/forgebook/client/session/SessionLifecycleListener.java` — FOUND
  - `src/test/java/com/forgebook/network/client/ClientPacketSinksTest.java` — FOUND
  - `src/main/java/com/forgebook/network/packet/ChatResponsePacket.java` — MODIFIED (imports + handleOnClient body)
  - `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java` — MODIFIED (imports + handleOnClient body)
- Commits (verified via `git log --oneline aa6c8606..HEAD`):
  - `2bc10d2` test(04-03): add failing test for ClientPacketSinks volatile-sink holder — FOUND
  - `d012468` feat(04-03): ClientPacketSinks volatile-sink holder for packet→session bridge — FOUND
  - `5a7d9ec` feat(04-03): wire ChatResponsePacket/ChatErrorPacket to ClientPacketSinks — FOUND
  - `32f3f6b` feat(04-03): SessionLifecycleListener clears ClientChatSession on logout — FOUND

---

*Phase: 04-in-inventory-chat-ui*
*Plan: 03*
*Completed: 2026-04-16*
