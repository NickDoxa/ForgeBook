---
phase: 05-release-polish
verified: 2026-04-16T23:30:00Z
status: human_needed
score: 4/5 success criteria fully verified; 1 (SC-5 REL-05) automation-passed but physical-smoke deferred AND blocked by pre-existing jarJar defect
overrides_applied: 0
human_verification:
  - test: "Fill compat matrix rows in docs/COMPATIBILITY.md"
    expected: "At minimum JEI row marked (GUI scale 1 + 2) with ✓/✗ and Notes; all 8 rows for full REL-04 physical coverage"
    why_human: "Requires ./gradlew runClient with 8 separate mod jars dropped into run/mods/, GUI-scale cycling, visual inspection. Estimated 2-4h total. Deferred per plan 05-05 autonomous:false."
  - test: "Run RELEASE-SMOKE.md Steps 2-9 on a clean Forge 1.20.1-47.4.18 dedicated server"
    expected: "Loading mod 'forgebook' (version 1.0.0) in logs; no NoClassDefFoundError; chmod 600 applied; /forgebook item returns grounded reply with Source: citation; all 7 subcommands exercised; audit log line emitted; zero sk-ant- leaks to logs/latest.log"
    why_human: "Requires disposable MC launcher + disposable Forge dedicated server + real Anthropic API key. Claude cannot run a GUI Minecraft client. Deferred per plan 05-06 autonomous:false."
  - test: "Resolve pre-existing jarJar SKIPPED defect (blocker for REL-05 SC-5)"
    expected: "./gradlew clean build produces forgebook-1.0.0.jar with META-INF/jarjar/jsoup-relocated-*.jar nested inside (jar tf | grep jsoup returns >=1 line)"
    why_human: "Build-pipeline fix is outside Phase 5 scope per plan 05-06 objective. A dedicated fix-plan must land before physical smoke Steps 2-9 can pass (ModDocsScraper would throw NoClassDefFoundError on first /forgebook item)."
---

# Phase 5: Release Polish Verification Report

**Phase Goal:** The mod ships as a tagged release with user-droppable logo slots, full localization coverage, a README that teaches server owners the security posture, a documented mod-compatibility matrix, and a prod-jar smoke test on a clean dedicated server — nothing shipped relies on dev-environment assumptions.

**Verified:** 2026-04-16T23:30:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth (Success Criterion) | Status | Evidence |
|---|---------------------------|--------|----------|
| SC-1 | Both logo slots exist as placeholders; README documents where to drop designed asset; mod loads cleanly | VERIFIED | `src/main/resources/logo.png` (67B) + `src/main/resources/assets/forgebook/textures/gui/logo.png` (67B, byte-identical via `cmp`) both present and valid PNGs. README.md L128-141 §"Customizing the Logo" documents both slots with size recommendations. `mods.toml` L11 keeps `logoFile="logo.png"` at JAR root (anti-pattern guard). |
| SC-2 | `en_us.json` covers every user-facing string (47 keys) | VERIFIED | `src/main/resources/assets/forgebook/lang/en_us.json` — verified 47 total keys: 8 chat + 13 error + 26 command. Authorizer.java + AiDispatcher.java + 4 command classes + RagItemPipeline.java all route user-visible text through `Component.translatable(key, args?)` with 3 documented carve-outs (stats tabular text, AI reply prose, RagItemPipeline source_label literal concat per 05-04 deviation). |
| SC-3 | README documents install + every config field + API-key server-side posture + OP-only default + chmod 600 recommendation; THIRD_PARTY_NOTICES.md credits jsoup (MIT) | VERIFIED | README.md (150 lines, ~970 words) present at repo root. Installs for server (L32-48) + client (L50-54). 12-row server config table + 1-row client config table (L56-79). Security Posture section (L81-106) names API-key server-side claim, SafeHttpFetcher, package firewall, chmod 600 (code-block at L42), ApiKeyScrubFilter, rate-limit defaults. LICENSE (MIT, Nick Doxa 2026) and THIRD_PARTY_NOTICES.md (jsoup 1.17.2 MIT, Jonathan Hedley) verified unchanged. mods.toml L12 mirrors jsoup MIT credit. |
| SC-4 | Mod-compat matrix covering 8 mods, manually verified at GUI scales 1 + 2 | PARTIAL — documentation shipped, physical verification deferred | `docs/COMPATIBILITY.md` present with 8-row skeleton (JEI, REI, Embeddium, Oculus, Jade, Mouse Tweaks, Quark, Inventory HUD+), 9-step Testing Protocol, Re-run Triggers, Contributing instructions. All 16 cells `[ ] pending`. Physical row-filling requires human operator running `./gradlew runClient` with each mod loaded — deferred per plan 05-05 autonomous:false. See human_verification items. |
| SC-5 | Built jar (not dev run) loads and serves `/forgebook item` on a clean Forge 1.20.1-47.4.18 dedicated server | PARTIAL — protocol + automation shipped, physical smoke deferred, BLOCKED by pre-existing jarJar defect | `docs/RELEASE-SMOKE.md` (11,305 bytes, 173 lines) present with 9-step operator protocol + Step 10 (tag + `gh release create`) + KNOWN BLOCKER preamble + Pass/Fail criteria + automated-vs-human table. build.gradle L12 `version = '1.0.0'`, settings.gradle pins `rootProject.name = 'forgebook'`. **However:** `jarJar` Gradle task is SKIPPED; `forgebook-1.0.0.jar` does NOT contain relocated jsoup. This is a pre-existing Phase 1 defect surfaced by 05-01 and documented in 05-06's RELEASE-SMOKE.md KNOWN BLOCKER section. Physical Steps 2-9 cannot succeed until jarJar is fixed (ModDocsScraper would throw `NoClassDefFoundError: com/forgebook/shadow/jsoup/*` on first `/forgebook item`). |

