---
phase: 05-release-polish
plan: 05
subsystem: docs
tags: [compatibility, testing, matrix, human-verify, mod-compat]

# Dependency graph
requires:
  - phase: 05-release-polish
    provides: "Plan 05-02 README.md forward-reference link to docs/COMPATIBILITY.md"
provides:
  - "docs/COMPATIBILITY.md skeleton matrix (8 REL-04 compat targets x 2 GUI scales = 16 pending cells)"
  - "9-step Testing Protocol any future contributor can follow"
  - "Re-run Triggers list (UI change, compat bump, user report) for self-refreshing matrix"
  - "Contributing Matrix Updates instructions for PR authors"
affects: [post-release-operator, future-compat-verification, phase-05-closeout]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Deferred-verification docs: ship the protocol + skeleton at phase close, fill matrix rows in a post-release operator session"

key-files:
  created:
    - "docs/COMPATIBILITY.md — 8-row compat-matrix skeleton + 9-step testing protocol + re-run triggers + contributing instructions"
  modified: []

key-decisions:
  - "Skeleton-only delivery for Phase 5: all 16 matrix cells start [ ] pending. Human operator fills rows in a post-release session via ./gradlew runClient per RESEARCH L593-599."
  - "Version strings (known-good) sourced from 05-RESEARCH.md L559-567 verbatim. Operators may substitute newer versions per Re-run Triggers."
  - "Testing Protocol documents the 9-step procedure (clean mods/ folder, drop ForgeBook + one compat target, launch, inspect button placement, test GUI scales 1 and 2, close, record)."
  - "Re-run Triggers list (UI changes, compat-mod major version bumps, user-reported regressions) makes the matrix survive beyond Phase 5."

patterns-established:
  - "Docs-skeleton-with-protocol: when physical verification is required but infeasible in a non-GUI harness, ship the protocol + skeleton and defer verification to a human-operator session. Document the deferral explicitly so the artifact is not mistaken for a completed verification."

requirements-completed: [REL-04]

# Metrics
duration: ~5min
completed: 2026-04-16
---

# Phase 05 Plan 05: Compatibility Matrix Documentation Summary

**Shipped docs/COMPATIBILITY.md with an 8-row compat-matrix skeleton, a 9-step testing protocol, and re-run triggers — physical row-filling deferred to a post-release human-operator session under --auto chain.**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-04-16 (wave 3 executor)
- **Completed:** 2026-04-16
- **Tasks:** 2 (1 auto + 1 human-verify checkpoint auto-approved)
- **Files modified:** 1 created (docs/COMPATIBILITY.md)

## Accomplishments

- Created `docs/COMPATIBILITY.md` with the 8-row skeleton matrix mandated by REL-04 — JEI, REI, Sodium/Embeddium, Iris/Oculus, Jade, Mouse Tweaks, Quark, Inventory HUD+ — each with a researcher-verified known-good version string and `[ ] pending` placeholders for both GUI scale columns.
- Documented the 9-step per-mod Testing Protocol (clean `run/mods/`, drop ForgeBook + one compat target, launch, inspect button at `leftPos+imageWidth+4`, verify no overlap, test click, test at GUI scale 1 then 2, close, record).
- Added Re-run Triggers section (UI changes, compat-mod major bumps, user reports) so the matrix self-refreshes beyond Phase 5.
- Added Contributing Matrix Updates instructions for future PR authors.
- Resolved the forward-reference link from `README.md` (line 126) which had pointed to `docs/COMPATIBILITY.md` since Plan 05-02 created the README.
- Auto-approved the human-verify checkpoint under `--auto` chain — physical matrix fill deferred to a post-release operator session per RESEARCH §"What gets shipped in Phase 5" (L593-599).

## Task Commits

Each task was committed atomically:

1. **Task 1: Create docs/COMPATIBILITY.md with skeleton matrix + testing protocol** — `0edd93d` (docs)
2. **Task 2: Human operator fills in at least one matrix row (JEI recommended)** — auto-approved checkpoint under `--auto` chain; no commit (matrix rows remain pending until post-release operator session)

**Plan metadata commit:** (will be created after this SUMMARY is written — captures SUMMARY.md only per parallel_execution: STATE.md and ROADMAP.md not modified)

## Files Created/Modified

- `docs/COMPATIBILITY.md` — ForgeBook's public-facing compat matrix for the 8 REL-04 compat targets. Ships with skeleton rows (all 16 cells `[ ] pending`), a known-good version string per row, a 9-step Testing Protocol, Re-run Triggers list, and Contributing instructions. First file ever created under `docs/` in this repo.

## Matrix Statistics

- **Rows:** 8 (Just Enough Items, Roughly Enough Items, Sodium/Embeddium, Iris/Oculus, Jade, Mouse Tweaks, Quark, Inventory HUD+)
- **Pending cells:** 16 (2 GUI scale columns × 8 rows) — all `[ ] pending`
- **Verified cells:** 0 — filling requires physical `./gradlew runClient` runs with each compat mod loaded (~15-30 min per mod, ~2-4 hours total)
- **Version strings:** 8 known-good versions sourced verbatim from `05-RESEARCH.md` L559-567

## Checkpoint Resolution

**Task 2 (human-verify checkpoint):** `auto-approved` under `--auto` chain.

Per orchestrator directive and the plan's `autonomous: false` marker, the physical row-filling work (8 × `./gradlew runClient` with different compat mods in `run/mods/`) is **deferred to a post-release human-operator session**. The skeleton + protocol + re-run triggers shipped in this plan satisfy REL-04 SC-1's documentation obligation; the operator session will append `✓`/`✗` verdicts to matrix cells as individual mods are tested.

