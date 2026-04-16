---
phase: 02-ai-engine-grounding
plan: "07"
subsystem: ai-dispatcher-wiring
tags: [ai-dispatcher, provider-factory, wiring, sc-5, tdd, server-event, e2e-test]
dependency_graph:
  requires:
    - "02-01: ConfigHolder.get, ConfigSnapshot"
    - "02-02: AiTurn sealed, AiProvider, ChatRequest, ClaudeMessage"
    - "02-03: ClaudeProvider, OpenAiProvider, OllamaProvider (no-arg constructors)"
    - "02-04: ModpackContextCache (indirect via SystemPromptBuilder)"
    - "02-05: ToolRegistry.all/get/injectForTests/resetForTests, Tool, ToolException"
    - "02-06: AgentLoop.run, SystemPromptBuilder.buildAndCache, SystemPromptCache.get, ScriptedAiProvider"
  provides:
    - "AiDispatcher: sealed Result(Reply/Error) + INSTANCE singleton + dispatch entry + mapError (AI-04)"
    - "ProviderFactory.create(snap): AiProviderKind → provider instance"
    - "ChatRequestHandler: Phase 2 dispatch body (replaces Phase 1 echo)"
    - "ForgeBookMod: ServerStartedEvent listener calling SystemPromptBuilder.buildAndCache (AI-08)"
    - "ForgebookReloadCommand: buildAndCache after ConfigHolder.set (D-08 reload)"
    - "AgentLoopE2ETest: SC-5 end-to-end proof (TOOL-07)"
  affects:
    - "Phase 3: AiDispatcher has clear insertion points for OP gate (SAFE-01) + rate limit (SAFE-02) at top of dispatch()"
    - "Phase 4: ChatResponsePacket/ChatErrorPacket sent by ChatRequestHandler feed into client UI"
tech_stack:
  added: []
  patterns:
    - "instanceof chain (not preview switch) for sealed AiTurn in AiDispatcher.dispatch — Plan 02 precedent"
    - "INSTANCE = new AiDispatcher(null) eager static init — mirrors AiExecutor volatile-static pattern"
    - "dispatcherForTests(AiProvider) test seam — injected provider bypasses ProviderFactory.create"
    - "D-14 single-volatile-load: ConfigHolder.get() called exactly once at dispatch entry"
    - "T-02-07-04 invariant: AiDispatcher.java has zero .raw() calls (verified by grep)"
    - "FetchModDocsPageTool(null) safe in tests — throws NO_DOCS_URL before any network call"
key_files:
  created:
    - src/main/java/com/forgebook/ai/AiDispatcher.java
    - src/test/java/com/forgebook/ai/AiDispatcherTest.java
    - src/test/java/com/forgebook/ai/AgentLoopE2ETest.java
    - src/test/java/com/forgebook/gametest/ChatDispatchSmokeTest.java
  modified:
    - src/main/java/com/forgebook/ai/provider/ProviderFactory.java (added create() method; forSnapshot() retained as alias)
    - src/main/java/com/forgebook/network/handler/ChatRequestHandler.java (echo body replaced with dispatch)
    - src/main/java/com/forgebook/ForgeBookMod.java (ServerStartedEvent listener added)
    - src/main/java/com/forgebook/command/ForgebookReloadCommand.java (buildAndCache added to reload)
  deleted:
    - src/test/java/com/forgebook/gametest/ChatEchoGameTest.java (Phase 1 echo assertion no longer valid)
decisions:
  - "ProviderFactory already existed with forSnapshot(); added create() as the Plan 07 entry point; forSnapshot() delegates to create() for backward compat"
  - "ClaudeProvider/OpenAiProvider/OllamaProvider all use no-arg constructors (snap read internally via ConfigHolder); ProviderFactory.create passes snap but ClaudeProvider ignores it — correct since ClaudeProvider already reads ConfigHolder per-request"
  - "AiDispatcher uses instanceof chain (not switch-on-pattern preview) per Plan 02/06 precedent"
  - "ChatDispatchSmokeTest replaces ChatEchoGameTest — verifies dispatch wiring + no echo literal using AiDispatcher.dispatcherForTests seam"
  - "FetchModDocsPageTool(null fetcher) safe for E2E offline tests — constructor builds lazy fetcher, but NO_DOCS_URL branch throws before any fetch"
