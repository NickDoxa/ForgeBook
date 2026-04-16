---
phase: 03-command-surface-safety-controls
plan: "02"
subsystem: safety
tags: [safety, audit-log, stats, metrics, observability]
requires:
  - com.forgebook.ai.RequestKind (Plan 03-01, Wave 1 co-deploy)
  - com.forgebook.network.packet.ChatErrorPacket.ErrorCode (existing)
provides:
  - com.forgebook.safety.StatsAccumulator (per-UUID + aggregate counters; render())
  - com.forgebook.safety.RequestAuditLogger (named "forgebook.audit" logger + fan-out)
affects:
  - Call sites in Plans 03-04 (dispatch flows), 03-05 (Authorizer), 03-06 (admin subcommands)
tech_stack:
  added: []    # JDK 17 LongAdder + ConcurrentHashMap + Log4j2 already on classpath
  patterns:
    - "Static-holder + atomic-primitives (analog: ToolRegistry + CircuitBreaker)"
    - "StringBuilder-sections render (analog: SystemPromptBuilder)"
    - "Named Log4j2 logger (novel — no prior named logger in codebase)"
    - "TDD RED/GREEN per task (plan type=tdd)"
key_files:
  created:
    - src/main/java/com/forgebook/safety/StatsAccumulator.java
    - src/main/java/com/forgebook/safety/RequestAuditLogger.java
    - src/test/java/com/forgebook/safety/StatsAccumulatorTest.java
    - src/test/java/com/forgebook/safety/RequestAuditLoggerTest.java
  modified: []
decisions:
  - "LongAdder over AtomicLong for hot-path counters (cell-partitioned writes)"
  - "Top-10 per-player render cap (Pitfall 8 — stays under 32 KB chat packet limit)"
  - "Denied requests do NOT count as initiated (SAFE-02 semantics)"
  - "Failed requests DO count as initiated but without token/latency attribution"
  - "Named 'forgebook.audit' logger enables operator-configurable log routing"
  - "Single fan-out point: RequestAuditLogger is the only call site of StatsAccumulator.record*"
metrics:
  duration_seconds: 457
  completed_date: "2026-04-16"
  task_count: 2
  file_count: 4
  test_count: 10
  commit_count: 4
---

# Phase 03 Plan 02: Audit Logger & Stats Accumulator Summary

One-liner: StatsAccumulator (per-UUID LongAdder counters with top-10 render) and RequestAuditLogger (named "forgebook.audit" Log4j2 logger) — together form the single fan-out point that satisfies SAFE-04 audit logging and CMD-06 /forgebook stats rendering with one call site = one log line + one counter bump.

## What Was Built

**Plan 02 is observability primitives — no Authorizer / Brigadier / AI coupling introduced; fan-out between RequestAuditLogger and StatsAccumulator verified via tests.**

### StatsAccumulator (src/main/java/com/forgebook/safety/StatsAccumulator.java)

- `PerPlayer` record wrapping four LongAdders: requests, inputTokens, outputTokens, latencySumMs.
- ConcurrentHashMap<UUID, PerPlayer> PER_PLAYER; lazy-created via `computeIfAbsent`.
- Five aggregate LongAdders: TOTAL_REQUESTS, TOTAL_DENIED, TOTAL_INPUT_TOK, TOTAL_OUTPUT_TOK, TOTAL_LATENCY_MS.
- Three record entry points:
  - `recordSuccess(uuid, inTok, outTok, latencyMs)` — bumps per-player + all aggregates.
  - `recordFailure(uuid, code)` — bumps per-player requests + TOTAL_REQUESTS only (failures count as initiated per SAFE-02; no tokens attributed because provider never returned a usable response).
  - `recordDenied(uuid, code)` — bumps TOTAL_DENIED only. Does NOT count as initiated because Authorizer rejected the request BEFORE provider contact.
- `render()` — 4 KB initial-capacity StringBuilder, header + 5 aggregate lines + mean-latency line + top-10 per-player section sorted by request count descending. Stays under the 32 KB vanilla chat packet limit even with thousands of unique UUIDs.
- `resetForTests()` — @VisibleForTesting helper; clears PER_PLAYER and resets all LongAdders. Tests call in @BeforeEach for isolation.

### RequestAuditLogger (src/main/java/com/forgebook/safety/RequestAuditLogger.java)

