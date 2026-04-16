---
phase: 04-in-inventory-chat-ui
plan: 01
subsystem: ui
tags: [value-types, sealed-interface, record, error-taxonomy, pure-function, tdd, forge-1.20.1]

# Dependency graph
requires:
  - phase: 01-wire-format-and-packet-plumbing
    provides: ChatErrorPacket.ErrorCode enum (6 values) consumed by ErrorCard
  - phase: 03-server-gates-and-commands
    provides: sealed-interface + record-variants precedent (RateLimiter.Outcome); exhaustive enum-switch precedent (Authorizer)
provides:
  - ChatEntry sealed interface (permits MessageBubble, ErrorCard)
  - MessageBubble record with USER/ASSISTANT kinds and pure computeBubbleHeight math helper
  - ErrorCard record with stripeColor / headingKey / bodyKey static lookups (exhaustive over all 6 ErrorCode values)
  - LoadingIndicator.frame(nowMs) pure dot cycler (500 ms frame, 2000 ms period)
affects: [04-02-ClientChatSession, 04-03-InventoryButton, 04-04-ChatPanelWidget, 04-05-ChatScreen, 04-06-Polish]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Sealed-interface + record-variants value types (ChatEntry permits MessageBubble, ErrorCard)"
    - "Exhaustive switch expression on ErrorCode — compiler enforces handling of every enum value"
    - "Pure-function test seam: time/layout math extracted so unit tests never touch net.minecraft"
    - "Reverse package firewall (UI-08): com.forgebook.client.ui never imports com.forgebook.{ai,safety}.* or com.forgebook.config.ApiKey"

key-files:
  created:
    - src/main/java/com/forgebook/client/ui/ChatEntry.java
    - src/main/java/com/forgebook/client/ui/MessageBubble.java
    - src/main/java/com/forgebook/client/ui/ErrorCard.java
    - src/main/java/com/forgebook/client/ui/LoadingIndicator.java
    - src/test/java/com/forgebook/client/ui/MessageBubbleWrapMathTest.java
    - src/test/java/com/forgebook/client/ui/ErrorCodeColorMapTest.java
    - src/test/java/com/forgebook/client/ui/LoadingIndicatorTest.java
  modified: []

key-decisions:
  - "Placed ErrorCard.stripeColor/headingKey/bodyKey as static lookups on the record rather than a separate ErrorStyle helper — keeps the UI-SPEC color table literally adjacent to the data type it styles and the compiler's exhaustive-switch check guards the value type directly."
  - "Used Math.floorMod in LoadingIndicator.frame so negative or wrap-around nowMs values still return a valid frame string (defensive for long-running client sessions)."
  - "ChatEntry permits both MessageBubble and ErrorCard in Task 1 — required a minimal ErrorCard stub to compile before Task 2 expanded it (sealed interfaces require all permitted types to exist in the module)."

patterns-established:
  - "UI value types live in com.forgebook.client.ui and import zero net.minecraft.*, zero com.forgebook.{ai,safety}.*, zero com.forgebook.config.ApiKey — enforced by grep acceptance criteria and will be hardened by plan 04-05's CI rule."
  - "Pure-function static helpers on records enable unit testing color tables, bubble geometry, and animation cadence without a Font, GuiGraphics, or game tick loop."

requirements-completed: [UI-04]

# Metrics
duration: 9min
completed: 2026-04-16
---

# Phase 04 Plan 01: In-Inventory Chat UI — Value Types & Error Taxonomy Summary

**Sealed ChatEntry hierarchy with MessageBubble, ErrorCard (ARGB stripe colors + i18n keys for all 6 Phase-3 ErrorCode values), and a pure-function LoadingIndicator dot cycler — zero net.minecraft imports, 19/19 TDD tests green.**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-04-16T20:21:30Z
- **Completed:** 2026-04-16T20:30:32Z
- **Tasks:** 3 (all TDD: RED → GREEN per task)
- **Files created:** 7 (4 production + 3 test)
- **Files modified:** 0

