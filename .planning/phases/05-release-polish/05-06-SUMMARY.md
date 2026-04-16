---
phase: 05-release-polish
plan: 06
subsystem: release-ops
tags: [docs, release, smoke, human-verify, jarjar-blocker, rel-05]

# Dependency graph
requires:
  - phase: 05-release-polish
    provides: "Plan 05-01 version bump to 1.0.0 (forgebook-1.0.0.jar artifact name); Plan 05-04 i18n keys (forgebook.command.reload.success, forgebook.command.denied.rate_limited, forgebook.command.denied.disabled) referenced as integration canaries."
  - phase: 01-foundations-safe-egress
    provides: "relocateJsoup Gradle task (runs successfully — produces build/relocated/jsoup-relocated-1.17.2.jar); ApiKeyScrubFilter (Phase 1 CFG-05) that the Step 3 grep re-validates."
  - phase: 03-command-surface
    provides: "[forgebook.audit] log-line format (uuid/kind/tokens/latency/outcome) that Step 5 asserts on; /forgebook subcommand set (item/ask/disable/enable/stats/reload) that Steps 5-8 exercise."
  - phase: 04-ui-chat
    provides: "ChatPanelWidget + ChatScreen surfaces that Step 6 visually verifies; ErrorCard.bodyKey client-side translatable rendering."
provides:
  - "docs/RELEASE-SMOKE.md — pre-tag release smoke protocol (9 operator steps + Step 10 tag/gh-release-create)"
  - "KNOWN BLOCKER section at top of RELEASE-SMOKE.md flagging the pre-existing Phase 1 jarJar-skipped defect as a gating prerequisite for physical smoke"
  - "Step 1 automated clean-build + jar-integrity assertion chain (ls + 'jar tf | grep jsoup' + sanity-grep 'jarjar|com/forgebook/shadow/jsoup')"
  - "Auto-deferred human-verify checkpoint record — Steps 2-9 parked for a pre-tag human operator session once the jarJar blocker is resolved"
affects:
  - "Future plan (outside Phase 5): jarJar bundling fix — RELEASE-SMOKE.md Step 1 will stay red until that plan lands; the doc is written to catch the fix when it arrives."
  - "Pre-v1.0.0 operator runbook: doc is now the canonical pre-tag checklist."

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Docs-as-gating-check: RELEASE-SMOKE.md Step 1 is an assertion chain that currently FAILS on the main tree by design — the failure is the signal that the upstream jarJar defect is still outstanding. When the fix lands, Step 1 goes green with no doc change required."
    - "KNOWN BLOCKER preamble pattern for release docs: surface pre-existing build-pipeline defects up front so release operators don't waste time walking downstream steps that cannot succeed."
    - "Dual-assertion jar-integrity check: specific (META-INF/jarjar/.*jsoup.*\\.jar) plus permissive sanity (jarjar|com/forgebook/shadow/jsoup) — survives potential ForgeGradle jar-in-jar naming changes without losing the coverage intent."

key-files:
  created:
    - "docs/RELEASE-SMOKE.md"
  modified: []

key-decisions:
  - "Documented the pre-existing jarJar-skipped defect as a KNOWN BLOCKER in RELEASE-SMOKE.md rather than attempting to fix it in this plan — objective explicitly scopes the fix to a separate plan outside Phase 5. The doc is the canary that the release operator (and the next planner) consults to know the blocker is still live."
  - "Added Step 10 (git tag + gh release create) to the protocol per plan-08 objective enhancement — plan text originally stopped at Step 9 Teardown, but objective asked for '+ gh release'. Implemented as Step 10 with explicit conditional ('only run if Steps 1-9 PASSED')."
  - "Dual jar-tf assertion pattern: specific regex plus permissive fallback grep so the check stays meaningful even if ForgeGradle 6's nested jar filename pattern changes in a future toolchain bump (flagged as 'What NOT to Use'-style brittleness in CLAUDE.md)."
  - "Auto-approved Task 3 human-verify checkpoint per --auto chain mode. Physical Steps 2-9 deferred to a pre-tag human operator session — deferral is doubly-gated now: (a) by the general '--auto defers physical smoke' rule, AND (b) by the jarJar blocker making Steps 2-9 impossible until the fix lands."
  - "Task 2 surfaced the jarJar blocker as EXPECTED state (per objective) — did NOT treat the 'jar tf | grep jsoup' returning zero lines as a Task 2 failure that should stop Task 3. The objective explicitly requested that the FAILURE be the documented signal. Documented in 'Deferred Issues' section below."

