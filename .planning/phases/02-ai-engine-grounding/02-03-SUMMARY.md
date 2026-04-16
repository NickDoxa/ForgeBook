---
phase: 02-ai-engine-grounding
plan: "03"
subsystem: ai-provider
tags: [anthropic, http-client, circuit-breaker, retry-policy, provider-factory, tdd, java17]
dependency_graph:
  requires:
    - "02-01: ConfigSnapshot.aiApiKey() — raw() called in ClaudeProvider header"
    - "02-01: ConfigSnapshot.maxTokens() — passed to ClaudeRequest.maxTokens"
    - "02-02: AiTurn sealed interface (FinalReply/ToolUses/ProviderError) — parseResponse maps to these"
    - "02-02: AiProvider interface — ClaudeProvider, OpenAiProvider, OllamaProvider all implement it"
    - "02-02: ChatRequest record — all providers receive it"
    - "02-02: Anthropic Gson DTOs (ClaudeRequest, ClaudeResponse, ContentBlock, etc.)"
  provides:
    - "CircuitBreaker (FAILURE_THRESHOLD=5, COOL_OFF=5min, injectable clock seam)"
    - "RetryPolicy record (DEFAULT: 3 retries, 1s base, 30s cap, 25% jitter) with shouldRetry predicate"
    - "HttpExecutor @FunctionalInterface — injectable test seam for HTTP"
    - "ClaudeProvider — hand-rolled Anthropic client on java.net.http.HttpClient + Gson"
    - "OpenAiProvider — NOT_IMPLEMENTED stub (AI-03, D-17)"
    - "OllamaProvider — NOT_IMPLEMENTED stub (AI-03, D-17)"
    - "ProviderFactory.forSnapshot(ConfigSnapshot) — exhaustive AiProviderKind switch"
  affects:
    - "02-05: AiDispatcher calls ProviderFactory.forSnapshot(snap) to get AiProvider"
    - "02-06: AgentLoop calls AiProvider.chat(req) and dispatches on AiTurn variants"
tech_stack:
  added: []
  patterns:
    - "HttpExecutor @FunctionalInterface test seam — production uses HttpClient::send; tests inject RecordingExecutor"
    - "RetryPolicy as immutable record with DEFAULT constant — pure compute, no state"
    - "CircuitBreaker with injectable LongSupplier clock — cool-off tests advance time deterministically"
    - "switch expression on sealed AiProviderKind enum for compile-time exhaustiveness in ProviderFactory"
    - "TDD RED/GREEN per task with separate test and feat commits"
key_files:
  created:
    - src/main/java/com/forgebook/ai/CircuitBreaker.java
    - src/main/java/com/forgebook/ai/RetryPolicy.java
    - src/main/java/com/forgebook/ai/provider/HttpExecutor.java
    - src/main/java/com/forgebook/ai/provider/ClaudeProvider.java
    - src/main/java/com/forgebook/ai/provider/OpenAiProvider.java
    - src/main/java/com/forgebook/ai/provider/OllamaProvider.java
    - src/main/java/com/forgebook/ai/provider/ProviderFactory.java
    - src/test/java/com/forgebook/ai/CircuitBreakerTest.java
    - src/test/java/com/forgebook/ai/RetryPolicyTest.java
    - src/test/java/com/forgebook/ai/provider/ClaudeProviderTest.java
    - src/test/java/com/forgebook/ai/provider/ProviderStubsTest.java
    - src/test/resources/forgebook/phase2/claude-end-turn.json
    - src/test/resources/forgebook/phase2/claude-tool-use.json
    - src/test/resources/forgebook/phase2/claude-4xx.json
    - src/test/resources/forgebook/phase2/claude-529.json
  modified:
    - src/main/java/com/forgebook/ai/CircuitBreaker.java (consecutiveFailures() made public for cross-package test access)
decisions:
  - "ANTHROPIC_VERSION pinned to '2023-06-01' as private static final — not operator-configurable (D-07, T-02-03-02)"
  - "HttpExecutor @FunctionalInterface allows production() factory and test RecordingExecutor without mocking frameworks"
  - "CircuitBreaker.consecutiveFailures() made public (was package-private per plan) to support ClaudeProviderTest assertions from com.forgebook.ai.provider package"
  - "RetryPolicy.delay() skips Thread.sleep when delay is 0ms — test uses Duration.ZERO policy for fast execution"
