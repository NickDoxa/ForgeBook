---
phase: 02-ai-engine-grounding
reviewed: 2026-04-16T00:00:00Z
depth: standard
files_reviewed: 70
files_reviewed_list:
  - src/main/java/com/forgebook/ForgeBookMod.java
  - src/main/java/com/forgebook/ai/AgentLoop.java
  - src/main/java/com/forgebook/ai/AiDispatcher.java
  - src/main/java/com/forgebook/ai/AiTurn.java
  - src/main/java/com/forgebook/ai/ChatRequest.java
  - src/main/java/com/forgebook/ai/ScriptedAiProvider.java
  - src/main/java/com/forgebook/ai/SystemPromptBuilder.java
  - src/main/java/com/forgebook/ai/SystemPromptCache.java
  - src/main/java/com/forgebook/ai/dto/ClaudeMessage.java
  - src/main/java/com/forgebook/ai/dto/ToolDef.java
  - src/main/java/com/forgebook/ai/provider/AIProvider.java
  - src/main/java/com/forgebook/ai/provider/ClaudeProvider.java
  - src/main/java/com/forgebook/ai/provider/HttpExecutor.java
  - src/main/java/com/forgebook/ai/provider/ProviderFactory.java
  - src/main/java/com/forgebook/command/ForgebookReloadCommand.java
  - src/main/java/com/forgebook/config/AiProviderKind.java
  - src/main/java/com/forgebook/config/ApiKey.java
  - src/main/java/com/forgebook/config/ConfigHolder.java
  - src/main/java/com/forgebook/config/ConfigSnapshot.java
  - src/main/java/com/forgebook/config/ForgebookClientConfig.java
  - src/main/java/com/forgebook/config/ForgebookServerConfig.java
  - src/main/java/com/forgebook/config/RateLimiter.java
  - src/main/java/com/forgebook/config/WebSearchProviderKind.java
  - src/main/java/com/forgebook/integration/CurseForgeClient.java
  - src/main/java/com/forgebook/integration/ModpackContextCache.java
  - src/main/java/com/forgebook/integration/scraper/PromptFraming.java
  - src/main/java/com/forgebook/integration/websearch/BraveSearchAdapter.java
  - src/main/java/com/forgebook/integration/websearch/DuckDuckGoHtmlAdapter.java
  - src/main/java/com/forgebook/integration/websearch/SearchResult.java
  - src/main/java/com/forgebook/network/handler/ChatRequestHandler.java
  - src/main/java/com/forgebook/network/packet/ChatErrorPacket.java
  - src/main/java/com/forgebook/network/packet/ChatRequestPacket.java
  - src/main/java/com/forgebook/network/packet/ChatResponsePacket.java
  - src/main/java/com/forgebook/tool/Tool.java
  - src/main/java/com/forgebook/tool/ToolException.java
  - src/main/java/com/forgebook/tool/ToolRegistry.java
  - src/main/java/com/forgebook/tool/impl/FetchModDocsPageTool.java
  - src/main/java/com/forgebook/tool/impl/GetModpackContextTool.java
  - src/main/java/com/forgebook/tool/impl/ListInstalledModsTool.java
  - src/main/java/com/forgebook/tool/impl/WebSearchTool.java
  - src/main/java/com/forgebook/util/AiExecutor.java
  - src/main/java/com/forgebook/util/CircuitBreaker.java
  - src/main/java/com/forgebook/util/RetryPolicy.java
  - src/main/java/com/forgebook/util/SafeHttpFetcher.java
  - src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java
  - src/test/java/com/forgebook/ai/AgentLoopE2ETest.java
  - src/test/java/com/forgebook/ai/AgentLoopTest.java
  - src/test/java/com/forgebook/ai/AiDispatcherTest.java
  - src/test/java/com/forgebook/ai/ScriptedAiProviderTest.java
  - src/test/java/com/forgebook/ai/SystemPromptBuilderTest.java
  - src/test/java/com/forgebook/ai/provider/ClaudeProviderTest.java
  - src/test/java/com/forgebook/ai/provider/ProviderFactoryTest.java
  - src/test/java/com/forgebook/config/ConfigHolderTest.java
  - src/test/java/com/forgebook/config/RateLimiterTest.java
  - src/test/java/com/forgebook/gametest/ChatDispatchSmokeTest.java
  - src/test/java/com/forgebook/integration/CurseForgeClientTest.java
  - src/test/java/com/forgebook/integration/ModpackContextCacheTest.java
  - src/test/java/com/forgebook/integration/scraper/PromptFramingTest.java
  - src/test/java/com/forgebook/integration/websearch/BraveSearchAdapterTest.java
  - src/test/java/com/forgebook/integration/websearch/DuckDuckGoHtmlAdapterTest.java
  - src/test/java/com/forgebook/tool/ToolRegistryTest.java
  - src/test/java/com/forgebook/tool/impl/FetchModDocsPageToolTest.java
  - src/test/java/com/forgebook/tool/impl/GetModpackContextToolTest.java
  - src/test/java/com/forgebook/tool/impl/ListInstalledModsToolTest.java
  - src/test/java/com/forgebook/tool/impl/WebSearchToolTest.java
  - src/test/java/com/forgebook/util/CircuitBreakerTest.java
  - src/test/java/com/forgebook/util/RetryPolicyTest.java
  - src/test/java/com/forgebook/util/SafeHttpFetcherTest.java
  - src/test/java/com/forgebook/util/log/ApiKeyScrubFilterTest.java
