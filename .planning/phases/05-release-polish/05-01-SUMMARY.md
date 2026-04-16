---
phase: 05-release-polish
plan: 01
subsystem: infra
tags: [packaging, release, gradle, mods-toml, logo, forge]

# Dependency graph
requires:
  - phase: 01-foundations-safe-egress
    provides: "ForgeGradle 6 + jarJar build scaffolding; src/main/resources/logo.png placeholder at JAR root; mods.toml skeleton with license/modId/displayURL/logoFile fields."
  - phase: 00-discovery
    provides: "THIRD_PARTY_NOTICES.md with jsoup MIT attribution; decision to ship MIT-licensed mod."
provides:
  - "build.gradle pinned to version 1.0.0 (release tag bump from 0.1.0)"
  - "settings.gradle pinned to rootProject.name = 'forgebook' (deterministic jar filename independent of cwd/worktree)"
  - "mods.toml credits field populated with jsoup MIT attribution (satisfies MIT 'permission notice' obligation via in-game manifest surface)"
  - "mods.toml issueTrackerURL pointing at GitHub Issues"
  - "src/main/resources/assets/forgebook/textures/gui/logo.png placeholder slot (byte-identical copy of JAR-root logo.png; forward-looking for v2 ChatPanelWidget brand mark)"
affects:
  - "05-02-README (install steps can reference forgebook-1.0.0.jar)"
  - "05-05-compatibility-test-matrix (build produces known-named jar for compat runs)"
  - "05-06-release-smoke-protocol (RELEASE-SMOKE.md Step 1 jar-tf assertion gated on forgebook-1.0.0.jar filename)"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Deterministic Gradle artifact naming via rootProject.name = 'forgebook' in settings.gradle"
    - "MIT attribution mirroring: credits field in mods.toml pairs with THIRD_PARTY_NOTICES.md for double-attribution (manifest UI + repo docs)"
    - "Dual logo slot convention: JAR-root logo.png for Forge mod-list + assets/<modid>/textures/gui/logo.png for future in-mod brand mark"

key-files:
  created:
    - "src/main/resources/assets/forgebook/textures/gui/logo.png"
  modified:
    - "build.gradle"
    - "settings.gradle"
    - "src/main/resources/META-INF/mods.toml"

key-decisions:
  - "Bumped version string directly from 0.1.0 -> 1.0.0 (no intermediate) per 05-RESEARCH §RESOLVED and CLAUDE.md release tag protocol."
  - "Added rootProject.name = 'forgebook' to settings.gradle as Rule 2 deviation (must-have truth #3 requires deterministic forgebook-1.0.0.jar filename regardless of worktree/cwd)."
  - "credits field phrasing taken verbatim from the license pattern in THIRD_PARTY_NOTICES.md: 'jsoup by Jonathan Hedley (MIT) — bundled as com.forgebook.shadow.jsoup'."
  - "issueTrackerURL placed immediately after displayURL (conventional grouping) per 05-PATTERNS.md."
  - "logoFile kept at JAR root ('logo.png', NOT moved to assets/) — explicit Forge anti-pattern guard per CLAUDE.md §'What NOT to Use' and 05-PATTERNS.md."
  - "In-chat logo placeholder created via Option A (byte-identical copy) per 05-RESEARCH §Placeholder Logo Generation L352-360; no branded PNG generated (user's post-release job)."

patterns-established:
  - "Dual-attribution pattern: bundled-dep licenses get in-manifest credit (mods.toml#credits) AND repo-root THIRD_PARTY_NOTICES.md. Apply to any future bundled dep."
  - "Forward-looking asset slot: create placeholder paths in advance of the code that will load them, so later phases can simply wire the ResourceLocation without also creating the file."
  - "Gradle settings.gradle anchoring: always set rootProject.name explicitly — never rely on cwd-derived default (breaks in worktrees, CI with shallow clones, renamed clones)."

requirements-completed: [REL-01]

# Metrics
duration: 11min
completed: 2026-04-16
---

# Phase 5 Plan 1: Release Packaging Polish Summary

**Version bump to 1.0.0, deterministic jar naming (`forgebook-1.0.0.jar`), jsoup MIT attribution in mods.toml, plus the second logo slot at `assets/forgebook/textures/gui/logo.png`.**

## Performance

- **Duration:** ~11 min
- **Started:** 2026-04-16T22:20:02Z
- **Completed:** 2026-04-16T22:30:49Z
- **Tasks:** 3
- **Files modified:** 3 (build.gradle, settings.gradle, mods.toml) + 1 created (asset logo slot)

## Accomplishments

