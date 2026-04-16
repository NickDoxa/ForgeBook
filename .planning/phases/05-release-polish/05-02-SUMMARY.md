---
phase: 05-release-polish
plan: 02
subsystem: docs
tags: [readme, license, attribution, security-posture, release-polish, rel-03]

# Dependency graph
requires:
  - phase: 01-foundation
    provides: LICENSE (MIT), THIRD_PARTY_NOTICES.md (jsoup MIT), config field definitions
  - phase: 02-core-ai-bridge
    provides: SafeHttpFetcher (SSRF guard), ApiKeyScrubFilter (Log4j2 redaction), package firewall
  - phase: 03-command-surface
    provides: 7 /forgebook subcommands (item, ask, reload, disable, enable, stats)
provides:
  - README.md at repo root documenting install, config, security posture, commands, logo, credits, license
  - Verified LICENSE (MIT) and THIRD_PARTY_NOTICES.md (jsoup MIT) unchanged — REL-03 attribution obligation satisfied
affects: [release-verification, first-time-server-owner-onboarding, security-compliance]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Single-doc security posture: README §Security Posture replaces a separate SECURITY.md (consolidation rationale documented in 05-RESEARCH; re-evaluate at v2 if doc grows)"
    - "Forward-reference markdown links: README links to docs/COMPATIBILITY.md before that file exists (arrives in plan 05-05)"
    - "Config-field tier table sourced from CLAUDE.md §(g) canonical classification rather than hand-invented"

key-files:
  created:
    - README.md
  modified: []

key-decisions:
  - "No separate SECURITY.md — consolidated into README §Security Posture per 05-RESEARCH L74 rationale (doc sprawl avoided at pre-v1)"
  - "Verified (did not modify) LICENSE + THIRD_PARTY_NOTICES.md — jsoup IS MIT, not Apache 2.0; task-prompt hint was incorrect per 05-RESEARCH §THIRD_PARTY_NOTICES status (verified at jsoup.org/license 2026-04-16)"
  - "Forward-reference to docs/COMPATIBILITY.md left as a link before target exists — GitHub shows broken-link warning until plan 05-05 lands in Wave 3; README is a forward-looking manifest"
  - "Version string in install steps: forgebook-1.0.0.jar (matches milestone v1.0 set by plan 05-01)"

patterns-established:
  - "Pattern: chmod 600 code-block (not prose) — threat T-05-02-01 mandates exact copy-paste-able command so operators can't typo file permissions and leak an API key"
  - "Pattern: NTFS ACL guidance stays prose — threat T-05-02-02 declined to prescribe a specific PowerShell command to avoid giving a wrong one; Windows server admins know their own tooling"
  - "Pattern: Attribution cross-links — README §Credits → THIRD_PARTY_NOTICES.md, mods.toml credits field (plan 05-01) → same file; satisfies MIT 'permission notice shall be included' obligation in-repo, in-manifest, and in-UI"

requirements-completed: [REL-03]

# Metrics
duration: ~5min
completed: 2026-04-16
---

# Phase 05 Plan 02: README & License Docs Summary

**Shipped repo-root README.md documenting server + client install, all 13 config fields (12 server + 1 client), server-side-only API key posture with `chmod 600` recommendation, SafeHttpFetcher SSRF guard, ApiKeyScrubFilter log redaction, and all 7 `/forgebook` subcommands; verified LICENSE (MIT) and THIRD_PARTY_NOTICES.md (jsoup MIT) unchanged.**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-04-16T22:17:00Z
- **Completed:** 2026-04-16T22:22:00Z
- **Tasks:** 2
- **Files modified:** 1 (README.md created; LICENSE + THIRD_PARTY_NOTICES.md intentionally unchanged)

## Accomplishments

- README.md at repo root with 11 sections: title/pitch, features, requirements, installation (server + client), configuration (12-row server table + 1-row client table), security posture, commands (7-row table), compatibility forward-link, logo customization, credits, license.
- Security posture section front-loads the `chmod 600 config/forgebook-server.toml` recommendation and names the three in-project defenses (SafeHttpFetcher SSRF guard, client-side package firewall, Log4j2 ApiKeyScrubFilter), plus the OP-only/5-req-per-minute default that prevents cost weaponization.
- REL-03 attribution obligation verified: LICENSE remains MIT (Nick Doxa 2026), THIRD_PARTY_NOTICES.md remains jsoup 1.17.2 MIT (Jonathan Hedley). Neither file was modified; the task-prompt hint that jsoup was Apache 2.0 was correctly rejected per 05-RESEARCH.