findings:
  critical: 0
  warning: 5
  info: 17
  total: 22
status: issues_found
---

# Phase 02: Code Review Report

**Reviewed:** 2026-04-16
**Depth:** standard
**Files Reviewed:** 70
**Status:** issues_found (no Critical)

## Summary

Phase 02 (AI engine grounding) implements the full AI orchestration stack: volatile-snapshot ConfigHolder/SystemPromptCache singletons, AiDispatcher + AgentLoop with bounded iteration and parallel tool execution, ClaudeProvider with circuit-breaker + retry + jittered backoff, a tool registry with four concrete tools (fetch_mod_docs_page, web_search, list_installed_mods, get_modpack_context), SafeHttpFetcher SSRF guard with per-domain allowlist, CurseForge + Brave + DuckDuckGo HTML adapters, and the ChatRequestHandler wiring (D-19 executor-hop + enqueueWork, D-20 rejection-to-OVERLOADED).

The code is well-structured and heavily cross-referenced against the phase's decision ledger (D-07..D-28) and invariants (AI-01..AI-08, CFG-01..CFG-07, CF-01..CF-02). Defensive patterns are consistently applied: nonce-framed untrusted HTML (`<mod_doc trust="untrusted" ...>`), log4j2 API-key scrubbing, OP-only reload command, sealed-interface exhaustive matching, input validation at tool boundaries.

**No Critical security or crash-risk issues were found.** Secrets never cross the client boundary; logs are scrubbed; SSRF allowlist is enforced; anti-injection framing is correct; rate-limits are enforced per-player server-side. All findings below are Warning- or Info-level — they speak to dead code, blocking startup, resource reuse, test hygiene, and comment drift.

## Warnings

### WR-01: Unreachable code at end of ClaudeProvider retry loop

**File:** `src/main/java/com/forgebook/ai/provider/ClaudeProvider.java:135-136`
**Issue:** The `runWithRetry` for-loop (`for (int attempt = 0; attempt <= retry.maxAttempts(); attempt++)`) returns on every terminal path inside the body — success path returns response; non-retryable error returns the mapped error; the last-attempt branch records breaker failure and returns. The statements after the loop (`breaker.recordFailure(); return lastError;`) are only reachable when `retry.maxAttempts() < 0`, which is impossible by contract. Dead code duplicates the breaker-failure record and hides intent.
**Fix:**
```java
// after the for-loop:
throw new IllegalStateException("unreachable: retry loop must return on every iteration");
```
Or restructure the loop so the terminal return sits naturally at the end. Remove the duplicate `breaker.recordFailure();` to prevent double-counting if the loop shape changes later.

### WR-02: 20-second blocking `get()` gates server startup on CurseForge fetch