Operators seeking to verify the MVP smoke-critical row should follow the JEI-specific step-by-step in the plan's `<how-to-verify>` block:

1. Download `jei-1.20.1-forge-15.20.0.106.jar`.
2. `./gradlew clean build --no-daemon`.
3. Clean `run/mods/`; copy ForgeBook + JEI jars in.
4. `./gradlew runClient --no-daemon`.
5. Inventory → verify "Ask ForgeBook" button placement; verify no JEI overlap.
6. Click button; verify ChatScreen opens with inventory beneath.
7. GUI Scale → 1, repeat 5-6. GUI Scale → 2, repeat.
8. Close; edit matrix row; commit `docs(phase-05): fill JEI row in compatibility matrix`.

## Decisions Made

- **Skeleton-only delivery** was chosen over partial-fill (e.g., pre-filling JEI) because running a single JEI client test requires a disposable jar, a clean `run/mods/`, a `./gradlew runClient` session, and manual GUI-scale cycling — all of which must happen on a real desktop with display. The executor runs in a headless worktree, so even a one-row demonstration would be fabricated. RESEARCH §"What gets shipped in Phase 5" (L593-599) explicitly licenses this: "Filling the other seven rows is a human-checkpoint deliverable — Claude cannot run `runClient` with 8 different compat mods and visually inspect the output. Record as an auto-defer to the operator under `workflow._auto_chain_active=true`."
- **Known-good version strings verbatim from RESEARCH**, not invented or bumped to "latest." This keeps the matrix reproducible against a known-verified version baseline; operators may substitute newer versions per the Re-run Triggers section, and the matrix will update organically as PRs land.
- **`[ ] pending` legend convention** uses GitHub-checkbox-style syntax so a maintainer can visually scan the matrix for unverified cells and optionally render them as interactive checkboxes in rendered Markdown on GitHub.

## Deviations from Plan

### Documentation observations (not code bugs)

**1. [Plan acceptance criterion mis-specification — no fix applied]**
- **Found during:** Task 1 verification
- **Issue:** The plan's acceptance criterion "grep -c '\\[ \\] pending' docs/COMPATIBILITY.md returns ≥ 16 (2 per row × 8 rows)" confuses `grep -c` (which counts matching *lines*) with `grep -o | wc -l` (which counts match *instances*). With the compliant 8-row × 2-column skeleton, each matrix row is a single line containing two `[ ] pending` strings, so `grep -c` returns 9 (8 matrix rows + 1 legend line containing the string), not 16.
- **Fix:** None — the underlying truth requirement ("16 pending cells across 8 rows") is correctly satisfied by the file. Verified via `grep -o "\\[ \\] pending" docs/COMPATIBILITY.md | wc -l` = 17 (16 matrix cells + 1 legend reference); matrix-rows-only check yields exactly 16. No code/doc change needed; the acceptance criterion was a wording bug in the plan, and the deliverable meets the intent.
- **Files modified:** None (observation only)
- **Verification:** See terminal transcript in execution log (`grep -o` match count = 17; matrix-rows-only count = 16).
- **Committed in:** N/A (no fix)

---

**Total deviations:** 1 documented observation (0 auto-fixes applied)
**Impact on plan:** None — the deliverable meets all `<must_haves.truths>` entries and all `<success_criteria>` items. The observation is recorded here only so a future acceptance-criterion-refinement pass can swap `grep -c` for `grep -o | wc -l` in similar plans.

## Issues Encountered

None.

## Deferred Work

**Physical matrix row verification (8 rows × 2 GUI scales = 16 cells):** Parked for a post-release human-operator session. Each row requires:

- Fresh `./gradlew runClient` (~2-5 min cold start).
- Download of the known-good-version compat-mod jar (CurseForge/Modrinth manual fetch).
- GUI-scale cycling via Options → Video Settings (~30 sec per scale).
- Visual inspection of button placement and overlap (~1 min).
- Matrix edit + commit.

Estimated total: ~15-30 min per row, ~2-4 hours for the full matrix. Post-release operator should prioritise JEI first (highest-impact compat target with similar overlay math to ForgeBook's button), then REI, then the shader/perf layer (Embeddium/Oculus), then the HUD layer (Jade/InventoryHUD+).

## User Setup Required

None — purely a documentation deliverable, no external service configuration.

## Next Phase Readiness

- **For Plan 05-06 (release-smoke-doc):** This plan lands `docs/COMPATIBILITY.md` as the first file under `docs/`, establishing the directory. Plan 05-06 will add `docs/RELEASE-SMOKE.md` as a sibling; no blockers.
- **For Phase 5 closeout:** REL-04 SC-1 (documentation obligation) is satisfied by the skeleton + protocol. REL-04 SC-1 (physical verification obligation) is deferred to post-release per RESEARCH L593-599.
- **For README.md:** Plan 05-02's forward-reference at line 126 (`[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)`) now resolves to a real file.

## Self-Check: PASSED

- File existence: `docs/COMPATIBILITY.md` confirmed present (see `test -f` result in verification transcript).
- Commit existence: `0edd93d` confirmed via `git log --oneline -5`.
- All 8 compat mod names present (JEI, REI, Embeddium, Oculus, Jade, Mouse Tweaks, Quark, Inventory HUD+) — each appears exactly once.
- All 8 known-good version strings present verbatim.
- 16 `[ ] pending` cells across 8 matrix rows (verified via `grep -o` match count).
- `## Testing Protocol`, `## Re-run Triggers`, `## Contributing` sections each present exactly once.
- Forward-reference from `README.md` line 126 resolves to the newly created file.

---
*Phase: 05-release-polish*
*Plan: 05 (compatibility-matrix-docs)*
*Completed: 2026-04-16*