## Task Commits

Each task was committed atomically:

1. **Task 1: Verify LICENSE and THIRD_PARTY_NOTICES.md unchanged** — `c6395ad` (docs, --allow-empty) — verification-only, zero file changes
2. **Task 2: Write README.md at repo root** — `70d03c2` (docs)

## Files Created/Modified

- `README.md` (created, 150 lines, 970 words) — Repo landing page documenting install, config, security posture, commands, logo customization, credits, license. Single source of truth for a first-time server owner.
- `LICENSE` (verified unchanged) — MIT, Copyright (c) 2026 Nick Doxa.
- `THIRD_PARTY_NOTICES.md` (verified unchanged) — jsoup 1.17.2 MIT, Copyright (c) 2009-2024 Jonathan Hedley; bundled as `com.forgebook.shadow.jsoup`.

## Decisions Made

1. **No separate SECURITY.md** — Consolidated security posture into README §Security Posture per 05-RESEARCH L74 rationale. ForgeBook is pre-v1; a separate doc would be doc sprawl. Re-evaluate if the section grows past ~15 bullets.
2. **Rejected the task-prompt hint that jsoup is Apache 2.0** — 05-RESEARCH §THIRD_PARTY_NOTICES status verified at jsoup.org/license on 2026-04-16 that jsoup is MIT. THIRD_PARTY_NOTICES.md already reflects MIT and was NOT changed. Changing it to Apache 2.0 would have introduced a false attribution.
3. **Forward-reference link to `docs/COMPATIBILITY.md`** — That file does not yet exist (arrives in plan 05-05, Wave 3). README is a forward-looking manifest; GitHub will show a broken-link warning until 05-05 lands. Accepted per plan <verification> §6.
4. **Version string `forgebook-1.0.0.jar`** — Matches milestone v1.0 set by plan 05-01 (`gradle.properties` mod_version). Aligns install instructions with the actual jar name a server owner will download.
5. **`chmod 600 config/forgebook-server.toml` rendered as a code block, not prose** — Threat T-05-02-01: operators must be able to copy-paste without typos. Acceptance criteria grep (`chmod 600 config/forgebook-server.toml`) locks this exact string.
6. **Windows NTFS ACL guidance stays prose** — Threat T-05-02-02: declined to prescribe a specific PowerShell `icacls` or `Set-Acl` command since getting it wrong would be worse than a generic "set the ACL so only the server-running account can read" note. Windows server admins know their own tooling.

## Deviations from Plan

None — plan executed exactly as written. All 26 acceptance-criteria greps pass on first write; word count 970 ≥ 500 sanity threshold; zero modifications to LICENSE or THIRD_PARTY_NOTICES.md (`git diff` empty).

## Issues Encountered

None. Both tasks were purely documentation work with no runtime code involved.

## User Setup Required

None — no external service configuration required for this plan.

## Next Phase Readiness

- Plan 05-03 (fine-tune Anthropic system prompt) can proceed independently — no dependency on README.
- Plan 05-05 (compatibility matrix) will create `docs/COMPATIBILITY.md`, which fulfills the forward-reference link in README.md §Compatibility. When that plan lands, the README link becomes live.
- Phase 05 verification step will grep README for the REL-03 mandated strings documented in this SUMMARY's acceptance-criteria.

## Self-Check: PASSED

- README.md exists at repo root (`test -f README.md` → OK)
- Task 1 commit `c6395ad` exists in git log (verification-only)
- Task 2 commit `70d03c2` exists in git log (README creation)
- LICENSE + THIRD_PARTY_NOTICES.md unmodified (`git diff LICENSE THIRD_PARTY_NOTICES.md` → empty)
- All 22 required strings present (chmod 600, 12 server fields, 1 client field, 7 subcommands, SafeHttpFetcher, ApiKeyScrubFilter, Java 17, Forge 1.20.1-47.4.18, Both client and server)
- All 3 link patterns present (LICENSE, THIRD_PARTY_NOTICES.md, docs/COMPATIBILITY.md)
- SECURITY.md reference count = 0 (correct — no separate SECURITY.md)
- Word count 970 ≥ 500 sanity threshold

---
*Phase: 05-release-polish*
*Plan: 02*
*Completed: 2026-04-16*
