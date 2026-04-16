---
phase: 03-command-surface-safety-controls
plan: "04"
subsystem: ai
tags: [rag, single-shot, item-command, citation, safe-http, pattern-3]
requires:
  - com.forgebook.safety.Authorizer (Plan 03-03, Wave 2)
  - com.forgebook.safety.RateLimiterHolder (Plan 03-01, Wave 1)
  - com.forgebook.safety.RequestAuditLogger (Plan 03-02, Wave 1)
  - com.forgebook.ai.RequestKind (Plan 03-01, Wave 1)
  - com.forgebook.ai.AiDispatcher (existing; mapError reused for ProviderError path)
  - com.forgebook.ai.AiTurn (existing; FinalReply / ProviderError variants)
  - com.forgebook.ai.ChatRequest (existing)
  - com.forgebook.ai.provider.ProviderFactory (existing)
  - com.forgebook.config.ConfigHolder (existing; D-14 single volatile load)
  - com.forgebook.ai.SystemPromptCache (existing)
  - com.forgebook.util.SafeHttpFetcher (existing, Phase 1 SSRF chokepoint)
  - com.forgebook.integration.scraper.ModDocsScraper (existing, D-16)
  - com.forgebook.integration.scraper.PromptFraming (existing, D-10)
provides:
  - com.forgebook.ai.RagItemPipeline (public `run(...)` entry + package-private `runInternal(...)` test seam)
  - com.forgebook.ai.RagItemPipeline.Feedback / AuthFn / FetchFn (package-private test seams)
affects:
  - Plan 03-06b (ItemSubcommand will call RagItemPipeline.run from a tick-thread Brigadier handler)
tech_stack:
  added: []
  patterns:
    - "Pattern 3 anchor: ChatRequest.tools = List.of() — Anthropic cannot return stop_reason=tool_use with empty tools[], eliminating the entire tool-using loop"
    - "SAFE-06 ordering: Authorizer.authorize BEFORE any fetch or provider call"
    - "D-10 framing: mod doc wrapped via PromptFraming.wrap (<mod_doc trust=\"untrusted\">)"
    - "D-14 single volatile load: ConfigHolder.get() captured once at entry and threaded"
    - "CMD-07 citation invariant: success reply always ends with '\\n\\nSource: ' + modURL"
    - "Package-private primitive-overload test seam (Plan 03-03 Authorizer precedent — avoids mocking Minecraft classes per CLAUDE.md)"
    - "StatsAccumulator as audit oracle: observable state (render() + resetForTests) replaces verify-mock for RequestAuditLogger fan-out"
key_files:
  created:
    - src/main/java/com/forgebook/ai/RagItemPipeline.java
    - src/test/java/com/forgebook/ai/RagItemPipelineTest.java
  modified: []
decisions:
  - "Deviated from plan's Mockito static/construction harness — used package-private runInternal(Feedback, AuthFn, FetchFn, Function<ConfigSnapshot,AiProvider>) seam instead. CLAUDE.md prohibits mocking Minecraft classes (ServerPlayer/CommandSourceStack), and Plan 03-03 Authorizer already established the primitive-overload precedent. Tests drive runInternal with pure-Java fakes — zero Mockito use."
  - "StatsAccumulator.render() as the audit oracle (observable state) instead of MockedStatic<RequestAuditLogger>. Keeps RequestAuditLogger as a concrete static helper (SAFE-04) while still giving tests a crisp per-test oracle."
  - "Reused AiDispatcher.mapError (package-private, same package com.forgebook.ai) for the ProviderError path — symmetry with /forgebook ask failure presentation."
  - "Fallback system prompt literal: \"You are ForgeBook. Cite source URLs. Be concise.\" — used only if SystemPromptCache returns null or empty. SystemPromptCache seeding is Plan 06's scope; pipeline must not NPE before that."
  - "Crude token estimator (chars/4) when AiTurn.FinalReply.usage is absent — matches Anthropic's published tokenizer rule of thumb and mirrors AiDispatcher.estimateTokens from Plan 03-03."
  - "RagItemPipeline does NOT wrap sendSuccess/sendFailure in server.execute(...) — caller contract documents that Plan 06 ItemSubcommand is responsible for tick-thread ordering. Double-hopping would be harmless but adds a tick of latency."