metrics:
  duration_minutes: 10
  completed_date: "2026-04-16"
  tasks_completed: 3
  tasks_total: 3
  files_created: 15
  files_modified: 1
  commits: 6
---

# Phase 2 Plan 03: AI Provider Layer Summary

**One-liner:** Hand-rolled Anthropic provider on `java.net.http.HttpClient` + Gson with `ANTHROPIC_VERSION="2023-06-01"`, `CircuitBreaker` (5-failure/5-min), `RetryPolicy` (3 retries, exp backoff 30s cap), `HttpExecutor` seam, and `OpenAI`/`Ollama` NOT_IMPLEMENTED stubs selected by `ProviderFactory`.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | CircuitBreakerTest + RetryPolicyTest failing | a005bb7 | CircuitBreakerTest.java, RetryPolicyTest.java |
| 1 (GREEN) | CircuitBreaker + RetryPolicy | 71782eb | CircuitBreaker.java, RetryPolicy.java (+ test fix) |
| 2 (RED) | ClaudeProviderTest + 4 fixtures failing | feffd13 | ClaudeProviderTest.java, 4x .json fixtures |
| 2 (GREEN) | HttpExecutor + ClaudeProvider | 77a7f0a | HttpExecutor.java, ClaudeProvider.java (+ CircuitBreaker public, test import fix) |
| 3 (RED) | ProviderStubsTest failing | 8c2de7c | ProviderStubsTest.java |
| 3 (GREEN) | OpenAiProvider + OllamaProvider + ProviderFactory | 8b70b19 | OpenAiProvider.java, OllamaProvider.java, ProviderFactory.java |

## What Was Built

### CircuitBreaker.java (new)

`AtomicInteger consecutiveFailures` + `AtomicLong trippedUntil`, injectable `LongSupplier` clock for deterministic cool-off testing.

Constants:
- `FAILURE_THRESHOLD = 5` — trips after exactly 5 consecutive failures
- `COOL_OFF = Duration.ofMinutes(5)` — stays open for 5 minutes after trip

Behavior: `recordFailure()` increments counter; at threshold sets `trippedUntil = now + 300000ms`. `isOpen()` returns `clock.getAsLong() < trippedUntil`. `recordSuccess()` resets both to 0.

### RetryPolicy.java (new)

Immutable `record` with `DEFAULT` constant:

| Constant | Value |
|----------|-------|
| `maxAttempts` | 3 |
| `baseDelay` | 1 second |
| `maxDelay` | 30 seconds |
| `jitter` | 0.25 (±25%) |

`shouldRetry(int status, boolean ioException)` returns `true` for: `{429, 500, 502, 503, 504, 529, IOException}`. Returns `false` for all other 4xx (400, 401, 402, 403, 404, 413, etc.).

`delay(attempt, retryAfter)`: when `retryAfter` present → `min(retryAfter, maxDelay)`; otherwise exponential `baseDelay * 2^attempt`, capped at `maxDelay`, with `±jitter`.

### HttpExecutor.java (new)

```java
@FunctionalInterface
public interface HttpExecutor {
    HttpResponse<String> send(HttpRequest req) throws Exception;
    static HttpExecutor production() { ... }
}
```

Production factory wraps `HttpClient.newHttpClient()`. Tests inject `RecordingExecutor` (captures `List<HttpRequest> calls`, returns canned responses from an iterator).

### ClaudeProvider.java (new)

AiTurn mapping table for all `stop_reason` values:

| `stop_reason` | AiTurn variant | Notes |
|---------------|----------------|-------|
| `"end_turn"` | `FinalReply(text, false)` | Normal completion |
| `"stop_sequence"` | `FinalReply(text, false)` | Custom stop sequence hit |
| `"max_tokens"` | `FinalReply(text, true)` | `truncated=true` |
| `"tool_use"` | `ToolUses(list)` | Extracts all `type=tool_use` blocks |
| `null` | treated as `"end_turn"` | Defensive fallback |
| (other) | `ProviderError(PROVIDER, ...)` | Unknown stop reason |