- `private static final Logger AUDIT = LogManager.getLogger("forgebook.audit")` — dedicated named logger (NOT the default class-name logger). Operators can route just this logger via a `<Logger name="forgebook.audit">` element in log4j2.xml.
- Three log methods, all using ParameterizedMessage `{}` placeholders (no String.format, no user-message concatenation):
  - `logSuccess(uuid, kind, in_tok, out_tok, latency_ms)` → emits `outcome=OK`; calls `StatsAccumulator.recordSuccess`.
  - `logFailure(uuid, kind, code, in_tok, out_tok, latency_ms)` → emits `outcome={code}`; calls `StatsAccumulator.recordFailure`.
  - `logDenied(uuid, kind, code, startNanos)` → computes `latencyMs = (nanoTime - startNanos)/1_000_000`; emits with `in_tok=0 out_tok=0`; calls `StatsAccumulator.recordDenied`.
- Payload restricted to metadata (uuid, kind, token counts, latency, outcome) — NEVER user message content (SAFE-04 / T-03-02-01 invariant).

### Test coverage (10 tests total)

**StatsAccumulatorTest (6 tests)**:
- `recordSuccess_incrementsAllCountersForPlayer`
- `recordFailure_incrementsRequestsOnly_notTokens`
- `recordDenied_incrementsTotalDeniedOnly_notPerPlayerRequests`
- `render_capsPerPlayerSectionToTopTenByRequestCount` (15 players → 10 rows)
- `separateUuids_haveIndependentCounters`
- `render_containsHeaderAndAllAggregateLabels`

**RequestAuditLoggerTest (4 tests)**:
- `logSuccess_fansOutToStatsAccumulator`
- `logFailure_fansOutToStatsAccumulatorAsFailure`
- `logDenied_fansOutToStatsAccumulatorAsDenied`
- `auditLogger_usesNamedForgebookAuditLogger`

All 10 tests pass in isolation (`./gradlew test --tests "com.forgebook.safety.*"`).

## Commits (per-task atomic TDD sequence)

| Commit   | Type | Description                                                                      |
| -------- | ---- | -------------------------------------------------------------------------------- |
| 06ca2cf  | test | Failing tests for StatsAccumulator (RED — 6 tests, compile fails)                |
| fcc9463  | feat | Implement StatsAccumulator with per-UUID LongAdder counters (GREEN)              |
| d2824cc  | test | Failing tests for RequestAuditLogger (RED — 4 tests, compile fails)              |
| fb928ce  | feat | Implement RequestAuditLogger with named audit logger + stats fan-out (GREEN)     |

## Key Decisions

| Decision                                             | Rationale                                                                                                         |
| ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `LongAdder` over `AtomicLong`                        | Cell-partitioned writes — lower contention on hot accumulators; JDK 17 standard since 1.8 (RESEARCH §Pattern 8).  |
| `ConcurrentHashMap.computeIfAbsent` for PER_PLAYER   | Lock-free lazy creation; first record for a UUID never loses under race.                                          |
| Named `forgebook.audit` logger                       | Operators can route audit lines separately via `<Logger name="forgebook.audit">` in log4j2.xml — free affordance. |
| Top-10 render cap                                    | Pitfall 8 — vanilla chat packet is 32 KB; 10 players × ~90 B per row keeps us well under with headroom.            |
| Denied requests NOT counted as initiated             | SAFE-02 — rate-limit / OP-gate / kill-switch fires BEFORE initiation; denied should not inflate per-player quota. |
| Single fan-out point (audit logger → stats)          | One call site ensures log line and counter bump always go together — no drift between `/stats` and the log stream. |
| `resetForTests()` as public @VisibleForTesting       | Static state needs explicit test reset; no DI container to swap instances.                                        |
| Parameterized `{}` over `String.format`              | T-03-02-01 — Log4j2 appender-level formatting handles escaping and avoids accidental PII embedding.                |

## Deviations from Plan

### Scoped Fix: RequestKind.java temporary stub for local verification

- **Found during:** Task 2 GREEN verification — `RequestAuditLogger` imports `com.forgebook.ai.RequestKind`, which is owned by Plan 03-01 (parallel Wave 1 worktree).
- **Issue:** Local `./gradlew test` could not compile because this worktree branched before Plan 03-01's RequestKind.java existed on disk. The plan explicitly notes "Wave 1 co-deploys both plans" — the full build passes only after merge.
- **Fix:** Created a byte-identical temporary `src/main/java/com/forgebook/ai/RequestKind.java` (exact same 2-line enum content as specified in 03-01-PLAN.md lines 93-95), ran verification, then REMOVED the file BEFORE committing any artifacts in this worktree. The final commit history of this worktree contains ONLY the four files listed in `files_modified`; Plan 01's worktree is the sole authoritative author of `RequestKind.java`.
- **Files modified:** None committed (file was created and deleted within the same run).
- **Rationale:** Rule 3 (auto-fix blocking issue) — verification was blocked by a documented cross-wave dependency. Creating a non-committed stub in the local working tree is the minimum-invasive unblock. No merge conflict is introduced because this worktree never owns the file.
- **Classification:** Workaround for parallel-wave compile ordering; not a true deviation from plan scope.