metrics:
  duration_seconds: 2400
  completed_date: "2026-04-16"
  task_count: 2
  file_count: 2
  test_count: 7
---

# Phase 3 Plan 04: RagItemPipeline RAG Single-Shot Pipeline Summary

Deterministic, single-shot RAG orchestrator for `/forgebook item` — authorize → check modURL → fetch+scrape+frame → one `provider.chat(empty tools[])` → audit → feedback — with CMD-07 citation invariant appended on every success path.

## What Was Built

**New production files:**
- `RagItemPipeline.java` (~314 lines) — `public static void run(CommandSourceStack, ServerPlayer, String modId, String itemId, Optional<URL> modURL, RequestKind)` plus a package-private `runInternal(...)` that takes pure-Java seams (`Feedback`, `AuthFn`, `FetchFn`, `Function<ConfigSnapshot, AiProvider>`). The production `run` extracts the UUID + builds the lambda factories, then delegates to `runInternal`.

**New test files:**
- `RagItemPipelineTest.java` (7 tests) — drives `runInternal` directly with pure-Java fakes:
  - `auth_denied_stops_before_fetch` — `AuthFn` returns `Authorizer.Denied(DISABLED, ...)`; asserts no fetch invoked, no provider constructed, `StatsAccumulator.total denied == 1`, `total requests == 0`.
  - `empty_mod_url_returns_provider_failure_without_fetch` — `Optional.<URL>empty()` surfaces PROVIDER failure; message contains `modId` and steers user to `/forgebook ask`.
  - `unsafe_url_exception_becomes_transport_failure` — `FetchFn` throws `UnsafeUrlException(PRIVATE_IP)`; assert generic user message "Could not fetch mod documentation. Try again later." (no block-reason leak per T-03-04-02).
  - `io_exception_becomes_transport_failure` — same shape as above but with `IOException`.
  - `provider_error_returns_mapped_error_to_user` — `ScriptedAiProvider` returns `AiTurn.ProviderError(TRANSPORT, ...)`; assert user message equals `AiDispatcher.mapError(...).humanReadable()` (symmetry with `/forgebook ask`).
  - `happy_path_final_reply_appends_source_citation` — reply ends with `"\n\nSource: https://create.fandom.com"` (CMD-07).
  - `happy_path_audit_log_success_fires_exactly_once` — `StatsAccumulator.render()` shows `total requests == 1`, `total denied == 0`, input/output token counts from the supplied `Usage`, per-player row keyed by caller UUID.

**Pipeline flow (runInternal):**
1. Null-snapshot defensive check — `sendFailure("ForgeBook not initialized ...")` and return.
2. `AuthFn.authorize(snap)` — if `Denied`, `RequestAuditLogger.logDenied(uuid, kind, code, startNanos)` + `sendFailure(denied.humanReadable())` + return.
3. Empty `modURL` — `RequestAuditLogger.logFailure(uuid, kind, PROVIDER, 0, 0, elapsedMs)` + `sendFailure("No documentation URL is registered for mod '...'. Try /forgebook ask ...")` + return.
4. `FetchFn.fetch(URI)` — on `UnsafeUrlException | IOException`, `logFailure(..., TRANSPORT, ...)` + generic failure message + return.
5. `ModDocsScraper.extractReadable(r.body(), url)` → `PromptFraming.wrap(readable, url)` — D-10 framed envelope.
6. Build `ChatRequest(snap.aiModel(), snap.maxTokens(), systemPrompt, [userMsg], List.of())` — Pattern 3 empty tools[].
7. `providerFactory.apply(snap).chat(req).join()` (with try/catch around `.join()` — provider exceptions become `logFailure(..., PROVIDER, ...)` + "AI provider returned an error.").
8. Instanceof chain on `AiTurn`:
   - `FinalReply` → tokens from `usage()` or `estimateTokens()`; `logSuccess(...)`; reply = `fr.text() + "\n\nSource: " + url`; `sendSuccess(reply)`.
   - `ProviderError` → `AiDispatcher.mapError(err)` → `logFailure(..., mapped.code(), ...)` + `sendFailure(mapped.humanReadable())`.
   - Defensive else (ToolUses returned with empty tools[] — sealed contract admits it) → `logFailure(..., PROVIDER, ...)` + "Unexpected provider response."

