---
phase: 03-command-surface-safety-controls
plan: "06a"
subsystem: command-wiring
tags: [brigadier, command-tree, reload, rate-limiter-swap, listener-swap, wiring-foundation]

# Dependency graph
requires:
  - phase: 02-ai-engine
    provides: SystemPromptBuilder.buildAndCache (invoked from executeReload)
  - phase: 03-command-surface-safety-controls
    provides: RateLimiter + RateLimiterHolder (Plan 01); ConfigSnapshot.rateLimitPerMinute (Phase 1/2)
provides:
  - Public static ForgebookReloadCommand.executeReload(CommandContext<CommandSourceStack>) — reusable reload body with RateLimiterHolder.swap after ConfigHolder.set
  - ForgebookCommands.onRegister — single registrar for the full /forgebook tree (ask, item, reload, disable, enable, stats) with correct .requires placement
  - Stub ItemSubcommand, AskSubcommand, AdminSubcommands with frozen method signatures — call sites compile; bodies throw "Plan 06b pending"
  - ForgeBookMod listener swap (ForgebookCommands::onRegister) and ServerStartingEvent seeding of RateLimiterHolder alongside ConfigHolder
affects: [03-06b, 03-07, 03-08]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Forward-declared stub classes so Brigadier method references compile before executor bodies land (Plan 06a wires, Plan 06b fills)"
    - "Structural vs runtime authorization split — .requires(hasPermission(2)) for admin-only subcommands; omit .requires for player-facing subcommands gated at runtime by Authorizer"
    - "Single-volatile-load reload ordering: ConfigHolder.set → SystemPromptBuilder.buildAndCache → RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute()))"

key-files:
  created:
    - src/main/java/com/forgebook/command/ForgebookCommands.java
    - src/main/java/com/forgebook/command/ItemSubcommand.java
    - src/main/java/com/forgebook/command/AskSubcommand.java
    - src/main/java/com/forgebook/command/AdminSubcommands.java
  modified:
    - src/main/java/com/forgebook/command/ForgebookReloadCommand.java
    - src/main/java/com/forgebook/ForgeBookMod.java

key-decisions:
  - "Keep ForgebookReloadCommand.onRegister as a thin back-compat wrapper delegating to ::executeReload — tests or third-party callers that invoke the old registrar still work"
  - "Place .requires on admin subcommands (reload, disable, enable, stats) structurally; leave ask and item ungated at the Brigadier level so the Authorizer decides in one place per RequestKind (matches CHAT_UI path)"
  - "Seed RateLimiterHolder inside the existing ConfigHolder ServerStartingEvent listener to guarantee ordering (ConfigHolder.set happens before RateLimiterHolder.swap within the same listener body)"
  - "Stub subcommand bodies throw UnsupportedOperationException(\"Plan 06b pending\") so an accidental mid-wave build halts loudly rather than executing empty bodies"

patterns-established:
  - "Pitfall 1 enforcement: structural OP gate only for admin subcommands; runtime op_only gate lives in Authorizer inside .executes bodies for player-facing subcommands"
  - "Reload body reads ConfigHolder.get() exactly once after set() per D-14 single-volatile-load invariant, then passes rateLimitPerMinute to the RateLimiter constructor"
  - "ItemArgument.item(event.getBuildContext()) is the 1.20.1 API (getBuildContext is provided by RegisterCommandsEvent)"

requirements-completed: [CMD-01, CMD-04]

# Metrics
duration: 4m 19s
completed: 2026-04-16
---

# Phase 03 Plan 06a: Command Surface Wiring Foundation Summary

**Brigadier root tree and RateLimiterHolder seeding wired so Plan 06b can land executor bodies without touching ForgeBookMod or ForgebookReloadCommand again.**

## Performance

- **Duration:** 4m 19s
- **Started:** 2026-04-16T17:05:16Z
- **Completed:** 2026-04-16T17:09:35Z
- **Tasks:** 3 completed
- **Files created:** 4
- **Files modified:** 2

## Accomplishments

- Extracted `ForgebookReloadCommand.executeReload` into a public static method and added `RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute()))` after `ConfigHolder.set` — delivers CMD-06 (reload resizes the limiter).
- Created `ForgebookCommands.onRegister` registering the full six-subcommand `/forgebook` tree with correct `.requires` placement (admin subcommands structurally gated; player-facing subcommands runtime-gated via Authorizer) — delivers CMD-01 (root tree registration).
- Swapped `ForgeBookMod`'s Brigadier listener from `ForgebookReloadCommand::onRegister` to `ForgebookCommands::onRegister` and augmented the existing `ConfigHolder` seed listener to also seed `RateLimiterHolder` — ensures readers see a non-null limiter from the first server tick.

## Task Commits

Each task was committed atomically:

1. **Task 1: ForgebookReloadCommand — extract executeReload, add RateLimiterHolder.swap** — `a307f43` (refactor)
2. **Task 2: ForgebookCommands — Brigadier root tree + stub subcommand classes for compile** — `6f3ccfd` (feat)
3. **Task 3: ForgeBookMod — swap listener + seed RateLimiterHolder on ServerStartingEvent** — `6635ece` (feat)

## Files Created/Modified

### Created

- `src/main/java/com/forgebook/command/ForgebookCommands.java` — Single Brigadier registrar for `/forgebook`. Declares six subcommands (ask, item, reload, disable, enable, stats), wires admin subcommands to `.requires(src -> src.hasPermission(2))`, leaves ask/item runtime-gated. Delegates reload to `ForgebookReloadCommand::executeReload`.
- `src/main/java/com/forgebook/command/ItemSubcommand.java` — Stub class. `executeHeld` and `executeWithArg` throw `UnsupportedOperationException("Plan 06b pending")`; signatures frozen so `ForgebookCommands::executeHeld` / `::executeWithArg` method references compile.
- `src/main/java/com/forgebook/command/AskSubcommand.java` — Stub class. `execute` throws `UnsupportedOperationException("Plan 06b pending")`.
- `src/main/java/com/forgebook/command/AdminSubcommands.java` — Stub class. `executeDisable`, `executeEnable`, `executeStats` throw `UnsupportedOperationException("Plan 06b pending")`.