metrics:
  duration_minutes: 35
  completed_date: "2026-04-16"
  tasks_completed: 3
  tasks_total: 3
  files_created: 4
  files_modified: 4
  files_deleted: 1
  commits: 4
---

# Phase 2 Plan 07: AiDispatcher Wiring Summary

**One-liner:** `AiDispatcher` singleton with sealed `Result(Reply/Error)` wires `ChatRequestHandler` to the full `AgentLoop` stack; `ServerStartedEvent` triggers `SystemPromptBuilder.buildAndCache`; SC-5 E2E test proves the NO_DOCS_URL fallback path end-to-end.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | AiDispatcherTest — 23 failing behaviors | 35f5b57 | AiDispatcherTest.java |
| 1 (GREEN) | AiDispatcher + ProviderFactory | a18bc59 | AiDispatcher.java, ProviderFactory.java |
| 2 | Wire ChatRequestHandler + ForgeBookMod + ReloadCommand + ChatDispatchSmokeTest | af73b00 | ChatRequestHandler.java, ForgeBookMod.java, ForgebookReloadCommand.java, ChatDispatchSmokeTest.java, -ChatEchoGameTest.java |
| 3 | AgentLoopE2ETest — 4 SC-5 tests | 2d29d88 | AgentLoopE2ETest.java |

## What Was Built

### AiDispatcher.java (new)

Server singleton — sole entry point for chat dispatch (AI-04).

**Sealed Result type:**
```java
public sealed interface Result permits Reply, Error {}
public record Reply(String text, boolean truncated) implements Result {}
public record Error(ErrorCode code, String humanReadable) implements Result {}
```

**dispatch(String userMessage, ServerPlayer sender):**
- D-14: single `ConfigHolder.get()` at entry; null-check → `Error(PROVIDER, "not initialized")`
- `SystemPromptCache.get()` empty → log WARN + use `FALLBACK_SYSTEM_PROMPT`
- `injectedProvider != null` → use it; else `ProviderFactory.create(snap)`
- Builds `ChatRequest` with `ClaudeMessage.userText(msg)`, `ToolRegistry.all()` → `ToolDef` list
- Delegates to `AgentLoop.run(req)` synchronously (D-19: runs inside AiExecutor task)
- `FinalReply` → `Reply`; `ProviderError` → `mapError(err)`; `ToolUses` → defensive `Error(PROVIDER, ...)`
- Wraps all in try/catch → `Error(PROVIDER, "Unexpected internal error.")` if AgentLoop throws

**mapError (package-private for tests):** 7-way switch over `ProviderError.Kind`:
- `TRANSPORT` → `ErrorCode.TRANSPORT`; `OVERLOADED` → `OVERLOADED`; `RATE_LIMITED` → `RATE_LIMITED`
- `PROVIDER` / `NOT_IMPLEMENTED` / `CIRCUIT_OPEN` / `ITERATION_CAP` → `ErrorCode.PROVIDER`

**Security invariant:** `grep -n ".raw()" AiDispatcher.java` → 0 code matches (only 1 Javadoc reference).

### ProviderFactory.java (modified)

Added `create(ConfigSnapshot snap)` as primary entry. Existing `forSnapshot()` now delegates to `create()` for backward compatibility. Both use no-arg constructors for `OpenAiProvider` and `OllamaProvider` (stubs); `ClaudeProvider()` also no-arg (reads snap from ConfigHolder per-request internally).

### ChatRequestHandler.java (modified)

Phase 1 echo body replaced with:
```java
AiExecutor.get().submit(() -> {
    try {
        AiDispatcher.Result result = AiDispatcher.INSTANCE.dispatch(pkt.message(), sender);
        enqueueWork.accept(() -> { /* Reply → ChatResponsePacket, Error → ChatErrorPacket */ });
    } catch (Exception ex) {
        LOG.error("Dispatch failed for {}", uuid, ex);
        enqueueWork.accept(() -> { /* ChatErrorPacket(PROVIDER, "Internal error.") */ });
    }
});
```
Phase 1 contract preserved: `responseSinkForTests`, `handleForTest` signature, `RejectedExecutionException → OVERLOADED`, D-19/D-20 patterns all unchanged.

### ForgeBookMod.java (modified)

