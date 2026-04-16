---
phase: 03-command-surface-safety-controls
plan: "01"
subsystem: safety
tags: [safety, primitives, rate-limiter, kill-switch, token-bucket, request-kind, dispatch-context, concurrent-hashmap, atomic-boolean, sealed-interface]

# Dependency graph
requires:
  - phase: 02-ai-engine-grounding
    provides: ConfigSnapshot.rateLimitPerMinute() accessor, ChatErrorPacket.ErrorCode enum (RATE_LIMITED / FORBIDDEN / DISABLED reused), SystemPromptCache volatile-holder analog, ModpackContextCache holder analog, RetryPolicy pure-compute analog
provides:
  - RequestKind enum (CHAT_UI / ASK / ITEM) for dispatch routing + audit logging
  - DispatchContext record carrying (message, sender, kind) for server-side orchestration
  - KillSwitch static volatile-holder (AtomicBoolean DISABLED) for CMD-05
  - TokenBucket package-private per-UUID primitive (synchronized tryAcquire + refill math)
  - RateLimiter ConcurrentHashMap<UUID, TokenBucket> with sealed Outcome { Allowed | Limited(retryAfterSeconds) }
  - RateLimiterHolder volatile-holder singleton (get / swap for reload semantics)
affects:
  - 03-02-audit-stats (consumes RequestKind for RequestAuditLogger.log*)
  - 03-03-authorizer (consumes KillSwitch.isDisabled, RateLimiterHolder.get, RateLimiter.Outcome)
  - 03-04-rag-item-pipeline (consumes DispatchContext, RequestKind.ITEM)
  - 03-05-subcommands-wiring (consumes DispatchContext, RequestKind.ASK, KillSwitch.setDisabled)
  - 03-06-reload-integration (swaps RateLimiterHolder on config reload)
  - 04-chat-ui-client (Phase 4 inventory chat eventually reads RequestKind.CHAT_UI)

# Tech tracking
tech-stack:
  added:
    - "java.util.concurrent.atomic.AtomicBoolean — CMD-05 kill-switch storage (documents intent vs. plain volatile boolean)"
    - "java.util.concurrent.ConcurrentHashMap<UUID, TokenBucket> — per-player bucket store with computeIfAbsent lazy creation"
    - "Sealed interface + record variants (Java 17) — RateLimiter.Outcome { Allowed | Limited } mirrors prior AiDispatcher.mapError sealed-Result idiom"
  patterns:
    - "Volatile-holder singleton (private ctor + private static volatile field + static get/swap) — third instance of the pattern (after SystemPromptCache, ModpackContextCache, ConfigHolder)"
    - "Package-private primitive (TokenBucket) exposed only through a same-package façade (RateLimiter) — prevents accidental public coupling"
    - "SAFE layer is key-agnostic — com.forgebook.safety.* has zero com.forgebook.client and zero ApiKey references (verified by grep; CI .raw() lint already excludes this package by construction)"

key-files:
  created:
    - "src/main/java/com/forgebook/ai/RequestKind.java — public enum { CHAT_UI, ASK, ITEM }"
    - "src/main/java/com/forgebook/ai/DispatchContext.java — public record (String message, ServerPlayer sender, RequestKind kind)"
    - "src/main/java/com/forgebook/safety/KillSwitch.java — public final class with static isDisabled/setDisabled backed by AtomicBoolean"
    - "src/main/java/com/forgebook/safety/TokenBucket.java — package-private final class with synchronized tryAcquire(capacity, refillPerSec) returning RateLimiter.Outcome"
    - "src/main/java/com/forgebook/safety/RateLimiter.java — public final class wrapping ConcurrentHashMap<UUID, TokenBucket> + sealed Outcome permits Allowed, Limited"
    - "src/main/java/com/forgebook/safety/RateLimiterHolder.java — public final class with private static volatile RateLimiter current + get/swap"
    - "src/test/java/com/forgebook/safety/RateLimiterTest.java — 5 JUnit 5 test cases"
    - "src/test/java/com/forgebook/safety/KillSwitchTest.java — 4 JUnit 5 test cases"
  modified: []