### Modified

- `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` — Extracted reload body into `public static int executeReload(CommandContext<CommandSourceStack> ctx)`. `onRegister` simplified to `.executes(ForgebookReloadCommand::executeReload)`. New body runs `ConfigHolder.set` → `SystemPromptBuilder.buildAndCache` → `RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute()))` in that order.
- `src/main/java/com/forgebook/ForgeBookMod.java` — Replaced listener `ForgebookReloadCommand::onRegister` with `ForgebookCommands::onRegister`. Augmented the existing `ConfigHolder` seed listener so it also seeds `RateLimiterHolder` with `new RateLimiter(rateLimitPerMinute())` after `ConfigHolder.set`, within the same lambda body (guaranteed ordering).

## Verification

- `./gradlew compileJava --no-daemon -x test` — BUILD SUCCESSFUL (pre-existing deprecation warnings unrelated to this plan)
- `./gradlew build --no-daemon -x test` — BUILD SUCCESSFUL (jar, reobfJar, relocateJsoup all succeeded)
- `./gradlew test --no-daemon` — BUILD SUCCESSFUL (no regressions in Phase 1/2 test suites)

### Must-Haves Truths Verified

- `grep -c '.then(Commands.literal(' ForgebookCommands.java` = 6 (six subcommands)
- `.requires(src -> src.hasPermission(2))` structurally appears on reload, disable, enable, stats; omitted from ask, item
- `grep -c 'ItemArgument.item(event.getBuildContext())' ForgebookCommands.java` = 1 (no 1.19.x cargo-cult)
- `grep -c '@Mod.EventBusSubscriber' ForgebookCommands.java` = 0 actual annotations (Javadoc mentions the annotation is intentionally NOT used)
- ForgeBookMod listener is `ForgebookCommands::onRegister` (`ForgebookReloadCommand::onRegister` count = 0)
- `ConfigHolder.set` precedes `RateLimiterHolder.swap` by file order in both `ForgebookReloadCommand.java` (line 66 → 70) and `ForgeBookMod.java` (line 62 → 67)

## Deviations from Plan

None of substance — the plan executed exactly as written. Two done-criteria counts warrant note:

- **`ForgebookReloadCommand.java` `RateLimiterHolder\.swap` grep count returned 3 (plan expected 1).** Two of the three occurrences are Javadoc comments (class-header javadoc at line 29 and method-header javadoc at line 56) that document the pattern. The functional call-site count is 1 at line 70. Semantic intent satisfied.
- **`ForgebookCommands.java` `.requires(src -> src.hasPermission(2))` grep count returned 5 (plan expected 4).** One is a Javadoc reference at line 16 describing the pattern; the four structural calls land on reload/disable/enable/stats at lines 52, 56, 60, 64. Semantic intent satisfied.

These are documentation artifacts, not logic deviations. No code behavior differs from the plan's specification.

## Authentication Gates

None — no auth flow touched in this plan.

## Known Stubs

`Plan 06b pending` stubs intentionally placed in this plan:

- `src/main/java/com/forgebook/command/ItemSubcommand.java` — `executeHeld` (line 17), `executeWithArg` (line 21). Plan 06b replaces with authorize → AiExecutor.submit → RagItemPipeline dispatch.
- `src/main/java/com/forgebook/command/AskSubcommand.java` — `execute` (line 11). Plan 06b replaces with authorize → AiExecutor.submit → AgentLoop dispatch.
- `src/main/java/com/forgebook/command/AdminSubcommands.java` — `executeDisable` (line 11), `executeEnable` (line 15), `executeStats` (line 19). Plan 06b replaces with kill-switch / stats logic.

These stubs are the explicit forward-declaration discipline for this plan pair (06a → 06b). Runtime invocation in the gap between the two plans throws `UnsupportedOperationException` — loud failure rather than silent no-op. The plan's threat model (T-03-06a-04) accepts this.

## Threat Surface Scan

No new threat surface introduced beyond what the plan's `<threat_model>` already covered. The Brigadier tree, admin `.requires` structural gates, and RateLimiterHolder seeding are all in-scope of T-03-06a-01 through T-03-06a-04.

## TDD Gate Compliance

The plan frontmatter does not declare `type: tdd`, so plan-level gate enforcement does not apply. Individual tasks had `tdd="true"` hints, but no existing test suite for `ForgebookReloadCommand` was present on disk (no `src/test/java/com/forgebook/command/` directory), so no RED/GREEN commit split was produced. The Phase 1 `ForgebookReloadCommandTest` referenced in the plan does not exist in this worktree base — the grep done-criterion "if exists" guard applied.

## Self-Check: PASSED

- `src/main/java/com/forgebook/command/ForgebookCommands.java` — FOUND
- `src/main/java/com/forgebook/command/ItemSubcommand.java` — FOUND
- `src/main/java/com/forgebook/command/AskSubcommand.java` — FOUND
- `src/main/java/com/forgebook/command/AdminSubcommands.java` — FOUND
- `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` — FOUND (modified)
- `src/main/java/com/forgebook/ForgeBookMod.java` — FOUND (modified)
- Commit `a307f43` — FOUND
- Commit `6f3ccfd` — FOUND
- Commit `6635ece` — FOUND