## Accomplishments

- ChatEntry sealed interface established as the discriminated union for conversation entries, ready for exhaustive instanceof pattern-matching in plan 04-04's render loop.
- MessageBubble value type locks the USER/ASSISTANT differentiation at the type level and exposes a pure computeBubbleHeight(lineCount, paddingTop, paddingBottom, lineHeight, lineGap) helper — UI-SPEC's 4/4 padding + 9 lineHeight + 1 gap numbers are now unit-tested against explicit expected values (18 px for 1 line, 38 px for 3 lines, 8 px for empty).
- ErrorCard locks UI-SPEC's entire Phase-3 Error Taxonomy → UI Mapping table as source of truth: TRANSPORT/OVERLOADED = 0xFFF5A623 (amber), RATE_LIMITED = 0xFF4A90E2 (blue), FORBIDDEN/PROVIDER = 0xFFE74C3C (red), DISABLED = 0xFF808080 (gray). Exhaustive switch expression means adding a 7th ErrorCode in a future phase breaks the build — by design.
- LoadingIndicator.frame(long) delivers the "Thinking…" dot cadence as a pure function of time; deterministic under unit test and unaffected by game tick drift.
- All three files satisfy the UI-08 reverse firewall a plan-level grep: zero net.minecraft.*, zero com.forgebook.{ai,safety}.*, zero com.forgebook.config.ApiKey imports across every file under src/main/java/com/forgebook/client/ui/.

## Task Commits

Each task followed RED → GREEN TDD:

1. **Task 1: ChatEntry + MessageBubble + bubble-height math**
   - `e589d61` test(04-01): add failing test for MessageBubble value type and bubble-height math
   - `908ae06` feat(04-01): ChatEntry sealed interface + MessageBubble value type + bubble-height math
2. **Task 2: ErrorCard color + i18n lookup tables**
   - `7a325b9` test(04-01): add failing test for ErrorCode→color/i18n lookup table
   - `0ec3681` feat(04-01): ErrorCard stripeColor/headingKey/bodyKey lookup tables
3. **Task 3: LoadingIndicator dot cycler**
   - `0218d52` test(04-01): add failing test for LoadingIndicator dot-cycler cadence
   - `208be51` feat(04-01): LoadingIndicator pure-function dot cycler

_REFACTOR phase omitted for all three tasks — production code was already minimal on arrival at GREEN; no restructuring benefit justified a third commit per task._

## Files Created

- `src/main/java/com/forgebook/client/ui/ChatEntry.java` — Sealed interface `permits MessageBubble, ErrorCard`; no body; 8 LOC.
- `src/main/java/com/forgebook/client/ui/MessageBubble.java` — Record `(Kind kind, String text)` with `Kind { USER, ASSISTANT }`, `user(String)`/`assistant(String)` factories, and static `computeBubbleHeight(lineCount, paddingTop, paddingBottom, lineHeight, lineGap)` helper.
- `src/main/java/com/forgebook/client/ui/ErrorCard.java` — Record `(ChatErrorPacket.ErrorCode code, String humanReadable)` with three static exhaustive-switch lookups: `stripeColor`, `headingKey`, `bodyKey`.
- `src/main/java/com/forgebook/client/ui/LoadingIndicator.java` — Final class with private constructor; `public static String frame(long nowMs)` using `Math.floorMod(nowMs, PERIOD_MS)` and index-to-string switch; constants `PERIOD_MS = 2000L`, `FRAME_MS = 500L`.
- `src/test/java/com/forgebook/client/ui/MessageBubbleWrapMathTest.java` — 6 tests (factories, Kind, computeBubbleHeight 1/3/0 lines, ChatEntry membership).
- `src/test/java/com/forgebook/client/ui/ErrorCodeColorMapTest.java` — 7 tests (6 per-code color assertions + 1 enumerative loop asserting non-zero color and `forgebook.error.*` i18n key prefix for every `ErrorCode.values()`).
- `src/test/java/com/forgebook/client/ui/LoadingIndicatorTest.java` — 6 tests (0ms '.', 500ms '..', 1000ms '...', 1500ms '', 2000ms wraps, 2500ms wraps).