key-decisions:
  - "AtomicBoolean over plain volatile boolean for KillSwitch — documents 'flag multiple threads read, one thread writes' intent even though no compound operation is needed"
  - "RateLimiterHolder.get() returns null before seeding (not Optional, not a default fallback instance) — matches ModpackContextCache's defensive-pre-seed contract. Plan 06 is responsible for seeding on ServerStartingEvent"
  - "TokenBucket is package-private — only RateLimiter (same package) can construct or invoke it, preventing misuse from outside com.forgebook.safety"
  - "Retry-after floor of 1 second (Math.max(1L, ceil(secondsToOne))) — SAFE-03 contract: human-readable 'try again in Ns' must cite a truthy count"
  - "Zero-or-negative RPM coerced to capacity=1 — no divide-by-zero; a misconfigured server always admits at least one request per bucket-window"

patterns-established:
  - "Safety primitive layer: all com.forgebook.safety.* classes depend only on JDK 17 stdlib (no Forge, no Minecraft, no Gson, no AI). Headless-testable with pure JUnit 5"
  - "Sealed Outcome result types: prefer sealed interface + record variants (Allowed / Limited(data)) over nullable return or thrown exceptions for expected control flow"
  - "Volatile-holder singleton for runtime overrides not in ConfigSnapshot: KillSwitch pattern — intentionally NOT a config field because /forgebook reload must not reset it"

requirements-completed:
  - CMD-05
  - SAFE-02
  - SAFE-03

# Metrics
duration: 7.4min
completed: 2026-04-16
---

# Phase 03 Plan 01: Safety Primitives Summary

**Type + safety primitive layer (RequestKind enum, DispatchContext record, KillSwitch AtomicBoolean, TokenBucket/RateLimiter/RateLimiterHolder) with 9 passing unit tests — zero net.minecraft or ApiKey imports in com.forgebook.safety.***

## Performance

- **Duration:** 7.4 min
- **Started:** 2026-04-16T14:56:06Z
- **Completed:** 2026-04-16T15:03:31Z
- **Tasks:** 2
- **Files created:** 8 (6 production + 2 test)
- **Files modified:** 0

## Accomplishments

- Shipped 6 production files for the Phase 3 safety primitive layer — every downstream plan (02 audit, 03 authorizer, 04 RAG pipeline, 05 subcommands, 06 reload) now has the exact API surface it declared dependencies on in this wave.
- RateLimiter validated against the SAFE-02 / SAFE-03 contracts:
  - 5 RPM admits 5 → limits the 6th with `retryAfterSeconds >= 1` (fiveRequestsPerMinute_allowsFiveThenLimits)
  - `0` and negative RPM coerced to capacity 1 (zeroRpmIsCoercedToOne_noDivideByZero + negativeRpmIsCoercedToOne_noException)
  - Separate UUIDs have independent buckets (separateUuidsHaveIndependentBuckets)
  - Sealed Outcome is exhaustive via instanceof chain (sealedOutcome_exhaustiveInstanceofHandling)
- KillSwitch validated against CMD-05:
  - Default state is enabled (isDisabled() → false)
  - `setDisabled(true/false)` flips idempotently
  - Concurrent read from a second thread observes the latest write (happens-before via AtomicBoolean)
- Architecture invariant preserved: zero `com.forgebook.client.*` and zero `ApiKey` references in `com.forgebook.safety.*` (grep verified). The safety layer is key-agnostic by construction.

## Task Commits

Each task was committed atomically:

1. **Task 1: RequestKind + DispatchContext + KillSwitch + TokenBucket (pure primitives)** — `954301d` (feat)
2. **Task 2 RED: add failing tests for RateLimiter + KillSwitch** — `dcd7e75` (test)
3. **Task 2 GREEN: implement RateLimiter + RateLimiterHolder** — `145c2e4` (feat)

_Note: Task 2 is tdd=true, split into test-first (RED) and implementation (GREEN) commits per TDD gate policy. The RED commit was validated by running `./gradlew compileTestJava` — it failed with `package RateLimiter does not exist`, proving the tests actually depend on the code that does not yet exist. The GREEN commit brings all 9 tests to BUILD SUCCESSFUL._

## Files Created