**File:** `src/main/java/com/forgebook/ai/SystemPromptBuilder.java` (method `buildAndCacheInternal`)
**Issue:** `ServerStartedEvent` -> `SystemPromptBuilder.buildAndCache(server)` performs a blocking `future.get(20, TimeUnit.SECONDS)` on a CurseForge HTTP fetch. If CurseForge is slow or unreachable, server startup stalls up to 20 seconds per reload. CF-01/CF-02 say the fetch is best-effort, but the 20-second wall still sits on the startup path.
**Fix:** Submit the fetch to `AiExecutor` without blocking. Seed `SystemPromptCache` immediately with a prompt that has no modpack context; when the async fetch completes, rebuild the prompt and publish the enriched version (swap-on-success). Startup is no longer gated by CurseForge latency, and `/forgebook reload` semantics already handle best-effort refresh.
```java
// Sketch:
SystemPromptCache.set(buildWithoutCurseForge(server));
AiExecutor.get().submit(() -> {
    fetchModpackContext(snap)
        .thenAccept(ctx -> SystemPromptCache.set(buildWithContext(server, ctx)))
        .exceptionally(ex -> { LOG.warn("CF fetch failed; keeping base prompt", ex); return null; });
});
```

### WR-03: `HttpClient.newHttpClient()` instantiated per outbound call

**File:** `src/main/java/com/forgebook/integration/CurseForgeClient.java:72`, `src/main/java/com/forgebook/integration/websearch/BraveSearchAdapter.java:50`
**Issue:** Each call allocates a fresh `HttpClient`. `HttpClient` is thread-safe and designed to be reused; discarding it on every call forfeits connection pooling, wastes TLS handshakes, and scales poorly under tool-heavy agent loops. Not a correctness bug — a reliability/efficiency smell.
**Fix:** Hold one shared `HttpClient` per class with an explicit `connectTimeout`:
```java
private static final HttpClient CLIENT = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();
```
Reuse across invocations. The existing `HttpExecutor` test seam in `ClaudeProvider` is already the right pattern to generalize here.

### WR-04: Placeholder assertion gives false confidence in dispatch smoke test

**File:** `src/test/java/com/forgebook/gametest/ChatDispatchSmokeTest.java:131-147`
**Issue:** `dispatchWiring_scriptedFinalReply_producesResponsePacketWithNoEchoLiteral` installs `responseSinkForTests`, immediately nulls it without ever routing a request through `handleForTest`, then ends with `assertTrue(true, "responseSinkForTests field used in test setup/teardown")`. The final assertion is tautological — the sink path is never exercised in this test. The inline comment acknowledges the limitation (`AiDispatcher.INSTANCE` is a `final static` field so it cannot be swapped from the test), but the test's name promises coverage it does not deliver.
**Fix:** Add a test seam to `AiDispatcher` (e.g., a `ThreadLocal<AiDispatcher>` override checked by `handleForTest` before falling through to `INSTANCE`), then drive a real `ChatRequestPacket` through `handleForTest(pkt, sender, Runnable::run, captured::set)` and assert the captured packet is `ChatResponsePacket` with `text == "handler-dispatch-reply"`. Alternatively, delete the placeholder assertion and rename the test to reflect what it actually proves (structural: dispatcher returns Reply; no echo literal in direct-dispatch output).

### WR-05: Raw `Map.class` deserialization drops generic type safety