## Acceptance-Criteria Grep Evidence

```
grep -c "sealed interface ChatEntry permits MessageBubble, ErrorCard" ChatEntry.java                    → 1 ✓
grep -c "public record MessageBubble(Kind kind, String text) implements ChatEntry" MessageBubble.java → 1 ✓
grep -c "public enum Kind { USER, ASSISTANT }" MessageBubble.java                                      → 1 ✓
grep -cE "public static int computeBubbleHeight\(int lineCount" MessageBubble.java                     → 1 ✓
grep -c "public record ErrorCard(ChatErrorPacket.ErrorCode code, String humanReadable) implements ChatEntry" ErrorCard.java → 1 ✓
grep -c "public static int stripeColor(ChatErrorPacket.ErrorCode code)" ErrorCard.java                 → 1 ✓
grep -c "0xFFF5A623" ErrorCard.java  (TRANSPORT + OVERLOADED)                                           → 2 ✓
grep -c "0xFFE74C3C" ErrorCard.java  (FORBIDDEN + PROVIDER)                                             → 2 ✓
grep -c "0xFF4A90E2" ErrorCard.java  (RATE_LIMITED)                                                     → 1 ✓
grep -c "0xFF808080" ErrorCard.java  (DISABLED)                                                         → 1 ✓
grep -c "public static final long PERIOD_MS = 2000L" LoadingIndicator.java                              → 1 ✓
grep -c "public static String frame(long nowMs)" LoadingIndicator.java                                  → 1 ✓
grep -c "Math.floorMod(nowMs, PERIOD_MS)" LoadingIndicator.java                                         → 1 ✓
grep -rE "import com\.forgebook\.(ai|safety)\.|import com\.forgebook\.config\.ApiKey" src/main/java/com/forgebook/client/ui/ → 0 hits ✓
grep -rE "import net\.minecraft" src/main/java/com/forgebook/client/ui/                                  → 0 hits ✓
```

## Test Results

```
com.forgebook.client.ui.MessageBubbleWrapMathTest  tests=6  failures=0  errors=0
com.forgebook.client.ui.ErrorCodeColorMapTest      tests=7  failures=0  errors=0
com.forgebook.client.ui.LoadingIndicatorTest       tests=6  failures=0  errors=0
                                                   ─────────────────────────────
                                                   TOTAL: 19 tests, 0 failures, 0 errors
```

Full `./gradlew --no-daemon build -x test` also passes — no upstream compile breakage from the new package.

## Decisions Made

- **Static lookups on the record rather than a separate ErrorStyle helper class.** Keeps the UI-SPEC color table adjacent to ErrorCard; lets the compiler's exhaustive-switch check guard the value type directly; avoids proliferating thin helper classes.
- **`Math.floorMod(nowMs, PERIOD_MS)` instead of `nowMs % PERIOD_MS`.** Defensive — negative timestamps (e.g., if `System.currentTimeMillis()` is ever clock-skewed backward between client ticks) still produce a valid non-negative phase.
- **ErrorCard created as a minimal record stub in Task 1 GREEN, then expanded in Task 2 GREEN.** Required because ChatEntry's sealed `permits MessageBubble, ErrorCard` needs ErrorCard to exist for Task 1 compilation. The stub form (`public record ErrorCard(ChatErrorPacket.ErrorCode code, String humanReadable) implements ChatEntry {}`) was exactly the Task 2 RED baseline — so the Task 2 RED test still failed to compile as designed (stripeColor/headingKey/bodyKey were missing), and the diff in Task 2 GREEN cleanly shows the lookup methods being added.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created ErrorCard stub in Task 1 instead of only Task 2**