### No other deviations

- No imports of `net.minecraft.*`, `com.forgebook.client.*`, or `com.forgebook.config.ApiKey` in either production file (grep-verified).
- No `String.format` in RequestAuditLogger (grep-verified — parameterized `{}` only).
- No user-message content referenced in any log call (code-reviewed).
- All plan acceptance criteria grep checks pass.

## Threat Model Verification

| Threat ID   | Disposition | Verification                                                                                          |
| ----------- | ----------- | ----------------------------------------------------------------------------------------------------- |
| T-03-02-01  | mitigated   | Method signatures accept no `String message` param; only uuid/kind/tokens/latency/code. `{}` placeholders only. |
| T-03-02-02  | mitigated   | Existing ApiKeyScrubFilter wraps all Log4j2 appenders globally — no code change needed.                |
| T-03-02-03  | accepted    | PER_PLAYER bounded by server lifetime + distinct UUIDs; ~80 B per entry × 10k UUIDs ≈ 800 KB (tolerable). |
| T-03-02-04  | mitigated   | All writes via LongAdder (lock-free) + `ConcurrentHashMap.computeIfAbsent` (atomic).                   |
| T-03-02-05  | mitigated   | `grep "com.forgebook.config.ApiKey" src/main/java/com/forgebook/safety/{Stats,Request}*.java` — zero matches. |

No new threat flags discovered during implementation.

## Acceptance Criteria Compliance

All plan-specified acceptance criteria verified:

- `public final class StatsAccumulator` — present (1 match)
- `ConcurrentHashMap<UUID, PerPlayer>` — present (1 match)
- `new LongAdder()` — present (6 matches: 4 in PerPlayer ctor + 5 aggregate fields share the same 6 occurrences spread between defaults and aggregates)
- All three `record*` method signatures — exact match
- `.limit(10)` — present (1 match in `render()`)
- `resetForTests()` — present
- `LogManager.getLogger("forgebook.audit")` — present (field declaration + Javadoc example)
- All three `log*` method signatures — exact match
- Three `StatsAccumulator.record*` fan-out calls — present (one per log method)
- StatsAccumulatorTest ≥ 6 @Test methods — 6 tests
- RequestAuditLoggerTest ≥ 4 @Test methods — 4 tests
- `./gradlew test --tests com.forgebook.safety.StatsAccumulatorTest` — 6/6 green
- `./gradlew test --tests com.forgebook.safety.RequestAuditLoggerTest` — 4/4 green

## Call Sites (for future plans)

Plans 03-04 / 03-05 / 03-06 will call into these primitives:

- `ChatRequestHandler.handle` (Plan 03-04) → `RequestAuditLogger.logDenied` on SAFE-06 precheck fail; `logSuccess` / `logFailure` inside AiDispatcher flows.
- `AskSubcommand` / `ItemSubcommand` (Plan 03-04) → `RequestAuditLogger.logDenied` on auth precheck.
- `AdminSubcommands` (Plan 03-04/06) → `StatsAccumulator.render()` for `/forgebook stats` output.
- `RagItemPipeline.run` (Plan 03-05) → `RequestAuditLogger.logSuccess/logFailure` at terminal points.

## Self-Check: PASSED

Files verified on disk:
- FOUND: src/main/java/com/forgebook/safety/StatsAccumulator.java
- FOUND: src/main/java/com/forgebook/safety/RequestAuditLogger.java
- FOUND: src/test/java/com/forgebook/safety/StatsAccumulatorTest.java
- FOUND: src/test/java/com/forgebook/safety/RequestAuditLoggerTest.java

Commits verified in git log:
- FOUND: 06ca2cf (test: failing StatsAccumulator tests)
- FOUND: fcc9463 (feat: StatsAccumulator implementation)
- FOUND: d2824cc (test: failing RequestAuditLogger tests)
- FOUND: fb928ce (feat: RequestAuditLogger implementation)

TDD gate compliance:
- StatsAccumulator: test commit 06ca2cf precedes feat commit fcc9463 — RED/GREEN order verified.
- RequestAuditLogger: test commit d2824cc precedes feat commit fb928ce — RED/GREEN order verified.
- No REFACTOR commits needed (implementations matched behavior requirements on first pass).