Wire contract:
- `POST https://api.anthropic.com/v1/messages`
- `x-api-key: {snap.aiApiKey().raw()}`
- `anthropic-version: 2023-06-01` (pinned constant)
- `content-type: application/json`
- `accept: application/json`
- `timeout: 60s`

Retry/breaker integration:
- Circuit open → immediate `CIRCUIT_OPEN` ProviderError, 0 HTTP calls
- 400/401/403/404/413 → `PROVIDER` error, no retry, 1 call total
- 429 → `RATE_LIMITED`, retried up to 3x (respects `retry-after` header)
- 500/502/503/504 → `TRANSPORT`, retried up to 3x (4 total calls)
- 529 → `OVERLOADED`, retried up to 3x

### OpenAiProvider + OllamaProvider (new stubs)

Both: no-arg public constructor (does not throw — D-17). `chat()` returns `CompletableFuture.completedFuture(ProviderError(NOT_IMPLEMENTED, "...", Optional.empty()))`. Messages contain provider name for operator readability.

### ProviderFactory.java (new)

```java
public static AiProvider forSnapshot(ConfigSnapshot snap) {
    return switch (snap.aiProvider()) {
        case ANTHROPIC -> new ClaudeProvider();
        case OPENAI    -> new OpenAiProvider();
        case OLLAMA    -> new OllamaProvider();
    };
}
```

Exhaustive `switch` expression on sealed `AiProviderKind` enum — compiler verifies all cases covered.

## Test Results

| Suite | Tests | Result |
|-------|-------|--------|
| `CircuitBreakerTest` | 6 | PASS |
| `RetryPolicyTest` | 6 | PASS |
| `ClaudeProviderTest` | 11 | PASS |
| `ProviderStubsTest` | 6 | PASS |

Total: 29 tests, all passing. Zero real network calls in any test.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] CircuitBreakerTest test5 cool-off time arithmetic was off by 1 second**
- **Found during:** Task 1 GREEN phase — test5 failed: `now[0]` advanced by 4m59s + 2ms was `1_299_002` but `trippedUntil` was `1_300_000`, so breaker was still open
- **Issue:** The test comment said "+2ms total extra" but 4m59s = 299000ms, and adding 2ms gives 299002ms which is still less than 300000ms (5 minutes)
- **Fix:** Changed the second time advance from `Duration.ofMillis(2)` to `Duration.ofSeconds(2)`, so total advance is 4m59s + 2s = 5m1s — definitively past the cool-off boundary
- **Files modified:** `src/test/java/com/forgebook/ai/CircuitBreakerTest.java`
- **Commit:** 71782eb

**2. [Rule 1 - Bug] ClaudeProviderTest missing `import java.net.http.HttpClient`**
- **Found during:** Task 2 GREEN compile — `HttpClient.Version` in the `mockResponse` anonymous class caused "package HttpClient does not exist" error
- **Issue:** The mock response stub overrides `version()` returning `HttpClient.Version.HTTP_1_1`, which requires `java.net.http.HttpClient` to be imported
- **Fix:** Added `import java.net.http.HttpClient;` to ClaudeProviderTest.java
- **Files modified:** `src/test/java/com/forgebook/ai/provider/ClaudeProviderTest.java`
- **Commit:** 77a7f0a

**3. [Rule 2 - Missing Critical] CircuitBreaker.consecutiveFailures() made public**
- **Found during:** Task 2 GREEN compile — `ClaudeProviderTest` (in package `com.forgebook.ai.provider`) called `breaker.consecutiveFailures()` which was `package-private` (visible only in `com.forgebook.ai`)
- **Issue:** The plan called this method "For tests only" but the test that exercises it (test11) lives in a different package than the production class
- **Fix:** Changed `int consecutiveFailures()` → `public int consecutiveFailures()` in CircuitBreaker.java
- **Impact:** Minimal — the method is documented as test-only; no production code calls it. The alternative (moving to same package or using reflection) would be more fragile
- **Files modified:** `src/main/java/com/forgebook/ai/CircuitBreaker.java`
- **Commit:** 77a7f0a

## Threat Mitigations Applied