patterns-established:
  - "Release-smoke protocol doc pattern: KNOWN BLOCKER preamble + automated Step 1 + human Steps 2-9 + optional Step 10 tag/release + Pass/Fail criteria + automated-vs-human table. Reusable for future release-artifact smoke docs (e.g. if ForgeBook adds a Fabric port and needs a parallel protocol)."
  - "Failing-by-design assertion: a release doc's automated check is the right place to park a gating prerequisite — its red state is the inbox for the upstream fix-plan."

requirements-completed: [REL-05]

# Metrics
duration: 4min
completed: 2026-04-16
---

# Phase 5 Plan 06: Release Smoke Docs Summary

**Release-smoke protocol at `docs/RELEASE-SMOKE.md` with 9 operator steps + Step 10 (tag + `gh release create`). KNOWN BLOCKER preamble flags the pre-existing Phase 1 jarJar-skipped defect as a gating prerequisite for physical prod-jar smoke.**

## Performance

- **Duration:** ~4 min
- **Started:** 2026-04-16T22:58:31Z
- **Completed:** 2026-04-16T23:02:43Z
- **Tasks:** 3 (1 auto + 1 auto-verify + 1 human-verify checkpoint)
- **Files created:** 1 (`docs/RELEASE-SMOKE.md`)
- **Files modified:** 0

## Accomplishments

- `docs/RELEASE-SMOKE.md` created (173 insertions, 11,132 bytes) covering all 9 protocol steps + Step 10 (tag + `gh release create`) + KNOWN BLOCKER preamble + Pass/Fail criteria + automated-vs-human table.
- All 7 `/forgebook` subcommands exercised across Steps 5-8 (item, ask, disable, enable, stats, reload + chat UI).
- Automated Step 1 assertion chain defined: `./gradlew clean build --no-daemon` + `ls build/libs/forgebook-1.0.0.jar` + `jar tf | grep "META-INF/jarjar/.*jsoup.*\.jar"` + sanity-grep `jar tf | grep -E "jarjar|com/forgebook/shadow/jsoup"`.
- Task 2 automated Step 1 run on the current tree: `./gradlew clean build --no-daemon` exits 0; `build/libs/forgebook-1.0.0.jar` exists at 188,103 bytes; `relocateJsoup` succeeds (produces `build/relocated/jsoup-relocated-1.17.2.jar`, 453,804 bytes); `jarJar SKIPPED`; jsoup assertion FAILS (expected — see blocker section).
- Plan 05-04 i18n keys referenced as integration canaries: `forgebook.command.reload.success`, `forgebook.command.denied.rate_limited`, `forgebook.command.denied.disabled`. A future key rename will break Plan 05-04's acceptance criteria; the smoke doc provides a secondary catch.
- Secret-leakage guard preserved: `grep "sk-ant-" logs/latest.log` assertion in Step 3 keeps the ApiKeyScrubFilter (Phase 1 CFG-05) as a release-gating check.

## Task Commits

1. **Task 1: Create docs/RELEASE-SMOKE.md** — `14fdf5e` (docs) — 9-step protocol with KNOWN BLOCKER preamble + Step 10 tag/release.
2. **Task 2: Automated Step 1 — clean build + jar integrity** — no commit (pure verification; build artefacts are git-ignored per Phase 1 CFG-06). Results captured inline in this SUMMARY.
3. **Task 3: Human-verify checkpoint** — auto-approved under `--auto` chain. No commit (process gate).

## Files Created/Modified

- **Created** `docs/RELEASE-SMOKE.md` (11,132 bytes, 173 lines). Committed in `14fdf5e`. Structure:
  - Prerequisites block.
  - **KNOWN BLOCKER** — jarJar task is SKIPPED (pre-existing Phase 1 defect) — up-front blocker call-out.
  - Step 1 (AUTOMATED — Claude can run).
  - Steps 2-9 (HUMAN-ONLY): dedicated-server install, secret-set + leak-check, client connect, smoke `/forgebook item`, chat UI smoke, disable/enable smoke, stats/reload + rate-limit smoke, teardown.
  - Step 10 (HUMAN, post-smoke): `git tag` + `gh release create`.
  - Pass / Fail Criteria.
  - Automated vs. human table.

## Decisions Made

