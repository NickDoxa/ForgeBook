---
phase: 02-ai-engine-grounding
plan: "06"
subsystem: agent-loop
tags: [agent-loop, system-prompt, volatile-cache, tdd, parallel-tools, anti-injection, java17]
dependency_graph:
  requires:
    - "02-02: AiProvider, AiTurn sealed, ChatRequest, ContentBlock, ClaudeMessage, ToolDef"
    - "02-03: AiExecutor.get() for parallel tool submission"
    - "02-04: CurseForgeClient.fetch, ModpackContextCache.set/get, ModpackContext"
    - "02-05: ToolRegistry.get/all/init/injectForTests, Tool, ToolException"
    - "02-01: ConfigHolder.get, ConfigSnapshot"
  provides:
    - "AgentLoop: bounded 6-iteration state machine (AI-05, D-11, D-12)"
    - "SystemPromptBuilder.build: pure function (mods + modpack + tools + D-10 rules)"
    - "SystemPromptBuilder.buildAndCache(MinecraftServer): Plan 07 entry point"
    - "SystemPromptBuilder.buildAndCacheInternal: package-private test seam"
    - "SystemPromptCache: volatile String holder (get/set)"
    - "ScriptedAiProvider: queue-driven AiProvider stub for AgentLoop tests (Plan 07 reuse)"
  affects:
    - "02-07: ForgeBookMod.ServerStartedEvent listener calls SystemPromptBuilder.buildAndCache(server)"
    - "02-07: AiDispatcher reads SystemPromptCache.get() per request"
    - "02-07: AgentLoopE2ETest reuses ScriptedAiProvider for SC-5 integration test"
tech_stack:
  added: []
  patterns:
    - "Java 17 pattern-matching instanceof chain (sealed AiTurn) in AgentLoop.run for-loop"
    - "CompletableFuture.supplyAsync on AiExecutor.get() for parallel tool execution (D-11)"
    - "Per-tool catch-all converting exceptions to is_error=true tool_result (D-12)"
    - "Package-private buildAndCacheInternal seam — matches SafeHttpFetcher Phase 1 pattern"
    - "ToolRegistry.injectForTests/resetForTests made public (same pattern as CircuitBreaker.consecutiveFailures)"
    - "TDD RED/GREEN per task with separate test and feat commits"
key_files:
  created:
    - src/main/java/com/forgebook/ai/AgentLoop.java
    - src/main/java/com/forgebook/ai/SystemPromptBuilder.java
    - src/main/java/com/forgebook/ai/SystemPromptCache.java
    - src/test/java/com/forgebook/ai/ScriptedAiProvider.java
    - src/test/java/com/forgebook/ai/AgentLoopTest.java
    - src/test/java/com/forgebook/ai/SystemPromptBuilderTest.java
    - src/test/java/com/forgebook/ai/SystemPromptCacheTest.java
  modified:
    - src/main/java/com/forgebook/tool/ToolRegistry.java (injectForTests/resetForTests made public)
decisions:
  - "ToolRegistry.get() throws IllegalArgumentException for unknown tools (Plan 05 design); AgentLoop.invokeTool wraps in try/catch and maps to UNKNOWN_TOOL error — no null-check needed"
  - "ToolRegistry.injectForTests/resetForTests made public (was package-private); same rationale as CircuitBreaker.consecutiveFailures() in Plan 03 — test class in different package"
  - "buildAndCacheInternal submits CurseForge fetch to AiExecutor.get() even in tests; AiExecutor.start() called in @BeforeAll to ensure executor is ready"
  - "AgentLoop uses Java 17 pattern-matching instanceof chain (not switch expression) — preview switch avoided per Plan 02 precedent"
  - "toolResultError encodes content as JSON string (contentObj.toString()) matching Anthropic tool_result wire shape"
metrics:
  duration_minutes: 9
  completed_date: "2026-04-16"
  tasks_completed: 2
  tasks_total: 2
  files_created: 7
  files_modified: 1
  commits: 4
---

# Phase 2 Plan 06: Agent Loop + System Prompt Summary

**One-liner:** `AgentLoop` 6-iteration state machine with parallel D-11 tool execution and D-12 error isolation, `SystemPromptBuilder` pure function + `buildAndCache` orchestration, and `SystemPromptCache` volatile holder — closes AI-05 and AI-08.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | AgentLoopTest + ScriptedAiProvider failing tests | 233e6aa | AgentLoopTest.java, ScriptedAiProvider.java |
| 1 (GREEN) | AgentLoop implementation | 62cf350 | AgentLoop.java, ToolRegistry.java |
| 2 (RED) | SystemPromptBuilderTest + SystemPromptCacheTest failing tests | ad023b9 | SystemPromptBuilderTest.java, SystemPromptCacheTest.java |
| 2 (GREEN) | SystemPromptBuilder + SystemPromptCache implementation | 0025a0e | SystemPromptBuilder.java, SystemPromptCache.java |

## What Was Built

### AgentLoop.java (new)

Bounded 6-iteration state machine over `AiTurn` sealed variants.

**Constants:** `public static final int MAX_ITERATIONS = 6`