| Threat | Status |
|--------|--------|
| T-02-03-01: ai_api_key leaked in error messages | Mitigated — `snap.aiApiKey().raw()` only used in header; error translation uses `resp.body()`. ApiKey.toString() returns `<redacted>`. Log4j2 scrubber (02-01) catches any x-api-key in logs. |
| T-02-03-02: Wrong anthropic-version causes schema drift | Mitigated — `ANTHROPIC_VERSION = "2023-06-01"` as private static final; Test 8 asserts exact literal on every request |
| T-02-03-03: Runaway retry exhausts AiExecutor queue | Mitigated — RetryPolicy.DEFAULT caps at 3 retries; Test 5 asserts exactly 4 HTTP calls |
| T-02-03-04: Provider outage causes 4x cost per request | Mitigated — CircuitBreaker trips after 5 consecutive failures; Test 10 verifies 0 HTTP calls when open |
| T-02-03-05: Stub silently returns empty success | Mitigated — stubs return ProviderError(NOT_IMPLEMENTED); Tests 1/2 assert Kind is NOT_IMPLEMENTED |

## Threat Flags

None — no new network endpoints, auth paths, file access patterns, or schema changes introduced beyond what is in the plan's threat model. All files are pure Java with no Minecraft imports.

## Known Stubs

None — all interfaces are complete contracts; stubs fail loudly at invocation rather than silently (NOT_IMPLEMENTED). ClaudeProvider is a real implementation, not a placeholder.

## Success Criteria Verification

1. `ClaudeProvider` posts to `/v1/messages` with exact pinned headers (tested via HttpExecutor seam). **PASS** — Test 8 asserts x-api-key, anthropic-version, content-type
2. `RetryPolicy.shouldRetry` returns true for {429, 500, 502, 503, 504, 529, IOException} and false for all other 4xx. **PASS** — Tests 1/2/3
3. `CircuitBreaker` trips at exactly 5 consecutive failures and cools for exactly 5 minutes (proven via injected clock). **PASS** — Tests 3/5/6
4. `ClaudeProvider.parseResponse` correctly maps all four `stop_reason` values to `AiTurn` variants. **PASS** — Tests 1/2/3
5. Stubs return `NOT_IMPLEMENTED` at invocation, not at construction. **PASS** — Tests 1/2/3 (ProviderStubsTest)
6. `ProviderFactory` selects the right impl for each `AiProviderKind` with compile-time exhaustiveness. **PASS** — Tests 4/5/6 (ProviderStubsTest) + switch expression covers all 3 enum values

## Self-Check: PASSED

Files exist:
- `src/main/java/com/forgebook/ai/CircuitBreaker.java` — FOUND
- `src/main/java/com/forgebook/ai/RetryPolicy.java` — FOUND
- `src/main/java/com/forgebook/ai/provider/HttpExecutor.java` — FOUND
- `src/main/java/com/forgebook/ai/provider/ClaudeProvider.java` — FOUND
- `src/main/java/com/forgebook/ai/provider/OpenAiProvider.java` — FOUND
- `src/main/java/com/forgebook/ai/provider/OllamaProvider.java` — FOUND
- `src/main/java/com/forgebook/ai/provider/ProviderFactory.java` — FOUND
- `src/test/resources/forgebook/phase2/claude-end-turn.json` — FOUND
- `src/test/resources/forgebook/phase2/claude-tool-use.json` — FOUND
- `src/test/resources/forgebook/phase2/claude-4xx.json` — FOUND
- `src/test/resources/forgebook/phase2/claude-529.json` — FOUND

Commits exist (git log):
- a005bb7 (test RED Task 1: CircuitBreakerTest + RetryPolicyTest) — FOUND
- 71782eb (feat GREEN Task 1: CircuitBreaker + RetryPolicy) — FOUND
- feffd13 (test RED Task 2: ClaudeProviderTest + fixtures) — FOUND
- 77a7f0a (feat GREEN Task 2: HttpExecutor + ClaudeProvider) — FOUND
- 8c2de7c (test RED Task 3: ProviderStubsTest) — FOUND
- 8b70b19 (feat GREEN Task 3: OpenAiProvider + OllamaProvider + ProviderFactory) — FOUND