**File:** `src/main/java/com/forgebook/ai/provider/ClaudeProvider.java` (tool-use `input` parsing)
**Issue:** `gson.fromJson(b.input, Map.class)` yields a raw `Map` (unchecked warning); downstream callers treat it as `Map<String, Object>`. A malformed or hostile response whose JSON object has non-string keys (shouldn't happen per JSON spec, but keys can be edge-case-decoded) could trigger a later `ClassCastException` at the use site rather than at the parse boundary.
**Fix:** Use a typed `TypeToken`:
```java
Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
Map<String, Object> input = gson.fromJson(b.input, mapType);
```
Or, better, deserialize into the tool's declared schema DTO if one exists.

## Info

### IN-01: Unused import `com.google.gson.JsonPrimitive`

**File:** `src/main/java/com/forgebook/ai/AgentLoop.java:12`
**Issue:** `JsonPrimitive` is imported but never referenced.
**Fix:** Remove the import.

### IN-02: Unused import `com.google.gson.JsonPrimitive`

**File:** `src/main/java/com/forgebook/ai/AiDispatcher.java:5`
**Issue:** `JsonPrimitive` is imported but never referenced.
**Fix:** Remove the import.

### IN-03: Possibly-unused import `java.util.function.Consumer`

**File:** `src/test/java/com/forgebook/gametest/ChatDispatchSmokeTest.java:26`
**Issue:** `Consumer` is imported; the only usage flows through lambda inference to `responseSinkForTests`. Verify via IDE. If unused after WR-04 fix, remove.
**Fix:** Remove if confirmed unused.

### IN-04: Redundant local alias in WebSearchTool.selectAdapter

**File:** `src/main/java/com/forgebook/tool/impl/WebSearchTool.java:152`
**Issue:** `BiFunction<String, Integer, List<SearchResult>> parser = braveParseBodyOverride;` assigns a field to a local of the same type, used once. Noise.
**Fix:** Inline `braveParseBodyOverride` at the call-site or delete the alias.

### IN-05: `@Deprecated` ProviderFactory alias retained without removal target

**File:** `src/main/java/com/forgebook/ai/provider/ProviderFactory.java`
**Issue:** `forSnapshot(snap)` is a deprecated alias retained for Plan 03 callers. Transitional shim without a documented removal milestone.
**Fix:** Add `@deprecated` Javadoc tag naming the removal target (e.g., "Remove after Phase 03-04") or remove the alias now if Plan 03 has migrated.

### IN-06: Fallback `new SafeHttpFetcher()` construction inside `invoke`

**File:** `src/main/java/com/forgebook/tool/impl/FetchModDocsPageTool.java:49`
**Issue:** When `fetcher == null` (the test-only constructor path used by `AgentLoopE2ETest` passing `(SafeHttpFetcher) null`), a fresh `SafeHttpFetcher` is instantiated inside `invoke`. Works but obscures intent — readers must check whether this path is ever used in production.
**Fix:** Move fallback construction to the constructor so `invoke` reads cleanly and there is exactly one fetcher per tool instance:
```java
public FetchModDocsPageTool(SafeHttpFetcher fetcher) {
    this.fetcher = fetcher != null ? fetcher : new SafeHttpFetcher();
}
```

### IN-07: Non-standard closing XML tag is intentional — elevate to class Javadoc

**File:** `src/main/java/com/forgebook/integration/scraper/PromptFraming.java`
**Issue:** The closing tag `</mod_doc tag="NONCE">` includes attributes — invalid XML/HTML but deliberately so: the nonce-scoped close tag defeats content-injection that forges a bare `</mod_doc>`. Intent is in a code comment; elevate to class-level Javadoc for future maintainers.
**Fix:** Add to the class Javadoc:
```
 * Closing tags include the nonce attribute (`</mod_doc tag="NONCE">`).
 * This is NOT standard XML/HTML — it's a deliberate injection defense: any forged
 * `</mod_doc>` in scraped content cannot match the nonce-scoped close tag.
```

### IN-08: Config drift — scoped test file renamed

**File:** Config block `files:` list references `src/test/java/com/forgebook/gametest/ChatEchoGameTest.java`
**Issue:** The file was renamed to `ChatDispatchSmokeTest.java` during Phase 02-07 wave (see commits `af73b00`, `2d29d88`). The review scope config still carries the old path.
**Fix:** Update the orchestrator's scope list (Plan/Tracking/ROADMAP references) to point at `ChatDispatchSmokeTest.java`. No source change required.

### IN-09: `AiDispatcher.dispatch` null-sender contract not documented

**File:** `src/main/java/com/forgebook/ai/AiDispatcher.java`
**Issue:** `dispatch(String message, ServerPlayer sender)` accepts a nullable `sender` (correctly — `ChatRequestHandler` guards before calling; tests pass `null`). The class Javadoc does not explicitly call out the null-accepting contract.
**Fix:** Add to `dispatch` Javadoc:
```
 * @param sender may be null (e.g., synthetic dispatch / test harness); when null,
 *               per-player rate limiting and PII-tagged error logging are skipped.
```

### IN-10: `cleanDdgRedirect` does not guard against malformed percent-encoding

**File:** `src/main/java/com/forgebook/integration/websearch/DuckDuckGoHtmlAdapter.java`
**Issue:** `URLDecoder.decode` throws `IllegalArgumentException` on malformed percent-encoding (e.g., stray `%` followed by non-hex). Propagating that up fails the whole search-result parse for one bad link.
**Fix:** Wrap the decode and skip on failure:
```java
try {
    return URLDecoder.decode(raw, StandardCharsets.UTF_8);
} catch (IllegalArgumentException e) {
    LOG.debug("Skipping malformed DDG redirect: {}", raw);
    return null; // caller filters nulls
}
```

### IN-11: ApiKeyScrubFilter patterns are exhaustive for today only

**File:** `src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java`
**Issue:** Patterns cover Authorization, `x-api-key`, `X-Subscription-Token`, `sk-ant-*`, `sk-proj-*`, and `api_key=` query params. A future provider using a different header convention (e.g., `X-Api-Token`, `Bearer <token>` in an exotic location) will not be scrubbed. Not an issue today.
**Fix:** Add a Javadoc reminder: "When adding a new `AIProvider` implementation, register its secret-bearing header/pattern here."

### IN-12: ITERATION_CAP error text hardcodes the constant value

**File:** `src/main/java/com/forgebook/ai/AiDispatcher.java` (ITERATION_CAP branch in `mapError`)
**Issue:** The human-readable string for `ITERATION_CAP` hardcodes `"6 iterations"`. `AgentLoopE2ETest` line 165 asserts `.contains("6")`. If `AgentLoop.MAX_ITERATIONS` is raised in a future phase, the message drifts silently.
**Fix:** Build the message from the constant:
```java
String.format("Agent exceeded %d iterations; terminating.", AgentLoop.MAX_ITERATIONS)
```
And update the test assertion to reference the constant too:
```java
assertTrue(err.humanReadable().contains(String.valueOf(AgentLoop.MAX_ITERATIONS)));
```

### IN-13: Reload command logs operator's text name (audit-posture note)

**File:** `src/main/java/com/forgebook/command/ForgebookReloadCommand.java:46`
**Issue:** `LOG.info("ForgeBook config reloaded by {}", ctx.getSource().getTextName());` logs operator display name. Appropriate for an OP-only server-side audit log; confirm this matches logging policy (not leaked to clients, not PII-sensitive in this context).
**Fix:** None required; document in audit-posture notes if a separate doc exists.

### IN-14: `/forgebook reload` ModpackContextCache refresh is implicit

**File:** `src/main/java/com/forgebook/command/ForgebookReloadCommand.java:38-48`
**Issue:** Reload calls `ConfigHolder.set(...)` then `SystemPromptBuilder.buildAndCache(server)`. `ModpackContextCache` refresh happens as a side effect of `buildAndCache` (good), but the reload-command Javadoc does not state this, so a future maintainer reading only this file may add a redundant explicit refresh.
**Fix:** Add a code comment after the `buildAndCache` call:
```java
// buildAndCache refreshes ModpackContextCache as a side effect
// (see SystemPromptBuilder.buildAndCacheInternal).
```

### IN-15: `responseSinkForTests` Javadoc references renamed test

**File:** `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java:57`
**Issue:** Javadoc says "Used by `ChatEchoGameTest` (Plan 01-05) to observe response/error packets." That test was renamed to `ChatDispatchSmokeTest` in Phase 02-07.
**Fix:** Update the Javadoc reference to `ChatDispatchSmokeTest`.

### IN-16: ForgeBookMod constructor wiring density — consider extraction next phase

**File:** `src/main/java/com/forgebook/ForgeBookMod.java:55-78`
**Issue:** The mod constructor wires six distinct listeners inline with fully-qualified class names. Readable today (every listener cross-references a D-XX decision), but growth in Phase 03+ will bloat the constructor. Not a bug.
**Fix:** When the next wiring lands, extract into a private helper:
```java
private void registerForgeEventListeners(IEventBus forgeBus) { ... }
```
Keeps the constructor skimmable.

### IN-17: `AgentLoopE2ETest` StubTool ignores `JsonObject input`

**File:** `src/test/java/com/forgebook/ai/AgentLoopE2ETest.java:302`
**Issue:** `StubTool.invoke(JsonObject input)` returns a hardcoded response without inspecting `input`. Intentional (stub behavior), but a future developer may assume input is validated.
**Fix:** Add a one-line comment:
```java
@Override public String invoke(JsonObject input) {
    return response; // input intentionally ignored — fixed-response test stub.
}
```

---

_Reviewed: 2026-04-16_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