- `src/main/java/com/forgebook/ai/RequestKind.java` — enum with three values (CHAT_UI, ASK, ITEM) for dispatch routing and audit logging
- `src/main/java/com/forgebook/ai/DispatchContext.java` — immutable record carrying (message, sender, kind) — replaces the Phase 2 (String, ServerPlayer) pair passed to AiDispatcher.dispatch
- `src/main/java/com/forgebook/safety/KillSwitch.java` — CMD-05 global kill switch. AtomicBoolean-backed, private ctor, static isDisabled/setDisabled
- `src/main/java/com/forgebook/safety/TokenBucket.java` — package-private per-UUID bucket with synchronized tryAcquire(capacity, refillPerSec) → RateLimiter.Outcome
- `src/main/java/com/forgebook/safety/RateLimiter.java` — public ConcurrentHashMap<UUID, TokenBucket> wrapper with sealed Outcome permits Allowed, Limited(retryAfterSeconds)
- `src/main/java/com/forgebook/safety/RateLimiterHolder.java` — volatile-holder singleton; get() returns null pre-seed, swap() is a single volatile store
- `src/test/java/com/forgebook/safety/RateLimiterTest.java` — 5 JUnit 5 cases (see Performance section)
- `src/test/java/com/forgebook/safety/KillSwitchTest.java` — 4 JUnit 5 cases (see Performance section)

## Decisions Made

- **AtomicBoolean over plain volatile boolean** for KillSwitch — no compound operation is performed, so `volatile boolean DISABLED` would also be correct, but AtomicBoolean documents the "flag that multiple threads read and one thread writes" intent more clearly. Matches research §Pattern 5 guidance.
- **RateLimiterHolder.get() returns null pre-seed** (not Optional, not a default-fallback instance). Mirrors ModpackContextCache's defensive contract — callers (Authorizer in Plan 03, ChatRequestHandler in Plan 05) must null-check. In practice, seeding happens on ServerStartingEvent strictly before any packet or command can be served; Plan 06 owns the seeding call.
- **Retry-after floor of 1 second**. `Math.max(1L, (long) Math.ceil(secondsToOne))` ensures SAFE-03's human-readable "try again in Ns" message always cites a truthy count, even when `secondsToOne` rounds to 0 at very high RPM settings.
- **Zero-or-negative RPM coerced to capacity=1**. `Math.max(1, requestsPerMinute)`. No divide-by-zero on `refillPerSec = capacity / 60.0`; a misconfigured `rate_limit_per_minute = 0` admits one request per minute instead of DoS'ing every player.
- **TokenBucket is package-private**. Only RateLimiter (same package) can construct or invoke it. Prevents T-03-01-04 (elevation via direct TokenBucket usage from outside the safety layer).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Deferred Task 1's compileJava verification until Task 2 implementation landed**
- **Found during:** Task 1 (after writing the four primitive files)
- **Issue:** Plan's Task 1 verify clause is `./gradlew compileJava -q`, but `TokenBucket.java` (a Task 1 file) references `RateLimiter.Outcome`, `RateLimiter.Allowed`, and `RateLimiter.Limited` — types that are created only in Task 2. Running `compileJava` after Task 1 alone reproducibly fails with `package RateLimiter does not exist` (3 errors). The plan's Task 1 verification is structurally impossible in isolation.
- **Fix:** Committed Task 1's four files verbatim (matching the plan's template byte-for-byte), deferred the `./gradlew compileJava` assertion until after Task 2's RateLimiter.java landed. Full verification ran after `145c2e4` (GREEN commit) and exited 0.
- **Files modified:** None — the fix was a sequencing adjustment, not a code change. Source files exactly match the plan's code blocks.
- **Verification:** `./gradlew compileJava -q` returns exit 0 after Task 2. `./gradlew test --tests "com.forgebook.safety.*"` reports BUILD SUCCESSFUL with 9/9 passing.
- **Committed in:** `954301d` (Task 1), `dcd7e75` (Task 2 RED), `145c2e4` (Task 2 GREEN)