See frontmatter `key-decisions`. Primary: (1) surface the pre-existing jarJar-skipped defect as a top-of-doc KNOWN BLOCKER rather than attempt to fix it (objective-scoped), (2) add Step 10 with tag + `gh release create` per objective enhancement, (3) dual jar-tf assertion pattern for ForgeGradle-version resilience, (4) auto-approve Task 3 per `--auto` chain with physical Steps 2-9 doubly-deferred (auto-defer + blocker-defer).

## Deviations from Plan

### Auto-extended: Added Step 10 (tag + gh release create)

- **Source:** Objective explicitly asked for "9 steps + Step 10 tag + gh release". Plan text originally stopped at Step 9 Teardown.
- **Fix:** Added Step 10 after Step 9 Teardown with both `git tag -a v1.0.0` + `gh release create v1.0.0 build/libs/forgebook-1.0.0.jar` commands, plus explicit precondition ("only run if Steps 1-9 all PASSED") and Expected block (tag visible, release published, sources jar not attached). Updated the "What's automated vs. human" table to add a Step 10 row (Partial — commands scriptable, tag-vs-rcN decision from human verdict).
- **Files modified:** `docs/RELEASE-SMOKE.md` (Step 10 section, automation table row).
- **Rationale:** Objective override (objective explicitly asks for gh release). Committed as part of Task 1's single doc commit.

### Auto-extended: Prominent KNOWN BLOCKER preamble

- **Source:** Objective explicitly asked for the jarJar-skipped defect to be prominently flagged in the SUMMARY AND for the Step 1 assertion to include `jar tf ... | grep -E 'jarjar|com/forgebook/shadow/jsoup'` that "currently fails" so the protocol documents the gap.
- **Fix:** Added a KNOWN BLOCKER section at the top of RELEASE-SMOKE.md (before Step 1) with: discovery provenance (pointer to 05-01-SUMMARY), reproducible evidence (the exact `jar tf` command that returns zero), impact on Steps 1-9, disposition (gating prerequisite outside Phase 5), and three candidate fix directions for the next planner. Added inline "jsoup canary" note in Step 5 where the blocker would surface downstream. Added "Current known status: … this step FAILS" paragraph to Step 1 itself.
- **Rationale:** Objective override. Not a plan deviation in the Rule 1-4 sense — it's the plan objective extended by the spawner. Committed as part of Task 1's single doc commit.

**Total deviations:** 0 in the Rule 1-4 bug/missing/blocking/architectural sense. 2 objective-extensions (Step 10, KNOWN BLOCKER preamble) folded into Task 1's single commit.

## Deferred Issues

### BLOCKER: jarJar task is SKIPPED — RELEASE-SMOKE.md Step 1 fails on current tree