- **Found during:** Task 1 (ChatEntry sealed interface + MessageBubble)
- **Issue:** `ChatEntry` is declared `sealed interface ChatEntry permits MessageBubble, ErrorCard` per the plan. A sealed interface with a permit on a non-existent class does not compile. Task 1's GREEN phase would have failed to compile without at least a minimal ErrorCard class.
- **Fix:** Created `ErrorCard.java` in Task 1 GREEN as the minimal form (record declaration only, no methods beyond the canonical accessors). Task 2 GREEN then expanded it with `stripeColor`/`headingKey`/`bodyKey` helpers — exactly matching the plan's Task 2 action content.
- **Files modified:** `src/main/java/com/forgebook/client/ui/ErrorCard.java`
- **Verification:** Task 2 RED still failed to compile (missing `stripeColor`/`headingKey`/`bodyKey`) before Task 2 GREEN — so the test-first property was preserved.
- **Committed in:** `908ae06` (Task 1 GREEN) and `0ec3681` (Task 2 GREEN).

---

**Total deviations:** 1 auto-fixed (1 blocking).
**Impact on plan:** None — the plan's files_modified list already includes ErrorCard.java and the Task 2 RED baseline is unchanged. The fix is an ordering adjustment, not a scope change.

## Issues Encountered

- **Initial Write path confusion.** The worktree directory (`agent-a4fdd223`) was shadowed by another worktree name (`confident-heyrovsky`) that exists on the same tree. An early test-file Write landed in the wrong worktree before being redirected; the wrong-location file was deleted and recreated at the correct worktree root. No committed artifacts were affected; no retry of test RED was needed once paths were straightened.

## Next Phase / Plan Readiness

- Plans 04-02 (ClientChatSession) and 04-04 (ChatPanelWidget) can now import `com.forgebook.client.ui.ChatEntry`, `MessageBubble`, `ErrorCard`, `LoadingIndicator` freely — all four are pure-data / pure-function and will not trigger the SCAF-02 `net.minecraft.client.*` grep.
- Plan 04-05 should add the UI-08 reverse-firewall CI grep to `.github/workflows/build.yml` — this plan pre-complies with the rule but does not install it.
- Plan 04-04's render loop can use `switch (entry) { case MessageBubble mb -> …; case ErrorCard ec -> …; }` pattern-matching without a default branch — the sealed interface guarantees exhaustiveness.
- UI-04 requirement is **partially satisfied**: color mapping + value types are done. The visual rendering of the error card (the 4-px stripe, heading text, wrap) arrives in plan 04-04.

## TDD Gate Compliance

Each task has a matching `test(04-01): …` RED commit immediately preceding its `feat(04-01): …` GREEN commit:

| Task | RED       | GREEN     |
| ---- | --------- | --------- |
| 1    | `e589d61` | `908ae06` |
| 2    | `7a325b9` | `0ec3681` |
| 3    | `0218d52` | `208be51` |

All RED commits were verified to fail compilation (missing production symbols) before their corresponding GREEN commit; no GREEN commit was made without its RED predecessor. No unexpectedly-passing RED was observed.

## Self-Check: PASSED

All claimed files exist and all claimed commits are present in the worktree history.

- Files:
  - `src/main/java/com/forgebook/client/ui/ChatEntry.java` — FOUND
  - `src/main/java/com/forgebook/client/ui/MessageBubble.java` — FOUND
  - `src/main/java/com/forgebook/client/ui/ErrorCard.java` — FOUND
  - `src/main/java/com/forgebook/client/ui/LoadingIndicator.java` — FOUND
  - `src/test/java/com/forgebook/client/ui/MessageBubbleWrapMathTest.java` — FOUND
  - `src/test/java/com/forgebook/client/ui/ErrorCodeColorMapTest.java` — FOUND
  - `src/test/java/com/forgebook/client/ui/LoadingIndicatorTest.java` — FOUND
- Commits: `e589d61`, `908ae06`, `7a325b9`, `0ec3681`, `0218d52`, `208be51` — all FOUND.

---

*Phase: 04-in-inventory-chat-ui*
*Plan: 01*
*Completed: 2026-04-16*