**run(ChatRequest initialReq):**
- Maintains `List<ClaudeMessage> messages` as mutable working copy
- For each iteration: builds `ChatRequest` with `List.copyOf(messages)`, calls `provider.chat(req).join()`
- `FinalReply` → return immediately
- `ProviderError` → return immediately (pass-through — D-12 doesn't retry provider errors)
- `ToolUses` → `assistantMessage()` appended, `executeParallel()` called, `userMessageWithToolResults()` appended, continue
- After 6 iterations: returns `ProviderError(ITERATION_CAP, "exceeded 6 iterations", empty)`

**executeParallel (D-11):**
```java
List<CompletableFuture<JsonObject>> futures = uses.stream()
    .map(use -> CompletableFuture.supplyAsync(() -> invokeTool(use), AiExecutor.get()))
    .toList();
CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
return futures.stream().map(CompletableFuture::join).toList(); // order preserved
```

**invokeTool (D-12 error isolation):**
- `ToolRegistry.get(name)` throws `IllegalArgumentException` for unknown tools → caught → `toolResultError(id, "UNKNOWN_TOOL", ...)`
- `ToolException` → `toolResultError(id, te.reason().name(), te.getMessage())`
- Any other `Exception` → `toolResultError(id, "FETCH_FAILED", className + message)`

**Wire shape:** `toolResultSuccess` → `{ type, tool_use_id, content: String }` (no `is_error`); `toolResultError` → `{ type, tool_use_id, content: JSON-stringified `{error, detail}`, is_error: true }`

### SystemPromptCache.java (new)

```java
private static volatile String current = "";
```
Mirrors `ConfigHolder` pattern exactly. `set(null)` coerces to `""` (defensive). Thread-safety: volatile store/load provides happens-before between `set()` (server-main at startup) and `get()` (AiExecutor workers per request) — T-02-06-07.

### SystemPromptBuilder.java (new)

**build() — pure function:**
- Identity preamble: `"You are ForgeBook, an in-game assistant..."`
- `Installed mods:` section — every mod formatted as `- modId — displayName — url` (D-09: no truncation)
- `Modpack:` section — OMITTED when `Optional.empty()` (CF-02 graceful degradation)
- `Available tools:` section — `- name: description` per tool
- `ANTI_INJECTION_RULES` block LAST (D-10: after all third-party content)

**buildAndCacheInternal() — package-private seam:**
Orchestration order: `CurseForgeClient.fetch(snap)` via AiExecutor → `ModpackContextCache.set()` → `ToolRegistry.init()` → `build()` → `SystemPromptCache.set()`

**buildAndCache(MinecraftServer) — production entry:**
Called by Plan 07's `ServerStartedEvent` listener. Also to be called by `/forgebook reload`.

### ScriptedAiProvider.java (new, test scope)

Queue-driven `AiProvider` stub for offline AgentLoop testing. Plan 07 `AgentLoopE2ETest` (SC-5) reuses this directly.

| Accessor | Description |
|----------|-------------|
| `callCount()` | Number of `chat()` invocations |
| `requestAt(int index)` | `ChatRequest` on the Nth call (0-indexed) |
| `requestCount()` | `history.size()` |
| `lastRequest()` | Most recent `ChatRequest` or null |

Exhausted queue returns `ProviderError(Kind.PROVIDER, "scripted provider exhausted", empty)` — never throws.

## Test Results

| Suite | Tests | Result |
|-------|-------|--------|
| AgentLoopTest | 13 | PASS |
| SystemPromptCacheTest | 5 | PASS |
| SystemPromptBuilderTest | 9 | PASS |
| **New total** | **27** | **27 PASS** |
| Pre-existing SafeHttpFetcherTest | 5 | FAIL (pre-existing SSL env issue, out of scope) |
| **Full suite** | **190** | **185 PASS, 5 pre-existing FAIL** |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ToolRegistry.injectForTests/resetForTests were package-private**
- **Found during:** Task 1 GREEN — `AgentLoopTest` (in `com.forgebook.ai`) called `ToolRegistry.injectForTests` (in `com.forgebook.tool`); compiler rejected with "not public"
- **Fix:** Changed both methods from package-private to `public` in `ToolRegistry.java`
- **Rationale:** Same pattern applied in Plan 03 for `CircuitBreaker.consecutiveFailures()`. Both are documented as test-only; no production code calls them.
- **Files modified:** `src/main/java/com/forgebook/tool/ToolRegistry.java`
- **Commit:** 62cf350

**2. [Rule 3 - Blocking] IModInfo anonymous class missing 5 abstract methods in SystemPromptBuilderTest**
- **Found during:** Task 2 GREEN compile — `IModInfo` interface in Forge 47.x requires `getForgeFeatures()`, `getNamespace()`, `getLogoBlur()`, and `getConfig()` in addition to the 9 methods listed in the plan
- **Fix:** Added the 4 missing method stubs to the `fakeMod` inline implementation; changed `List<IModInfo.ModVersion>` → `List<? extends IModInfo.ModVersion>` for wildcard variance
- **Files modified:** `src/test/java/com/forgebook/ai/SystemPromptBuilderTest.java`
- **Commit:** 0025a0e

**3. [Rule 3 - Blocking] `List<TestTool>` not assignable to `Collection<Tool>` in test11**
- **Found during:** Task 2 GREEN compile — `List.of(new TestTool(...), ...)` has inferred type `List<TestTool>`, not `List<Tool>`, causing type incompatibility at `build()` call site
- **Fix:** Changed variable declaration from `var` to `Collection<Tool>` for explicit upcast
- **Files modified:** `src/test/java/com/forgebook/ai/SystemPromptBuilderTest.java`
- **Commit:** 0025a0e

## Threat Mitigations Applied

| Threat | Status |
|--------|--------|
| T-02-06-01: Tool output tampering | Mitigated — all tool outputs flow through `toolResultSuccess`/`toolResultError`; AgentLoop never re-parses tool output as instructions |
| T-02-06-02: DoS via unbounded iteration | Mitigated — `MAX_ITERATIONS = 6` hard cap; Test 10 asserts exactly 6 provider calls then ITERATION_CAP |
| T-02-06-03: DoS via parallel sub-task fanout | Mitigated — sub-tasks submitted to `AiExecutor.get()` (bounded ThreadPoolExecutor); `RejectedExecutionException` caught by per-tool `catch Exception` → structured error |
| T-02-06-04: API key in system prompt | Mitigated — `SystemPromptBuilder.build()` never calls `.raw()`; grep confirms 0 matches |
| T-02-06-05: Modpack summary prompt injection | Mitigated — `ANTI_INJECTION_RULES` block is ALWAYS last in prompt, re-asserts authority after modpack section |
| T-02-06-06: Repudiation | Accepted — per-iteration `LOG.warn("tool {} failed", ...)` provides audit trail |
| T-02-06-07: Torn reads from SystemPromptCache | Mitigated — `private static volatile String current` provides happens-before guarantee |

## Note for Plan 07

- **`SystemPromptBuilder.buildAndCache(server)`** is the entry point the new `ServerStartedEvent` listener calls. It orchestrates CF fetch → ToolRegistry.init → build → SystemPromptCache.set in sequence.
- **`ForgebookReloadCommand`** from Phase 1 must be extended to also call `SystemPromptBuilder.buildAndCache(server)` on reload (PATTERNS §"MODIFIED: ForgeBookMod.java" line 964).
- **`ScriptedAiProvider`** is in `src/test/java/com/forgebook/ai/` — the Plan 07 E2E SC-5 test imports it directly. Queue 4 turns: `ToolUses(fetch_mod_docs_page)` → `ToolUses(web_search)` → `FinalReply(cited URL)` to exercise the missing-docs fallback.
- **`SystemPromptCache.get()`** is what `AiDispatcher` reads at request entry. Returns `""` if `buildAndCache` hasn't run yet — AiDispatcher should detect empty and surface a startup-not-ready error.

## Known Stubs

None — all production files are fully implemented. `SystemPromptCache.get()` returns `""` before first `set()` call — this is the correct defensive default, not a stub.

## Threat Flags

None — no new network endpoints, auth paths, or file access patterns beyond the plan's threat model. All files are pure Java; `SystemPromptBuilder` has zero `.raw()` calls.

## Success Criteria Verification

1. AI-05 closed: 6-iteration cap enforced (Test 10), parallel D-11 (Test 7), error-tolerant D-12 (Test 8/9). **PASS**
2. AI-08 closed: prompt pre-rendered at startup (Test 13), reused via volatile cache (Tests 1-5), graceful CurseForge degradation (Test 14). **PASS**
3. All 27 new tests pass. **PASS**
4. ScriptedAiProvider available for Plan 07 SC-5 test with `requestAt(int)` and `lastRequest()`. **PASS**
5. Zero `import net.minecraft.*` in AgentLoop.java. **PASS**
6. `MAX_ITERATIONS = 6` appears exactly once in AgentLoop.java. **PASS**

## Self-Check: PASSED

| Item | Status |
|------|--------|
| src/main/java/com/forgebook/ai/AgentLoop.java | FOUND |
| src/main/java/com/forgebook/ai/SystemPromptBuilder.java | FOUND |
| src/main/java/com/forgebook/ai/SystemPromptCache.java | FOUND |
| src/test/java/com/forgebook/ai/ScriptedAiProvider.java | FOUND |
| src/test/java/com/forgebook/ai/AgentLoopTest.java | FOUND |
| src/test/java/com/forgebook/ai/SystemPromptBuilderTest.java | FOUND |
| src/test/java/com/forgebook/ai/SystemPromptCacheTest.java | FOUND |
| Commit 233e6aa (test RED Task 1) | FOUND |
| Commit 62cf350 (feat GREEN Task 1) | FOUND |
| Commit ad023b9 (test RED Task 2) | FOUND |
| Commit 0025a0e (feat GREEN Task 2) | FOUND |
| compileJava succeeds | PASS |
| 190 tests run, 185 pass (5 pre-existing SSL failures) | PASS |
| No new test regressions | PASS |