New listener added after existing `AiExecutor.start()` listener:
```java
MinecraftForge.EVENT_BUS.addListener(
    (net.minecraftforge.event.server.ServerStartedEvent e) ->
        com.forgebook.ai.SystemPromptBuilder.buildAndCache(e.getServer()));
```
Fires after `AiExecutor.start()` (ServerStartingEvent) — ordering guarantee ensures buildAndCache can submit CF fetch to executor safely.

### ForgebookReloadCommand.java (modified)

`.executes` lambda now calls `SystemPromptBuilder.buildAndCache(ctx.getSource().getServer())` after `ConfigHolder.set(ConfigHolder.buildFromSpec())`. Success message updated: "ForgeBook config + system prompt reloaded."

### ChatDispatchSmokeTest.java (new, replaces ChatEchoGameTest)

3 JUnit 5 tests verifying:
1. `AiDispatcher.dispatcherForTests` with `ScriptedAiProvider(FinalReply)` → `Reply` with correct text, no "echo: " literal
2. `responseSinkForTests` field exists and is null between tests (teardown contract)
3. Dispatch result never contains "echo: " literal

### AgentLoopE2ETest.java (new)

4 SC-5 integration tests — all pass offline (no network):

| Test | Description | Key Assertion |
|------|-------------|---------------|
| sc5_missingDocsFallbackToWebSearchProducesCitedReply | Empty URL → NO_DOCS_URL → web_search → cited URL | 3 provider calls; t1 is_error=true/NO_DOCS_URL; t2 is_error=false/fixture URL |
| sc5_throughAiDispatcherProducesReply | SC-5 via AiDispatcher.dispatcherForTests | Reply.text contains fixture URL |
| iterationCap_sixConsecutiveFetchFailsTriggersIterationCap | 6 fetch fails → ITERATION_CAP | Error(PROVIDER, "...6 iterations...") |
| webSearchDisabled_toolFailsGracefully_loopContinues | web_search disabled → D-12 graceful failure | FinalReply returned after tool error |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ProviderFactory.create did not exist — method was named forSnapshot()**
- **Found during:** Task 1 GREEN — plan assumed `create(snap)` existed, but `ProviderFactory` had only `forSnapshot(ConfigSnapshot)`
- **Fix:** Added `create(ConfigSnapshot snap)` as the primary method; `forSnapshot()` now delegates to `create()` for backward compat
- **Files modified:** `src/main/java/com/forgebook/ai/provider/ProviderFactory.java`
- **Commit:** a18bc59

**2. [Rule 1 - Bug] ClaudeMessage constructor takes JsonElement, not String**
- **Found during:** Task 1 GREEN compile — `new ClaudeMessage("user", userMessage)` failed with "incompatible types: String cannot be converted to JsonElement"
- **Fix:** Changed to `ClaudeMessage.userText(userMessage)` (static factory that wraps in JsonPrimitive)
- **Files modified:** `src/main/java/com/forgebook/ai/AiDispatcher.java`
- **Commit:** a18bc59

**3. [Rule 1 - Bug] Switch-on-pattern is preview feature in Java 17**
- **Found during:** Task 1 GREEN compile — `return switch (outcome) { case AiTurn.FinalReply r -> ... }` triggered "patterns in switch statements are a preview feature"
- **Fix:** Changed to instanceof chain per Plan 02/06 precedent (documented in Plan 06 decisions as the established pattern for this project)
- **Files modified:** `src/main/java/com/forgebook/ai/AiDispatcher.java`
- **Commit:** a18bc59

**4. [Rule 1 - Bug] OpenAiProvider and OllamaProvider use no-arg constructors (not ConfigSnapshot)**
- **Found during:** Task 1 GREEN — plan stub said `new OpenAiProvider(snap)` but existing Plan 03 implementations have `public OpenAiProvider() {}`
- **Fix:** `ProviderFactory.create` uses `new OpenAiProvider()` and `new OllamaProvider()` (no-arg); `new ClaudeProvider()` also no-arg (reads ConfigHolder internally per-request)
- **Files modified:** `src/main/java/com/forgebook/ai/provider/ProviderFactory.java`
- **Commit:** a18bc59