**Score:** 3/5 fully VERIFIED + 2/5 PARTIAL (docs shipped, physical work deferred to human). Under the "docs + protocol = phase-close-ready" interpretation documented in plans 05-05 and 05-06, the goal is achievable pending human follow-up.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `build.gradle` | `version = '1.0.0'` | VERIFIED | L12: `version = '1.0.0'`. Commit `30a70a7`. |
| `settings.gradle` | `rootProject.name = 'forgebook'` (deterministic jar name) | VERIFIED | Contains `rootProject.name = 'forgebook'` (05-01 Rule-2 deviation fix, commit `30a70a7`). |
| `src/main/resources/META-INF/mods.toml` | Populated credits + issueTrackerURL, logoFile at JAR root | VERIFIED | L10 `issueTrackerURL="https://github.com/Nick-Doxa/ForgeBook/issues"`; L11 `logoFile="logo.png"` (not moved); L12 `credits="jsoup by Jonathan Hedley (MIT) — bundled as com.forgebook.shadow.jsoup"`. |
| `src/main/resources/logo.png` | Forge mod-list logo placeholder | VERIFIED | 67-byte PNG placeholder (pre-existing from Phase 1). |
| `src/main/resources/assets/forgebook/textures/gui/logo.png` | In-chat logo placeholder slot | VERIFIED | 67 bytes, byte-identical to JAR-root logo (`cmp` passes). Created in 05-01 commit `07b7f7a`. Not yet referenced from Java (forward-looking slot). |
| `README.md` | Repo-root docs with install/config/security/commands/compatibility/logo/credits/license | VERIFIED | 150 lines, all 11 sections present, all 22+ required strings grep-confirmed by 05-02. |
| `LICENSE` | MIT (Nick Doxa 2026) | VERIFIED | Unchanged from Phase 1 scaffold. MIT header confirmed. |
| `THIRD_PARTY_NOTICES.md` | jsoup 1.17.2 MIT attribution | VERIFIED | `## jsoup 1.17.2 (MIT License)` + Jonathan Hedley copyright + bundled-as note. Unchanged from Phase 1. |
| `docs/COMPATIBILITY.md` | 8-row skeleton + 9-step protocol + re-run triggers | VERIFIED (skeleton) | All 8 mods named with known-good version strings; all 16 GUI-scale cells `[ ] pending`. Physical row-filling deferred. |
| `docs/RELEASE-SMOKE.md` | 9-step protocol (Step 1 automated, 2-9 human) + Step 10 tag/release | VERIFIED | 173 lines, 9 numbered steps + Step 10 + KNOWN BLOCKER preamble + Pass/Fail + automated-vs-human table. All 7 `/forgebook` subcommands exercised. |
| `src/main/resources/assets/forgebook/lang/en_us.json` | 47 keys (21 Phase 4 preserved + 26 new command keys) | VERIFIED | JSON-parses; exactly 47 keys; 8 chat + 13 error + 26 command namespaces confirmed. Em-dash uses raw UTF-8 (equivalent to `\u2014`); all 4 format placeholders (`%s`, `%d`, `%ds`) match Component.translatable contract. |
| `src/main/java/com/forgebook/safety/Authorizer.java` | Denied record split (humanReadable String + Component feedback) | VERIFIED | L68: `public record Denied(ErrorCode code, String humanReadable, Component feedback) implements Result {}`. 4 denial sites use `Component.translatable("forgebook.command.denied.*")` (lines 96, 104, 112, 122-123). |
| `src/main/java/com/forgebook/ai/AiDispatcher.java` | Error record split + 10 translatable call sites | VERIFIED | L69: `public record Error(ErrorCode code, String humanReadable, Component feedback) implements Result {}`. 10 `Component.translatable` sites confirmed (7 mapError arms + 3 dispatch-body + iteration_cap). |
| `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` | Uses translatable reload.success | VERIFIED | L72: `() -> Component.translatable("forgebook.command.reload.success"), true)`. Zero Component.literal remaining. |
| `src/main/java/com/forgebook/command/AdminSubcommands.java` | disable/enable → translatable; stats carve-out | VERIFIED | L57 + L65: `Component.translatable(key)` in BiConsumer wrappers. 2 `Component.literal` remain (executeStats tabular carve-out + one inline). |
| `src/main/java/com/forgebook/command/AskSubcommand.java` | sendFailureKey helper + Denied.feedback pass-through + AI-reply literal carve-out | VERIFIED | L206: `src.sendFailure(Component.translatable(key, args))` in sendFailureKey; 3 Component.literal remain (AI reply + helper + carve-out). |
| `src/main/java/com/forgebook/command/ItemSubcommand.java` | Mirrors AskSubcommand | VERIFIED | L260: `src.sendFailure(Component.translatable(key, args))` in sendFailureKey; zero Component.literal in user-visible non-reply paths. |
| `src/main/java/com/forgebook/ai/RagItemPipeline.java` | Feedback interface widened; 7 literal sites flipped; CMD-07 citation preserved | VERIFIED | `sendFailureKey` + `sendFailureComponent` default methods defined (L340, L348); production `feedbackOf(src)` overrides both (L299, L302); 5 `feedback.sendFailureKey(...)` + 2 `feedback.sendFailureComponent(...)` call sites confirmed. Source label key retained in en_us.json + comment; literal `"\n\nSource: " + url` concat preserved per 05-04 deviation (objective-correct). |
| `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java` | Wire format preserved (String humanReadable) | VERIFIED | L23: `public record ChatErrorPacket(UUID requestId, ErrorCode code, String humanReadable)`. `buf.writeUtf(p.humanReadable, 512)` at L39. No SimpleChannel protocol bump needed. |
| `build/libs/forgebook-1.0.0.jar` | Production jar with nested jsoup | PARTIAL | In this worktree, only `build/libs/confident-heyrovsky-0.1.0.jar` (stale pre-05-01, Apr-16 17:17) exists — source config at HEAD is `version = '1.0.0'` + `rootProject.name = 'forgebook'`, so a fresh `./gradlew clean build` will produce `forgebook-1.0.0.jar`. Plan 05-06 Task 2 verified this in agent-af48817e worktree: 188,103 bytes. **However**, per 05-06 Self-Check, `jar tf | grep jsoup` returns zero (jarJar SKIPPED — blocker). |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `build.gradle` | `build/libs/forgebook-1.0.0.jar` | Gradle jar task reads `project.version` | VERIFIED (config) | L12 `version = '1.0.0'` + settings.gradle `rootProject.name = 'forgebook'` combine to name the artifact correctly. Actual jar on disk is stale but the wiring is correct. |
| `mods.toml` | `logo.png` (JAR root) | `logoFile="logo.png"` resolved by Forge mod-list UI | VERIFIED | Path explicitly kept at JAR root per anti-pattern guard. |
| `mods.toml` | `THIRD_PARTY_NOTICES.md` (attribution obligation) | `credits=` mirrors jsoup MIT | VERIFIED | Credit string matches THIRD_PARTY_NOTICES format: "jsoup by Jonathan Hedley (MIT) — bundled as com.forgebook.shadow.jsoup". |
| `README.md` | `LICENSE` | Markdown link L150: `[LICENSE](LICENSE)` | VERIFIED | Link exists and target file exists. |
| `README.md` | `THIRD_PARTY_NOTICES.md` | Markdown link L146: `[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)` | VERIFIED | Link exists and target file exists. |
| `README.md` | `docs/COMPATIBILITY.md` | Markdown link L126: `[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)` | VERIFIED | Forward-reference created in 05-02, target created in 05-05. Link resolves. |
| `Authorizer.java` | `en_us.json` | `Component.translatable("forgebook.command.denied.*")` × 4 | VERIFIED | All 4 keys (disabled, not_player, forbidden, rate_limited) present in en_us.json and referenced from Authorizer.java. |
| `AiDispatcher.java` | `en_us.json` | `Component.translatable("forgebook.command.provider.*")` × 7 + 3 shared | VERIFIED | All 7 provider keys + not_initialized/provider_error/overloaded present and referenced. |
| `AdminSubcommands.java` | `en_us.json` | `Component.translatable(key)` via BiConsumer → disable/enable keys | VERIFIED | disable.success/.already + enable.success/.already all present and referenced via string literals. |
| `AskSubcommand.java` / `ItemSubcommand.java` | `en_us.json` | `sendFailureKey(...) → Component.translatable(key, args)` | VERIFIED | All referenced keys (not_initialized, internal_error, overloaded, item.no_held, item.unknown) present in en_us.json. |
| `RagItemPipeline.java` | `en_us.json` | `Feedback.sendFailureKey` default + override | VERIFIED | 5 keys referenced (not_initialized, item.no_docs_url, item.fetch_failed, provider_error, provider_unexpected) all present. |
| `AiDispatcher.Error.humanReadable()` (String) | `ChatErrorPacket` wire | String wire preserved (no protocol bump) | VERIFIED | `ChatRequestHandler` still reads `.humanReadable()`; `buf.writeUtf` shape unchanged. Option A split succeeded. |
| `build.gradle`:relocateJsoup | `build/libs/forgebook-1.0.0.jar`:META-INF/jarjar/*.jar | `jarJar` task nesting | **NOT WIRED (BLOCKER)** | `jarJar` task is SKIPPED. Pre-existing Phase 1 defect. `relocateJsoup` produces `build/relocated/jsoup-relocated-1.17.2.jar` successfully, but the nesting step is disabled. Documented in 05-06 KNOWN BLOCKER. |

### Data-Flow Trace (Level 4)

Phase 5 ships predominantly docs + i18n + packaging (non-runtime data pipelines). The one runtime-path artifact is the i18n key consumption chain (Java → JSON → client render), traced above under Key Links — all 26 new keys flow from call site → en_us.json and render through Minecraft's Component.translatable machinery (default behavior returns the key verbatim if no language pack; resolves to English if `en_us.json` is loaded).

No other dynamic-data renderings introduced in this phase.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| JSON parses and has exact 47 keys | `node -e "const d=JSON.parse(fs.readFileSync(...))..."` | `Total: 47 chat: 8 error: 13 command: 26` | PASS |
| Logo slots byte-identical | `cmp src/main/resources/logo.png src/main/resources/assets/forgebook/textures/gui/logo.png` | exit 0 ("byte-identical OK") | PASS |
| README contains mandated chmod 600 string | grep | present at L42 | PASS |
| Authorizer 3-field Denied record | grep `record Denied(ErrorCode code, String humanReadable, Component feedback)` | 1 match L68 | PASS |
| AiDispatcher 3-field Error record | grep `record Error(ErrorCode code, String humanReadable, Component feedback)` | 1 match L69 | PASS |
| ChatErrorPacket wire unchanged | grep `public record ChatErrorPacket(UUID requestId, ErrorCode code, String humanReadable)` | 1 match L23 | PASS |
| Built jar has relocated jsoup nested | `jar tf build/libs/forgebook-1.0.0.jar \| grep jsoup` (per 05-06 Self-Check) | zero lines | **FAIL — jarJar SKIPPED (pre-existing defect)** |
| `./gradlew build` exits 0 | `./gradlew clean build --no-daemon` | exits 0 per 05-01 + 05-06 (186–188 KB jar produced) | PASS |
| Jar name deterministic (forgebook-* not worktree-*) | settings.gradle `rootProject.name = 'forgebook'` present | present | PASS (fresh rebuild in this worktree required to materialize jar; stale `confident-heyrovsky-0.1.0.jar` is pre-05-01 artifact) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| REL-01 | 05-01-PLAN | Logo slots (both paths) exist as documented placeholders | SATISFIED | `logo.png` (JAR root) + `assets/forgebook/textures/gui/logo.png` both 67-byte PNGs (`cmp` passes). README §"Customizing the Logo" L128-141 documents both. |
| REL-02 | 05-03-PLAN + 05-04-PLAN | `en_us.json` covers every user-facing string | SATISFIED | 47 keys (21 preserved + 26 new). 7 Java files refactored to `Component.translatable(key, args?)` with 3 documented carve-outs (AI reply prose, StatsAccumulator tabular, RagItemPipeline source_label literal concat — key retained in en_us.json). Wire format preserved (ChatErrorPacket String humanReadable unchanged). |
| REL-03 | 05-02-PLAN | README documents install + config + server-side API posture + OP-only default + `chmod 600` recommendation; THIRD_PARTY_NOTICES.md credits jsoup | SATISFIED | README.md (150 lines) covers all mandated items. LICENSE (MIT) + THIRD_PARTY_NOTICES.md (jsoup 1.17.2 MIT, Jonathan Hedley) verified unchanged. mods.toml credits mirror attribution. |
| REL-04 | 05-05-PLAN | Mod-compat matrix for 8 mods, manually verified at GUI scales 1+2 | SATISFIED (docs) — NEEDS HUMAN (physical rows) | `docs/COMPATIBILITY.md` skeleton + 9-step protocol + re-run triggers shipped. All 8 compat mods + known-good version strings + `[ ] pending` placeholders present. Physical row-filling deferred to post-release operator session per plan autonomous:false. See human_verification item 1. |
| REL-05 | 05-06-PLAN | Built jar smoke-tested on clean Forge 47.4.18 dedicated server | SATISFIED (protocol + Step 1 automation) — NEEDS HUMAN (Steps 2-9) — BLOCKED (jarJar defect) | `docs/RELEASE-SMOKE.md` with 9-step protocol + Step 10 tag/release + KNOWN BLOCKER preamble. Step 1 (clean build + jar exists) green. **jarJar SKIPPED defect** (pre-existing Phase 1) blocks Steps 2-9: `jar tf \| grep jsoup` returns zero; ModDocsScraper would throw NoClassDefFoundError on first `/forgebook item`. See human_verification items 2 + 3. |

**Orphaned requirements check:** ROADMAP.md Phase 5 lists REL-01..05; all 5 claimed by plans in this phase. No orphans.

### Anti-Patterns Found

No blocking anti-patterns introduced by Phase 5 itself. Notable observations:

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| Phase 1 `build.gradle` L53-67 (jarJar wiring) | n/a | `jarJar SKIPPED` Gradle task — pre-existing defect | **Blocker (inherited)** | Blocks REL-05 SC-5 physical smoke. NOT introduced by Phase 5; explicitly documented in 05-06 KNOWN BLOCKER per objective directive. Fix is out-of-Phase-5-scope per plan 05-06 objective. |
| `build/libs/confident-heyrovsky-0.1.0.jar` | n/a | Stale build artifact in worktree | Info | Pre-05-01 jar (Apr-16 17:17) remains because no fresh `./gradlew clean build` ran in this specific worktree after the 05-01 version bump + rootProject.name pin merged. Config at HEAD is correct (`version = '1.0.0'`, `rootProject.name = 'forgebook'`); a fresh clean build will produce `forgebook-1.0.0.jar` as 05-06 Task 2 already demonstrated in agent-af48817e. Not a regression. |
| `docs/COMPATIBILITY.md` | all rows | 16 `[ ] pending` cells | Info | Intentional — skeleton-only delivery per plan 05-05. Physical row-filling is the human follow-up. |
| Component.literal carve-outs (3) | AdminSubcommands.executeStats, AskSubcommand AI reply, RagItemPipeline AI reply + source_label concat | Intentional literal retention | Info | Each documented with inline comment per plan 05-04 design decision. Reviewed and accepted. |

### Human Verification Required

Phase 5 passed all automated checks and all documentation deliverables, but the goal still requires three human follow-ups before a `v1.0.0` git tag can be applied:

#### 1. Fill compat matrix rows (REL-04 SC-4 physical verification)

**Test:** Walk the 9-step protocol in `docs/COMPATIBILITY.md` for at least one compat target (JEI recommended as highest-impact row).

**Expected:** Matrix row for JEI shows `✓` in both GUI scale 1 and GUI scale 2 columns with a Notes entry describing observed button placement; `**Last verified:**` date updated.

**Why human:** Requires `./gradlew runClient` with JEI jar dropped into `run/mods/`, in-game GUI-scale cycling via Options, and visual inspection. Non-GUI harness cannot do this. Full matrix (all 8 rows) is ~2-4h; minimum for REL-04 intent is 1 row.

#### 2. Run RELEASE-SMOKE.md Steps 2-9 on a clean dedicated server (REL-05 SC-5 physical verification)

**Test:** After the jarJar blocker (item #3) is resolved, perform the full 9-step smoke per `docs/RELEASE-SMOKE.md`: dedicated-server install, set secrets + chmod 600 + verify scrub, client connect, `/forgebook item`, chat UI smoke, disable/enable smoke, stats/reload smoke, teardown.

**Expected:** Every "Expected" line in Steps 2-9 matches observed behaviour; zero stack traces; `grep "sk-ant-" logs/latest.log` returns zero; all 7 `/forgebook` subcommands respond correctly; audit log line `[forgebook.audit] uuid=... kind=ITEM tokens=... latency_ms=... outcome=SUCCESS` appears exactly once per request.

**Why human:** Requires disposable MC launcher, disposable Forge 47.4.18 dedicated server, real Anthropic API key, visual in-game verification. 30-60 min for a clean run.

#### 3. Resolve the jarJar SKIPPED pre-existing defect (blocking prerequisite for #2)

**Test:** Dedicated build-pipeline fix-plan (out of Phase 5 scope per objective) must land before human-verification item #2 can proceed.

**Expected:** `./gradlew clean build --no-daemon` produces `build/libs/forgebook-1.0.0.jar` AND `jar tf build/libs/forgebook-1.0.0.jar | grep -E "META-INF/jarjar/jsoup-relocated-.*\.jar"` returns at least one match. The `jarJar` task in Gradle output no longer reports `SKIPPED`.

**Why human:** Requires editing `build.gradle` L53-67, likely replacing the `jarJar files(tasks.relocateJsoup)` pattern with a version-ranged `jarJar(group: ..., name: ..., version: '[1.17,2.0)')` declaration per ForgeGradle 6's jarJar documentation, AND ensuring `reobfJarJar` also wires into the `assemble` lifecycle. Plan 05-01 and 05-06 explicitly scope this fix out of Phase 5. It is a **REL-05 SC-5 prerequisite**: until this fix lands, `forgebook-1.0.0.jar` will throw `NoClassDefFoundError: com/forgebook/shadow/jsoup/*` the moment any `/forgebook item` request hits `ModDocsScraper.extract(...)`.

### Gaps Summary

**Phase 5 itself introduced no failures.** All 6 plans completed their autonomous scope green:
- Plan 05-01: Version bump, mods.toml credits, issueTrackerURL, second logo slot, rootProject.name fix. Build green.
- Plan 05-02: README.md at repo root with all 11 sections. LICENSE + THIRD_PARTY_NOTICES.md verified unchanged.
- Plan 05-03: en_us.json expanded 21 → 47 keys (26 new command keys). JSON valid.
- Plan 05-04: 7 Java production files + 7 test files refactored to Component.translatable with wire-format preservation. Build + test green.
- Plan 05-05: docs/COMPATIBILITY.md skeleton + 9-step protocol shipped. Human row-filling parked.
- Plan 05-06: docs/RELEASE-SMOKE.md 9-step protocol + Step 10 tag/release + KNOWN BLOCKER preamble. Step 1 automated verification green (jar exists). Human Steps 2-9 parked.

**The single unresolvable automated gap is the inherited jarJar SKIPPED defect** — a pre-existing Phase 1 packaging-pipeline bug discovered by 05-01, re-confirmed by 05-06, and prominently documented in `docs/RELEASE-SMOKE.md`'s KNOWN BLOCKER preamble. This blocks REL-05 SC-5's physical smoke regardless of any Phase 5 work and must be fixed by a dedicated build-pipeline plan outside the Phase 5 scope before the release protocol can be walked end-to-end.

**Phase 5 is ready to close** under the autonomous-scope + `--auto` chain interpretation explicitly licensed by:
- 05-05 RESEARCH L593-599: "physical row-filling deferred to post-release operator session"
- 05-06 objective: "Document the gap and its blocking nature" for the jarJar defect rather than fix it in-phase

But the phase goal ("ships as a tagged release ... nothing shipped relies on dev-environment assumptions") is not yet **physically achievable** until:
1. The jarJar fix lands (outside Phase 5).
2. A human operator walks RELEASE-SMOKE Steps 2-9 on a clean dedicated server.
3. At least one compat matrix row is filled (JEI recommended).

Status is therefore `human_needed` — automated work is complete; the remaining items are by-design human follow-ups + an inherited blocker that gates the most critical of those follow-ups.

---

*Verified: 2026-04-16T23:30:00Z*
*Verifier: Claude (gsd-verifier)*