- `./gradlew clean build` now produces `build/libs/forgebook-1.0.0.jar` (186 KB) — the artifact under test for REL-05.
- `mods.toml` carries the jsoup MIT permission notice in-manifest, satisfying the MIT license's attribution obligation at the Forge mod-list UI surface.
- `mods.toml` gained an `issueTrackerURL` field pointing at the GitHub issues page (standard Forge metadata).
- Both logo slots now exist as valid 67-byte 1×1 RGBA PNG placeholders — REL-01 SC-1 satisfied verbatim.
- `settings.gradle` now pins `rootProject.name = 'forgebook'`, making the jar filename deterministic regardless of worktree directory name or cwd.

## Task Commits

1. **Task 1: Bump build.gradle version to 1.0.0** — `30a70a7` (chore) — combined with the `rootProject.name` deviation fix below.
2. **Task 2: Populate mods.toml credits + add issueTrackerURL** — `f391f95` (docs)
3. **Task 3: Create in-chat logo placeholder by byte-copying existing placeholder** — `07b7f7a` (feat)

## Files Created/Modified

- **Modified** `build.gradle` — L12: `version = '0.1.0'` → `version = '1.0.0'`. No other lines touched.
- **Modified** `settings.gradle` — added `rootProject.name = 'forgebook'` (deviation — see below).
- **Modified** `src/main/resources/META-INF/mods.toml` — populated `credits` (L12), added new `issueTrackerURL` line (L10, immediately after `displayURL`). `logoFile`, `modId`, `license`, `authors`, `description`, and both dependency blocks untouched.
- **Created** `src/main/resources/assets/forgebook/textures/gui/logo.png` — 67 bytes, byte-identical to `src/main/resources/logo.png` (`cmp` passes; md5 `3e17337b1bab8fca8d1d4909c8ec4f68` on both). Forward-looking slot; no Java code references it yet.
- **Untouched** `gradle.properties` — MDK template defaults are dead code per CLAUDE.md; intentionally left alone.

## Decisions Made

See frontmatter `key-decisions`. Primary: version bump straight to 1.0.0 (no intermediate), in-manifest MIT attribution pattern for jsoup, and logo-slot creation via byte-identical copy (not new PNG generation).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 — Missing Critical] Added `rootProject.name = 'forgebook'` to `settings.gradle`**