## Requirements Satisfied

| ID | Description | Evidence |
|----|-------------|----------|
| CMD-02 | `/forgebook item` RAG grounded in mod docs URL | `RagItemPipeline.run` — fetch via SafeHttpFetcher → scrape → frame → single provider call |
| CMD-07 | Every item answer includes the source URL | Happy-path branch appends `"\n\nSource: " + url`; `happy_path_final_reply_appends_source_citation` asserts exact literal |

## Tests

- 7 new tests in `RagItemPipelineTest` — all GREEN.
- Full `com.forgebook.ai.*` suite (12 test classes) remains GREEN — no regressions.

## Grep Invariants (Task Done Criteria)

Production file:
- `grep -c 'List\.of()' RagItemPipeline.java` → 1 (>=1 required; empty tools[] invariant)
- `grep -c 'Source: ' RagItemPipeline.java` → 4 (>=1 required; CMD-07 literal)
- `grep -c 'Authorizer.authorize' RagItemPipeline.java` → 1 (=1 required; single auth call site)
- `grep -c 'RequestAuditLogger\.' RagItemPipeline.java` → 7 (>=4 required; one per terminal path)
- `grep -c 'getDisplayURL' RagItemPipeline.java` → 0 (anti-pattern absent — IModInfo.getDisplayURL doesn't exist per CLAUDE.md)
- `grep -c 'AgentLoop' RagItemPipeline.java` → 0 (NO AgentLoop references)

Test file:
- `grep -c '@Test' RagItemPipelineTest.java` → 7 (>=7 required; seven-branch coverage)
- `grep -c 'Source: https://create.fandom.com' RagItemPipelineTest.java` → 1 (>=1 required; CMD-07 citation assertion)

## Deviations from Plan

**1. [Rule 2 — Better test pattern] Used runInternal primitive-seam harness instead of Mockito static/construction mocks.**
- **Found during:** Task 2 planning.
- **Issue:** The plan's `<action>` text sketched a harness using `MockedStatic<ConfigHolder>`, `MockedStatic<RateLimiterHolder>`, `MockedStatic<ProviderFactory>`, `MockedStatic<RequestAuditLogger>`, and `MockedConstruction<SafeHttpFetcher>` — plus `mock(ServerPlayer.class)` and `mock(CommandSourceStack.class)`. CLAUDE.md "avoid mocking Minecraft classes" directly prohibits the latter two, and Plan 03-03's Authorizer test already established a cleaner precedent: a package-private primitive overload that takes plain types.
- **Fix:** Extended the pipeline with a package-private `runInternal(Feedback, UUID, String modId, String itemId, Optional<URL> modURL, RequestKind, ConfigSnapshot, AuthFn, FetchFn, Function<ConfigSnapshot, AiProvider>)` that takes pure-Java seams. Tests construct pure-Java `RecordingFeedback`, lambda `AuthFn`/`FetchFn`, and use `ScriptedAiProvider` (already in the test classpath from Phase 1). No Mockito usage at all.
- **Files modified:** `RagItemPipeline.java` (added runInternal + 3 package-private interfaces), `RagItemPipelineTest.java` (all 7 tests drive runInternal).
- **Commits:** 070e171 (production), 2b3eb44 (tests).

**2. [Rule 2 — Better audit oracle] Used StatsAccumulator.render() as the audit oracle instead of MockedStatic<RequestAuditLogger>.**
- **Found during:** Task 2 assertion design.
- **Issue:** The plan's harness relied on `verify(auditStatic, times(1)).logSuccess(...)` via MockedStatic. StatsAccumulator (Plan 03-02) already exposes observable state via `render()` and `resetForTests()` — so the fan-out from `logSuccess/logFailure/logDenied` → `recordSuccess/recordFailure/recordDenied` → counter bumps IS externally observable. Using the real StatsAccumulator as oracle is stronger (exercises the full fan-out including counter semantics: denied ≠ initiated per SAFE-02).
- **Fix:** Tests call `StatsAccumulator.resetForTests()` in `@BeforeEach`, then assert on `render()` substrings (`"total requests : 1"`, `"total denied : 1"`, `"total in_tok : 123"`, `"total out_tok : 45"`, and caller UUID presence in per-player rows).
- **Files modified:** `RagItemPipelineTest.java` only.

**3. [Rule 1 — Bug] Plan example `new SafeHttpFetcher.Result(200, body)` doesn't match actual record shape.**
- **Found during:** Task 2 test authoring.
- **Issue:** The plan wrote `new SafeHttpFetcher.Result(200, "<html>docs</html>")` (status + body), but the actual record is `Result(String body, String contentType, URI finalUri)` — no status field.
- **Fix:** Used `new SafeHttpFetcher.Result(body, "text/html", uri)` in tests.
- **Files modified:** `RagItemPipelineTest.java` only.

**4. [Rule 1 — Bug] Plan example `new UnsafeUrlException("blocked")` doesn't compile.**
- **Found during:** First `./gradlew compileTestJava` run.
- **Issue:** `UnsafeUrlException` takes a `Reason` enum, not a String (`UnsafeUrlException(Reason)`).
- **Fix:** Used `new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP)`.
- **Files modified:** `RagItemPipelineTest.java` only.

**5. [Rule 1 — Bug] Plan example `new Usage(123, 45)` doesn't compile.**
- **Found during:** Initial Task 2 compile attempt.
- **Issue:** `Usage` is a plain class with public mutable fields and no explicit constructor, not a record.
- **Fix:** Added `usage(int, int)` helper in the test that creates a fresh `Usage()` and sets `inputTokens` / `outputTokens` fields directly.
- **Files modified:** `RagItemPipelineTest.java` only.

**6. [Rule 3 — Blocking] Effectively-final lambda capture error in runInternal.**
- **Found during:** First `./gradlew compileJava` run.
- **Issue:** The `FinalReply.usage().orElseGet(() -> estimateTokens(systemPrompt, userMsg))` lambda captures `systemPrompt` / `userMsg`, which started as local-mutable-ish. Java requires effectively-final capture.
- **Fix:** Materialized `final String cachedPrompt = SystemPromptCache.get();` and `final String systemPrompt = (cachedPrompt == null || cachedPrompt.isEmpty()) ? FALLBACK_SYSTEM_PROMPT : cachedPrompt;` before the ChatRequest is built, so both `systemPrompt` and `userMsg` are effectively final when the estimateTokens lambda captures them on the FinalReply branch.
- **Files modified:** `RagItemPipeline.java` only.
- **Commit:** rolled into 070e171 (production file).

## Self-Check

Files created:
- `src/main/java/com/forgebook/ai/RagItemPipeline.java` — FOUND
- `src/test/java/com/forgebook/ai/RagItemPipelineTest.java` — FOUND
- `.planning/phases/03-command-surface-safety-controls/03-04-SUMMARY.md` — FOUND (this file)

Commits in git log (worktree branch `worktree-agent-aae474f8`):
- `070e171` — `feat(03-04): implement RagItemPipeline RAG single-shot pipeline` — FOUND
- `2b3eb44` — `test(03-04): add RagItemPipelineTest covering 7 branches` — FOUND

## Self-Check: PASSED