- **Severity:** High — blocks REL-05 physical smoke (Steps 2-9 cannot proceed; Step 5's first AI request will throw `NoClassDefFoundError: com/forgebook/shadow/jsoup/...` on a clean dedicated server).
- **Pre-existing?** Yes. First discovered during Plan 05-01 execution — see `.planning/phases/05-release-polish/05-01-SUMMARY.md` "Issues Encountered — Deferred" section, which reproduces the defect against the pre-05-01-edit tree.
- **Re-confirmed in Task 2 of this plan:** `./gradlew clean build --no-daemon` exits 0 → `jarJar SKIPPED` + `reobfJarJar SKIPPED` appear in Gradle output → `build/libs/forgebook-1.0.0.jar` is 188,103 bytes with zero `META-INF/jarjar/` entries and zero `com/forgebook/shadow/jsoup/` classes → the `relocateJsoup` task itself runs successfully and produces `build/relocated/jsoup-relocated-1.17.2.jar` (453,804 bytes) → but the `jarJar` task that should nest that artifact into the main jar is disabled.
- **Why not fixed here:** Objective explicit: *"Do not attempt to fix the jarJar bundling in this plan — that's a separate fix outside Phase 5 scope. Just document the gap and its blocking nature."* This plan's objective is documentation; the fix belongs to a dedicated build-pipeline plan (potentially a phase 05-07 hotfix or a phase 06 opener).
- **Documented in:** `docs/RELEASE-SMOKE.md` — KNOWN BLOCKER preamble at top, plus Step 1 "Current known status" paragraph, plus Step 5 "Jsoup canary" note where the error would surface downstream. The doc is now the inbox for the fix.
- **Candidate root causes** (for the planner of the fix-plan):
  - ForgeGradle 6's `jarJar` task may require version-ranged dependency declarations (`jarJar(group: 'com.forgebook.shadow', name: 'jsoup-relocated', version: '[1.17,2.0)')`) instead of a raw `files(...)` reference. See `build.gradle` L63-64.
  - The `reobfJarJar SKIPPED` sibling line suggests task-wiring into the `assemble` lifecycle is also needed.
  - Reference: ForgeGradle 6 jarJar documentation (https://docs.minecraftforge.net/en/fg-5.x/dependencies/jarinjar/).

## User Setup Required

None for this plan. For the deferred physical smoke (Steps 2-9 of RELEASE-SMOKE.md), the release operator will need:

- Disposable Minecraft 1.20.1 launcher installation.
- Disposable Forge 1.20.1-47.4.18 dedicated server folder.
- Anthropic API key (~$0.01 per smoke at Haiku pricing).
- **Prerequisite:** the jarJar fix-plan (outside Phase 5) must land first — without it, Step 1 cannot pass and Steps 2-9 cannot be walked usefully.

## Next Phase Readiness

- **Phase 5 close:** docs artefact shipped, automated Step 1 validated (build-pipeline side), human checkpoint auto-approved. REL-05 documentation obligation satisfied.
- **Pre-v1.0.0 tagging:** Blocked on the jarJar fix-plan plus a human-operator session walking Steps 2-9 of `docs/RELEASE-SMOKE.md`. The doc is the checklist; it cannot be walked green today.
- **Downstream code paths:** No Java source changes. No Gradle changes. No test changes. Pure docs addition.

## Threat Flags

None. The plan's threat model (T-05-06-01 through T-05-06-08) is entirely *mitigated through documentation* — the doc is the mitigation vehicle, and it was delivered. No new security-relevant surface introduced.

## TDD Gate Compliance

Not applicable — plan type is `execute`, not `tdd`. No RED/GREEN/REFACTOR cycle expected.

## Self-Check: PASSED

Claimed artifacts verified present via Bash `ls` / `test -f` / `grep` (executed in /c/Users/Nick/IdeaProjects/ForgeBook/.claude/worktrees/agent-af48817e):

- `docs/RELEASE-SMOKE.md` exists, 11,132 bytes — FOUND.
- `grep -cE "^## Step [1-9] " docs/RELEASE-SMOKE.md` returned 9 — FOUND.
- `grep -c "^## Step 10" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "forgebook-1.0.0.jar" docs/RELEASE-SMOKE.md` returned 11 (>=2) — FOUND.
- `grep -c "./gradlew clean build --no-daemon" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "jar tf build/libs/forgebook-1.0.0.jar" docs/RELEASE-SMOKE.md` returned 3 (>=1) — FOUND.
- `grep -c "chmod 600" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- All 7 `/forgebook` subcommand refs present (item=5, ask=1, reload=2, disable=2, enable=2, stats=2) — FOUND.
- `grep -c 'grep "sk-ant-" logs/latest.log' docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "forgebook.audit" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "^## Pass / Fail Criteria" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "v1.0.0-rc" docs/RELEASE-SMOKE.md` returned 3 (>=1) — FOUND.
- `grep -c "Delete the API key" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "forgebook.command.reload.success" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "forgebook.command.denied.rate_limited" docs/RELEASE-SMOKE.md` returned 1 — FOUND.
- `grep -c "gh release create" docs/RELEASE-SMOKE.md` returned 2 — FOUND.
- `grep -c "KNOWN BLOCKER" docs/RELEASE-SMOKE.md` returned 2 — FOUND.

Claimed commits verified via `git log --oneline`:

- `14fdf5e docs(05-06): add docs/RELEASE-SMOKE.md pre-tag release smoke protocol` — FOUND.

Claimed Task 2 build results verified via on-disk artefacts:

- `build/libs/forgebook-1.0.0.jar` exists, 188,103 bytes — FOUND.
- `build/libs/forgebook-0.1.0.jar` does NOT exist (proves `clean` wiped stale artefacts) — CONFIRMED.
- `build/relocated/jsoup-relocated-1.17.2.jar` exists, 453,804 bytes — FOUND (confirms `relocateJsoup` ran).
- `jar tf build/libs/forgebook-1.0.0.jar | grep -E "META-INF/jarjar/.*jsoup.*\.jar"` returns zero lines — BLOCKER CONFIRMED (as expected per objective).
- `jar tf build/libs/forgebook-1.0.0.jar | grep -E "jarjar|com/forgebook/shadow/jsoup"` returns zero lines — BLOCKER CONFIRMED.

---
*Phase: 05-release-polish*
*Completed: 2026-04-16*