- **Found during:** Task 1 (verify `build/libs/forgebook-1.0.0.jar` exists after clean build).
- **Issue:** Without an explicit `rootProject.name`, Gradle falls back to the root directory name. In the main checkout that produces `ForgeBook-1.0.0.jar` (capital-F, hyphen); in the parallel-executor worktree this plan ran in it produced `agent-a953d66f-1.0.0.jar`. The plan's must-have truth #3 requires literally `forgebook-1.0.0.jar` — the name is consumed by 05-06 (RELEASE-SMOKE.md Step 1 `jar tf build/libs/forgebook-1.0.0.jar`), 05-05 (compat test protocol), and 05-02 (README install steps). The jar-naming consistency predates Phase 5 — phase 01-01-PLAN.md L622 already expected `forgebook-0.1.0.jar`. In other words, the setting was always intended but never actually enforced.
- **Fix:** Appended `rootProject.name = 'forgebook'` to `settings.gradle` (below the existing `plugins { ... }` block). Single line, zero coupling with any other config.
- **Files modified:** `settings.gradle`
- **Verification:** `./gradlew clean build --no-daemon` now produces `build/libs/forgebook-1.0.0.jar` (186 KB) in the worktree; previously produced the worktree-directory-named jar.
- **Committed in:** `30a70a7` (alongside Task 1's version bump — both are single-line build-system fixes that form one coherent release-artifact-naming change).

---

**Total deviations:** 1 auto-fixed (1 missing critical infrastructure).
**Impact on plan:** The fix is a single extra line in a second file; it unblocks 3 downstream plans (05-02, 05-05, 05-06) that literally pattern-match on the jar filename. Zero scope creep — the jar-naming contract was already implicitly assumed by those plans and by phase 01's summary.

## Issues Encountered

### Deferred: `jarJar` task is SKIPPED — bundled jsoup not inside the main jar

- **Discovery:** During Task 1 verification, `jar tf build/libs/forgebook-1.0.0.jar | grep jsoup` returned zero matches. Gradle log explains: `Skipping task ':jarJar' as task onlyIf 'Task is enabled' is false.`
- **Verification this is pre-existing, not caused by plan 05-01:** Stashed the Task 1 edits and rebuilt from the exact pre-edit state (`version = '0.1.0'`, no rootProject.name override). Same result: `agent-a953d66f-0.1.0.jar` with zero jsoup classes inside and zero `META-INF/jarjar/` entries. The jsoup relocation task itself (`relocateJsoup`, a ShadowJar subclass) runs successfully and produces `build/relocated/jsoup-relocated-1.17.2.jar` — but the `jarJar` task that should nest it into the main artifact is disabled.
- **Why out of scope for 05-01:** Per GSD executor scope boundary rules, I only auto-fix issues *directly caused by the current task's changes*. This `jarJar` gap exists on the pre-edit tree and appears to be a long-standing bug in the Phase 1 build setup. Plan 05-01's objective is packaging polish (version, metadata, logo slot) — not ForgeGradle task-wiring debugging.
- **Impact on plan 05-01:** The plan's optional acceptance criterion *"`jar tf build/libs/forgebook-1.0.0.jar | grep -E 'META-INF/jarjar/jsoup-relocated-.*\.jar'` returns a match (jarJar bundling preserved)"* cannot be met. All other acceptance criteria are satisfied.
- **Blast radius:** Runtime code that calls `com.forgebook.shadow.jsoup.*` will fail `ClassNotFoundException` when the shipped jar is dropped into a real Minecraft installation. This is a REL-05 (prod-jar smoke) blocker, not a REL-01 blocker. Needs a dedicated fix-plan before Plan 05-06 runs its human-smoke-test — suggest phase 05 adds a plan 05-07 or folds the fix into plan 05-06 Task 2.
- **Likely root cause (for the next planner to investigate):** Either (a) ForgeGradle 6's `jarJar` task has an `onlyIf` that requires `jarJar` dependency declarations to have an explicit version range (instead of a raw `files(...)` reference), or (b) the task needs to be reobfuscated + wired into the `assemble` lifecycle differently. Worth checking the `reobfJarJar SKIPPED` line right below `jarJar SKIPPED` in the build log — same root cause likely applies. See build.gradle L54-67 for the current wiring.

### Documentation gap: mods.toml credits field was not mentioned in the original plan's manual edit-list for "fields to populate"

Not a deviation — plan L162-170 clearly called for the credits population. Noted here only because 05-RESEARCH did not mention the empty credits line explicitly (flagged in 05-PATTERNS.md metadata: "`mods.toml` has an empty `credits=""` field (L11) that REL-03 requires to be populated — not called out in RESEARCH"). Plan 05-01 correctly folded this into Task 2 regardless.

## User Setup Required

None — no external service configuration, no environment variables, no keys required for this plan. All changes are build-system / asset file edits.

## Next Phase Readiness

- **Plan 05-02 (README):** Can now safely reference `forgebook-1.0.0.jar` in install steps.
- **Plan 05-05 (compat matrix):** Compat test rows can invoke `cp build/libs/forgebook-1.0.0.jar run/mods/` reliably.
- **Plan 05-06 (release smoke):** RELEASE-SMOKE.md Step 1 (`ls build/libs/forgebook-1.0.0.jar` + `jar tf`) will work for the filename+size assertions — BUT the `jar tf | grep jsoup` sub-step will fail until the jarJar-disabled issue (see "Deferred" above) is fixed. Planner of Plan 05-06 should call this out explicitly and consider a pre-requisite build-fix plan.
- **Downstream code paths:** None changed. `ChatPanelWidget`, `AgentLoop`, `AiDispatcher`, etc. are untouched. No Java source edits at all.

## Self-Check: PASSED

Claimed artifacts verified present via Read / Bash `ls` / `test -f`:

- `build.gradle` L12 reads `version = '1.0.0'` — FOUND.
- `settings.gradle` contains `rootProject.name = 'forgebook'` — FOUND.
- `src/main/resources/META-INF/mods.toml` L10 `issueTrackerURL=...`, L12 `credits="jsoup by Jonathan Hedley (MIT) — bundled as com.forgebook.shadow.jsoup"` — FOUND.
- `src/main/resources/assets/forgebook/textures/gui/logo.png` exists, 67 bytes, md5 `3e17337b1bab8fca8d1d4909c8ec4f68`, byte-identical to `src/main/resources/logo.png` (cmp OK) — FOUND.
- `build/libs/forgebook-1.0.0.jar` exists, 186,315 bytes — FOUND.

Claimed commits verified via `git log --oneline`:

- `30a70a7` chore(05-01): bump version to 1.0.0 and pin rootProject.name — FOUND.
- `f391f95` docs(05-01): populate mods.toml credits + add issueTrackerURL — FOUND.
- `07b7f7a` feat(05-01): add in-chat logo placeholder slot (REL-01 SC-1) — FOUND.

---
*Phase: 05-release-polish*
*Completed: 2026-04-16*