**2. [Rule 3 - Blocking] Corrected Write-tool file placement from main repo into worktree**
- **Found during:** Task 1 (immediately after first Write calls, before any commit)
- **Issue:** Initial Write calls used the project-root absolute path `C:\Users\Nick\IdeaProjects\ForgeBook\src\...` which lands in the main repo working tree, not the `.claude\worktrees\agent-a0d6f780` worktree this agent runs in. `git status` in the worktree showed a clean tree; the new files were untracked in the main repo instead.
- **Fix:** `cp` each file from the main repo path into the worktree path, then `rm -f` / `rm -rf` the stray copies in the main repo. Subsequent Write calls use the full worktree absolute path (`C:\Users\Nick\IdeaProjects\ForgeBook\.claude\worktrees\agent-a0d6f780\...`) to avoid recurrence.
- **Files modified:** None in the main repo after cleanup (verified); all plan files exclusively in the worktree.
- **Verification:** `cd "C:/Users/Nick/IdeaProjects/ForgeBook" && git status --short` no longer lists `src/main/java/com/forgebook/safety/` or the new RequestKind.java / DispatchContext.java as untracked.
- **Committed in:** `954301d` (the corrected content was what was staged; no rewrite needed)

---

**Total deviations:** 2 auto-fixed (2 × Rule 3 blocking)
**Impact on plan:** Neither deviation changed code content — both were sequencing/tooling corrections. Plan's intended artifact set ships exactly as specified. No scope creep.

## Issues Encountered

- Line-ending warnings from Git ("LF will be replaced by CRLF"). Expected on Windows; no action taken. The committed files have the line endings Git autocrlf prescribes for this repo.

## TDD Gate Compliance

Plan-level TDD gate sequence for Task 2 (type tdd=true):

1. ✓ RED gate — `dcd7e75` (test commit) — `./gradlew compileTestJava` fails with `package RateLimiter does not exist` (verified before Task 2 GREEN)
2. ✓ GREEN gate — `145c2e4` (feat commit) — `./gradlew test --tests "com.forgebook.safety.*"` reports BUILD SUCCESSFUL, 9/9 passing
3. REFACTOR — not applicable; implementation is already minimal and matches the plan's template byte-for-byte.

## Threat Flags

No new threat surface introduced beyond the plan's existing threat register (T-03-01-01 through T-03-01-05). The safety layer adds zero network, file-IO, or authentication surface — only in-memory JDK primitives.

## Next Phase Readiness

**Plan 02 (audit/stats) can proceed in parallel** — its `files_modified` set does not overlap with this plan, and it consumes `RequestKind` (now shipped) for `RequestAuditLogger.log*` signatures.

**Plan 03 (Authorizer) is unblocked** — `KillSwitch.isDisabled()`, `RateLimiterHolder.get()`, `RateLimiter.Outcome` (sealed with Allowed / Limited) are all available at the signatures declared in the plan frontmatter.

**Plan 04 (RAG item pipeline), Plan 05 (subcommands + network), Plan 06 (reload integration)** — all declared dependencies on this wave's primitives are satisfied.

**Statement required by plan:** Plan 01 is pure primitives — no Forge / AI / network deps introduced. Only JDK 17 stdlib (`java.util.concurrent.atomic.AtomicBoolean`, `java.util.concurrent.ConcurrentHashMap`, `java.util.UUID`) plus `net.minecraft.server.level.ServerPlayer` referenced solely from `DispatchContext.java` (which lives in `com.forgebook.ai`, not `com.forgebook.safety`). `com.forgebook.safety.*` has zero client / Forge / AI / network imports.

## Self-Check: PASSED

All 6 production files + 2 test files verified present at their declared paths:
- `src/main/java/com/forgebook/ai/RequestKind.java`
- `src/main/java/com/forgebook/ai/DispatchContext.java`
- `src/main/java/com/forgebook/safety/KillSwitch.java`
- `src/main/java/com/forgebook/safety/TokenBucket.java`
- `src/main/java/com/forgebook/safety/RateLimiter.java`
- `src/main/java/com/forgebook/safety/RateLimiterHolder.java`
- `src/test/java/com/forgebook/safety/RateLimiterTest.java`
- `src/test/java/com/forgebook/safety/KillSwitchTest.java`

All 3 task commits verified present in `git log --oneline --all`:
- `954301d` (Task 1)
- `dcd7e75` (Task 2 RED)
- `145c2e4` (Task 2 GREEN)

---
*Phase: 03-command-surface-safety-controls*
*Plan: 01*
*Completed: 2026-04-16*