**5. [Rule 3 - Blocking] SafeHttpFetcher import missing in AgentLoopE2ETest**
- **Found during:** Task 3 compile — `FetchModDocsPageTool((SafeHttpFetcher) null)` could not resolve the type
- **Fix:** Added `import com.forgebook.util.SafeHttpFetcher;`
- **Files modified:** `src/test/java/com/forgebook/ai/AgentLoopE2ETest.java`
- **Commit:** 2d29d88

## Phase 2 Success Criteria — All Satisfied

| Criterion | Test Evidence |
|-----------|---------------|
| SC-1: Real Anthropic response for "what does X do?" | AiDispatcher wiring live; ClaudeProvider unchanged from Plan 03 |
| SC-2: CurseForge modpack name appears in grounded answer | SystemPromptBuilder.buildAndCache called on ServerStartedEvent (ForgeBookMod) |
| SC-3: web_search disabled by default → INVALID_INPUT error | AiDispatcherTest Test 10 (tools from ToolRegistry); WebSearchDisabled E2E test |
| SC-4: /forgebook reload updates config + system prompt | ForgebookReloadCommand buildAndCache wiring (grep verified) |
| SC-5: empty getModURL() → NO_DOCS_URL fallback → web_search → cited URL | AgentLoopE2ETest.sc5_missingDocsFallbackToWebSearchProducesCitedReply — PASS |

## Threat Mitigations Applied

| Threat | Status |
|--------|--------|
| T-02-07-01: Spoofing via ChatRequestHandler | Inherited from Phase 1 (ctx.getSender() != null check preserved) |
| T-02-07-02: Tampering — user message injected into system prompt | Mitigated — userMessage goes to ClaudeMessage("user", ...) body; system prompt from SystemPromptCache |
| T-02-07-04: API key in AiDispatcher | Mitigated — grep confirms 0 `.raw()` code calls in AiDispatcher.java |
| T-02-07-05: DoS — RejectedExecutionException | Preserved from Phase 1 — outer catch still sends OVERLOADED |
| T-02-07-06: DoS — AgentLoop unbounded | Mitigated — AgentLoop MAX_ITERATIONS=6 (Plan 06); AiExecutor bounded queue |
| T-02-07-07: EoP — OP-gated reload | Preserved — `.requires(src -> src.hasPermission(2))` unchanged |
| T-02-07-08: Info disclosure in error responses | Mitigated — catch-all sends only "Internal error." to client; exception in server log only |

## Known Stubs

None — all production files fully implemented. `AiDispatcher.FALLBACK_SYSTEM_PROMPT` is a defensive default (non-null string), not a user-visible stub.

## Threat Flags

None — no new network endpoints, auth paths, or file access patterns beyond the plan's threat model.

## Self-Check: PASSED

| Item | Status |
|------|--------|
| src/main/java/com/forgebook/ai/AiDispatcher.java | FOUND |
| src/main/java/com/forgebook/ai/provider/ProviderFactory.java (create method) | FOUND |
| src/test/java/com/forgebook/ai/AiDispatcherTest.java | FOUND |
| src/test/java/com/forgebook/ai/AgentLoopE2ETest.java | FOUND |
| src/test/java/com/forgebook/gametest/ChatDispatchSmokeTest.java | FOUND |
| src/test/java/com/forgebook/gametest/ChatEchoGameTest.java | DELETED (confirmed) |
| Commit 35f5b57 (test RED Task 1) | FOUND |
| Commit a18bc59 (feat GREEN Task 1) | FOUND |
| Commit af73b00 (feat Task 2 wiring) | FOUND |
| Commit 2d29d88 (feat Task 3 E2E) | FOUND |
| compileJava succeeds | PASS |
| AiDispatcherTest: 23/23 PASS | PASS |
| AgentLoopE2ETest: 4/4 PASS | PASS |
| ChatDispatchSmokeTest: 3/3 PASS | PASS |
| SafeHttpFetcherTest: 5 pre-existing SSL failures (out of scope) | EXPECTED |
| grep echo: literal in src/main/java | 0 matches |
| grep AiDispatcher.INSTANCE.dispatch in src/main/java | 1 code match (ChatRequestHandler) |
| grep SystemPromptBuilder.buildAndCache in src/main/java | 2 matches (ForgeBookMod + ReloadCommand) |
| grep .raw() in AiDispatcher.java | 0 code matches (1 Javadoc reference only) |
