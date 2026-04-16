# Phase 2: AI Engine & Grounding - Pattern Map

**Mapped:** 2026-04-15
**Files analyzed:** 22 (17 new + 5 modified)
**Analogs found:** 22 / 22 (every new file has at least a role-match analog inside the Phase 1 codebase)

---

## File Classification

### NEW files (17)

| New File | Package | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|---|
| `AiDispatcher.java` | `com.forgebook.ai` | server singleton / orchestrator | request-response (off-tick) | `network/handler/ChatRequestHandler.java` | exact (executor-hop pattern) |
| `AiProvider.java` | `com.forgebook.ai` | interface (abstraction seam) | request-response (`CompletableFuture<AiTurn>`) | (no direct interface analog) `ApiKey` for value-type philosophy | partial — interface shape is novel |
| `AiTurn.java` (sealed) | `com.forgebook.ai` | sealed result type | typed result | `network/packet/ChatErrorPacket.java#ErrorCode` enum + `UnsafeUrlException#Reason` | role-match (typed taxonomy) |
| `AgentLoop.java` | `com.forgebook.ai` | state-machine orchestrator | event-driven (loop over turns) | `util/SafeHttpFetcher.java` (bounded loop, typed exception, hop limit) | role-match (bounded loop with typed errors) |
| `CircuitBreaker.java` | `com.forgebook.ai` | utility (concurrent state) | event-driven (success/failure ticks) | `util/AiExecutor.java` (volatile static, lifecycle methods) | role-match (process-wide singleton) |
| `RetryPolicy.java` | `com.forgebook.ai` | utility (pure compute) | transform | `util/Cidr.java` (stateless static helper) | role-match |
| `SystemPromptBuilder.java` | `com.forgebook.ai` | builder / startup hook | batch (one-shot at `ServerStartedEvent`) | `config/ConfigHolder.java#buildFromSpec()` (startup builder) | role-match |
| `SystemPromptCache.java` | `com.forgebook.ai` | volatile holder | event-driven (set on startup/reload) | `config/ConfigHolder.java` (volatile + set/get) | exact |
| `ChatRequest.java` | `com.forgebook.ai` | DTO (record) | data carrier | `config/ConfigSnapshot.java` (immutable record) | exact |
| `dto/ClaudeRequest.java` | `com.forgebook.ai.dto` | Gson DTO | data carrier (serialize) | `network/packet/ChatRequestPacket.java` (record + ser/de) | role-match |
| `dto/ClaudeResponse.java` | `com.forgebook.ai.dto` | Gson DTO | data carrier (deserialize) | `network/packet/ChatResponsePacket.java` | role-match |
| `dto/ClaudeMessage.java` | `com.forgebook.ai.dto` | Gson DTO | data carrier | `config/ConfigSnapshot.java` (record) | role-match |
| `dto/ContentBlock.java` | `com.forgebook.ai.dto` | Gson DTO (unified type) | data carrier | `config/ConfigSnapshot.java` | role-match |
| `dto/ClaudeError.java` | `com.forgebook.ai.dto` | Gson DTO | data carrier | `network/packet/ChatErrorPacket.java` | role-match |
| `provider/ClaudeProvider.java` | `com.forgebook.ai.provider` | HTTP client adapter | request-response (HTTP+JSON) | `util/SafeHttpFetcher.java` (HTTP, typed errors, retries) | exact (modulo SafeHttpFetcher gating — see notes) |
| `provider/OpenAiProvider.java` | `com.forgebook.ai.provider` | stub adapter | request-response (throws) | `util/AiExecutor.java#get()` (clean fail-fast: `IllegalStateException`) | role-match |
| `provider/OllamaProvider.java` | `com.forgebook.ai.provider` | stub adapter | request-response (throws) | same as `OpenAiProvider` | role-match |
| `tool/Tool.java` | `com.forgebook.tool` | interface | request-response | (no direct analog) | partial |
| `tool/ToolRegistry.java` | `com.forgebook.tool` | registry / startup hook | lookup | `network/ForgebookNetwork.java` (`register()` called from setup) | role-match |
| `tool/ToolResult.java` | `com.forgebook.tool` | DTO (record) | data carrier | `util/SafeHttpFetcher.java#Result` (record returned from utility) | exact |
| `tool/ToolException.java` | `com.forgebook.tool` | typed checked exception | error surfacing | `util/UnsafeUrlException.java` | exact |
| `tool/impl/ListInstalledModsTool.java` | `com.forgebook.tool.impl` | tool (queries `ModList`) | transform (in-process) | `util/Cidr.java` (pure helper) | partial |
| `tool/impl/FetchModDocsPageTool.java` | `com.forgebook.tool.impl` | tool (HTTP via SafeHttpFetcher) | request-response | `util/SafeHttpFetcher.java` caller pattern | exact |
| `tool/impl/WebSearchTool.java` | `com.forgebook.tool.impl` | tool (selects adapter) | request-response | same | exact |
| `tool/impl/GetModpackContextTool.java` | `com.forgebook.tool.impl` | tool (cache read) | lookup | `config/ConfigHolder.java#get()` | exact |
| `integration/CurseForgeClient.java` | `com.forgebook.integration` | HTTP client adapter | request-response (one-shot at startup) | `util/SafeHttpFetcher.java` (HTTP + typed errors) | role-match (uses raw HttpClient — JSON, not allowlist) |
| `integration/ModpackContext.java` | `com.forgebook.integration` | DTO (record) | data carrier | `config/ConfigSnapshot.java` | exact |
| `integration/ModpackContextCache.java` | `com.forgebook.integration` | volatile holder | event-driven (set on startup/reload) | `config/ConfigHolder.java` | exact |
| `integration/websearch/WebSearchAdapter.java` | `com.forgebook.integration.websearch` | interface | request-response | (no direct analog) | partial |
| `integration/websearch/SearchResult.java` | `com.forgebook.integration.websearch` | DTO (record) | data carrier | `util/SafeHttpFetcher.java#Result` | exact |
| `integration/websearch/DuckDuckGoHtmlAdapter.java` | `com.forgebook.integration.websearch` | adapter (HTTP + jsoup) | request-response | `util/SafeHttpFetcher.java` caller pattern | exact |
| `integration/websearch/BraveSearchAdapter.java` | `com.forgebook.integration.websearch` | adapter (HTTP + JSON) | request-response | `util/SafeHttpFetcher.java` (typed errors) — but uses raw HttpClient | role-match |
| `integration/scraper/ModDocsScraper.java` | `com.forgebook.integration.scraper` | utility (jsoup readability) | transform | `util/Cidr.java` (stateless static helper) | role-match |
| `integration/scraper/PromptFraming.java` | `com.forgebook.integration.scraper` | utility (string transform) | transform | `util/Cidr.java` | exact |
| `config/WebSearchProviderKind.java` | `com.forgebook.config` | enum | typed value | `config/AiProviderKind.java` | exact |

### MODIFIED files (5)

| Modified File | Modification | Closest Analog (for the new code) |
|---|---|---|
| `config/ForgebookServerConfig.java` | Add `MAX_TOKENS`, `WEB_SEARCH_PROVIDER`, `WEB_SEARCH_API_KEY`; bump `AI_MODEL` default to `"claude-haiku-4-5"` | `ForgebookServerConfig` itself (extend the existing static block in the same idiom) |
| `config/ConfigSnapshot.java` | Add 3 fields: `int maxTokens`, `WebSearchProviderKind webSearchProvider`, `ApiKey webSearchApiKey` | `ConfigSnapshot` itself (record extension) |
| `config/ConfigHolder.java` | Wire 3 new fields into `buildFromSpec()` | `ConfigHolder.buildFromSpec()` (extend existing constructor call) |
| `network/handler/ChatRequestHandler.java` | Replace echo body inside `AiExecutor.get().submit(...)` with `AiDispatcher.INSTANCE.dispatch(...)` | The same file's `handleForTest` body (keep scaffolding; swap task) |
| `ForgeBookMod.java` | Add a `ServerStartedEvent` listener wiring `SystemPromptBuilder.buildAndCache(...)`, `ToolRegistry.init(...)`, and the CurseForge fetch | `ForgeBookMod.java` lines 58-70 (existing `MinecraftForge.EVENT_BUS.addListener(...)` calls) |

---

## Pattern Assignments

### `com.forgebook.ai.AiDispatcher` (server singleton, request-response)

**Analog:** `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java`

**Executor-hop pattern (lines 96-128) — copy verbatim, swap task body:**
```java
try {
    AiExecutor.get().submit(() -> {
        // Phase 1: echo (no provider call).
        // Phase 2+: AiDispatcher.dispatch(pkt, sender) -> Claude provider.
        String reply = "echo: " + pkt.message();
        ChatResponsePacket resp = new ChatResponsePacket(pkt.requestId(), reply);

        enqueueWork.accept(() -> {
            Consumer<Object> sink = responseSinkForTests;
            if (sink != null) {
                sink.accept(resp);
            } else {
                responder.accept(resp);
            }
        });
    });
} catch (RejectedExecutionException e) {
    LOG.warn("aiExecutor rejected submission; returning OVERLOADED to {}",
        sender != null ? sender.getUUID() : "<no sender>");
    ChatErrorPacket err = new ChatErrorPacket(
        pkt.requestId(), ErrorCode.OVERLOADED, "Server is busy. Try again.");
    ...
}
```

**Singleton pattern (volatile static `INSTANCE`):** copy from `util/AiExecutor.java` lines 26-37:
```java
private static volatile ThreadPoolExecutor INSTANCE;
public static ExecutorService get() {
    ThreadPoolExecutor e = INSTANCE;
    if (e == null) {
        throw new IllegalStateException(
            "aiExecutor not started — ServerStartingEvent hasn't fired?");
    }
    return e;
}
```

**Guidance:** `AiDispatcher` is invoked from inside `ChatRequestHandler`'s `AiExecutor.submit(...)` task — it does NOT submit to AiExecutor itself. It synchronously runs `AgentLoop.run(...)` (which itself submits parallel sub-tasks back to AiExecutor), returns a `Result` (sealed `Reply | Error`), and lets the handler do `enqueueWork`.

---

### `com.forgebook.ai.AiProvider` (interface, request-response)

**Analog (philosophy):** none (new interface); model after a minimal seam.

**Pattern excerpt (planner-authored shape, anchored on JDK `HttpClient.send` style):**
```java
public interface AiProvider {
    /** Off-tick HTTP/provider call. MUST be invoked from AiExecutor or
     *  AgentLoop's own executor — never from the network thread or main thread. */
    CompletableFuture<AiTurn> chat(ChatRequest req);
}
```

**Guidance:** Interface lives at the package root so `provider/*` impls and tests' `ScriptedAiProvider` both see it. Mirror the same "pure-Java seam" approach used by `SafeHttpFetcher`'s package-private `Predicate<InetAddress> cidrCheck` test seam (see `SafeHttpFetcher.java` lines 39-51). The seam is what makes the AgentLoop testable without network or Mockito.

---

### `com.forgebook.ai.AiTurn` (sealed type, typed result)

**Analog:** `src/main/java/com/forgebook/util/UnsafeUrlException.java` (typed error taxonomy via `enum Reason`) AND `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java#ErrorCode`.

**Typed-error pattern from `UnsafeUrlException` (lines 10-34):**
```java
public final class UnsafeUrlException extends Exception {
    public enum Reason {
        SCHEME, PRIVATE_IP, REDIRECT_LIMIT, SIZE_CAP, CONTENT_TYPE, TIMEOUT
    }
    private final Reason reason;
    public UnsafeUrlException(Reason reason) {
        super("Unsafe URL: " + reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
```

**Error-code enum from `ChatErrorPacket` (lines 19-26):**
```java
public enum ErrorCode {
    OVERLOADED, TRANSPORT, RATE_LIMITED, FORBIDDEN, PROVIDER, DISABLED
}
```

**Guidance:** `AiTurn` is a sealed interface (Java 17 sealed types) with three permitted records:
```java
public sealed interface AiTurn permits AiTurn.FinalReply, AiTurn.ToolUses, AiTurn.ProviderError {
    record FinalReply(String text, boolean truncated) implements AiTurn {}
    record ToolUses(List<ToolUseBlock> uses) implements AiTurn {}
    record ProviderError(Kind kind, String message, Optional<Duration> retryAfter) implements AiTurn {
        public enum Kind {
            TRANSPORT, PROVIDER, OVERLOADED, RATE_LIMITED, NOT_IMPLEMENTED,
            CIRCUIT_OPEN, ITERATION_CAP
        }
    }
}
```
The `ProviderError.Kind` enum maps 1:1 onto `ChatErrorPacket.ErrorCode` in Phase 3 — match names where the semantic is identical (`OVERLOADED`, `TRANSPORT`, `PROVIDER`, `RATE_LIMITED`).

---

### `com.forgebook.ai.AgentLoop` (state machine, event-driven)

**Analog:** `src/main/java/com/forgebook/util/SafeHttpFetcher.java` — bounded loop with typed exit conditions.

**Bounded-iteration loop pattern (SafeHttpFetcher.java lines 53-119):**
```java
public Result fetch(URI start) throws UnsafeUrlException, IOException {
    URI current = start;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
        if (!"https".equalsIgnoreCase(current.getScheme()))
            throw new UnsafeUrlException(UnsafeUrlException.Reason.SCHEME);
        // ... per-hop work ...
        if (code >= 300 && code < 400) {
            current = current.resolve(loc);
            continue;  // re-validate on next loop iteration
        }
        return new Result(out.toString(StandardCharsets.UTF_8), mime, current);
    }
    throw new UnsafeUrlException(UnsafeUrlException.Reason.REDIRECT_LIMIT);
}
```

**Apply the same shape to AgentLoop — `MAX_REDIRECTS=3` becomes `MAX_ITERATIONS=6`, hop becomes turn:**
```java
for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
    AiTurn turn = provider.chat(req).get();
    switch (turn) {
        case AiTurn.FinalReply r -> { return r; }
        case AiTurn.ToolUses uses -> {
            List<ToolResult> results = executeParallel(uses.uses());  // D-11
            messages.add(assistantMessage(uses));
            messages.add(userMessageWithToolResults(results));         // D-12
        }
        case AiTurn.ProviderError err -> { return err; }
    }
}
return new AiTurn.ProviderError(Kind.ITERATION_CAP, "exceeded 6 iterations", Optional.empty());
```

**Parallel sub-task submission pattern — copy from RESEARCH §7.2; the `AiExecutor.get()` invocation matches `ChatRequestHandler.java:98`:**
```java
List<CompletableFuture<ToolResult>> futures = uses.stream()
    .map(use -> CompletableFuture.supplyAsync(
        () -> invokeTool(use), AiExecutor.get()))
    .toList();
CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
List<ToolResult> results = futures.stream().map(CompletableFuture::join).toList();
```

**Guidance:** Same off-tick-only invariant as `SafeHttpFetcher`. Order-preserving `stream().map(::join)` is critical — Anthropic requires `tool_result` blocks in the same order as `tool_use` blocks.

---

### `com.forgebook.ai.CircuitBreaker` (concurrent state, event-driven)

**Analog:** `src/main/java/com/forgebook/util/AiExecutor.java` — volatile static + lifecycle methods.

**Volatile-static singleton pattern (AiExecutor.java lines 23-78):**
```java
public final class AiExecutor {
    private static final Logger LOG = LogManager.getLogger();
    private static volatile ThreadPoolExecutor INSTANCE;
    private AiExecutor() {}
    public static ExecutorService get() { ... }
    public static synchronized void start() { ... }
    public static void onServerStopping(ServerStoppingEvent e) { ... }
}
```

**Guidance:** Per RESEARCH §7.4, use `AtomicInteger consecutiveFailures` + `AtomicLong trippedUntil`. Single shared instance owned by `ClaudeProvider` (or `AiDispatcher`). Public surface: `boolean isOpen()`, `void recordSuccess()`, `void recordFailure()`. Match Phase 1's "private constructor + static volatile + LogManager.getLogger()" idiom.

---

### `com.forgebook.ai.RetryPolicy` (pure compute, transform)

**Analog:** `src/main/java/com/forgebook/util/Cidr.java` (stateless static helper — see `Cidr::isBlocked` invocation in `SafeHttpFetcher.java:42`).

**Pattern:** record with `static final RetryPolicy DEFAULT = ...` and a pure `Duration delay(int attempt, Optional<Duration> retryAfter)` method (see RESEARCH §7.3 for exact body). Static `boolean shouldRetry(int status, boolean ioException)` mirrors `Cidr.isBlocked`'s pure-predicate shape.

**Guidance:** Constants live as record fields, not magic numbers in callers. No logger needed (pure compute).

---

### `com.forgebook.ai.SystemPromptBuilder` (startup builder, batch)

**Analog:** `src/main/java/com/forgebook/config/ConfigHolder.java#buildFromSpec()` (lines 27-42) — same "build immutable result from external sources at startup" shape.

**Builder pattern excerpt (ConfigHolder.java lines 27-42):**
```java
public static ConfigSnapshot buildFromSpec() {
    java.util.Optional<String> modpackId = java.util.Optional
        .ofNullable(ForgebookServerConfig.CURSEFORGE_MODPACK_ID.get())
        .filter(s -> !s.isBlank());
    return new ConfigSnapshot(
        ForgebookServerConfig.AI_PROVIDER.get(),
        new ApiKey(ForgebookServerConfig.AI_API_KEY.get()),
        ...
    );
}
```

**Guidance:** Static-method builder that takes `(List<ModInfo> mods, Optional<ModpackContext> modpack, ConfigSnapshot snap)` and returns `String`. Pure function — no side effects (publication is `SystemPromptCache.set(...)` done by the caller in `ForgeBookMod`'s `ServerStartedEvent` listener). Add `static void buildAndCache(MinecraftServer server)` convenience entry that does the orchestration shown in RESEARCH §6.3.

---

### `com.forgebook.ai.SystemPromptCache` (volatile holder, event-driven)

**Analog:** `src/main/java/com/forgebook/config/ConfigHolder.java` — exact match.

**Volatile holder pattern (ConfigHolder.java lines 14-26):**
```java
public final class ConfigHolder {
    private static volatile ConfigSnapshot current = null;
    private ConfigHolder() {}
    public static ConfigSnapshot get() { return current; }
    public static void set(ConfigSnapshot s) { current = s; }
}
```

**Guidance:** Identical shape — `private static volatile String current`, `get()`, `set(String)`. Defensive `get()` may return `""` (empty) when not yet built, matching the documented startup ordering in `ForgeBookMod.java:59-62`.

---

### `com.forgebook.ai.ChatRequest` (DTO, data carrier)

**Analog:** `src/main/java/com/forgebook/config/ConfigSnapshot.java` — record-as-immutable-snapshot.

**Record DTO pattern (ConfigSnapshot.java lines 10-20):**
```java
public record ConfigSnapshot(
    AiProviderKind aiProvider,
    ApiKey aiApiKey,
    String aiModel,
    Optional<String> curseforgeModpackId,
    ApiKey curseforgeApiKey,
    boolean opOnly,
    int rateLimitPerMinute,
    boolean enableWebSearch,
    int configVersion
) {}
```

**Guidance:** `record ChatRequest(String userMessage, String system, List<ClaudeMessage> history, List<Tool> tools, int maxTokens, String model)`. No constructor logic — use defaults at the call site. `Optional<>` for nullable fields per `ConfigSnapshot.curseforgeModpackId` precedent.

---

### `com.forgebook.ai.dto.*` (Gson DTOs)

**Analog:** `src/main/java/com/forgebook/network/packet/ChatRequestPacket.java` (record + ser/de) AND `config/ConfigSnapshot.java` (immutable record).

**Record + ser/de pattern from ChatRequestPacket.java (lines 15-26):**
```java
public record ChatRequestPacket(UUID requestId, String message) {
    public static void encode(ChatRequestPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.requestId);
        buf.writeUtf(p.message, 32_000);
    }
    public static ChatRequestPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String msg = buf.readUtf(32_000);
        return new ChatRequestPacket(id, msg);
    }
    ...
}
```

**Guidance:** DTOs are records with `@SerializedName` annotations only where Java field names diverge from Anthropic's wire names (e.g., `max_tokens`, `tool_use_id`, `stop_reason`, `input_schema`). Gson (already on classpath via Minecraft 1.20.1) round-trips records since Gson 2.10. **No `dependencies { }` declaration** — CLAUDE.md "What NOT to Use" forbids redeclaring Gson. Field naming exactly matches CLAUDE.md §e:
- `ClaudeRequest { String model; @SerializedName("max_tokens") int maxTokens; String system; List<ClaudeMessage> messages; @Nullable List<ToolDef> tools; }`
- `ClaudeResponse { List<ContentBlock> content; @SerializedName("stop_reason") String stopReason; Usage usage; }`
- `ContentBlock { String type; @Nullable String text; @Nullable String name; @Nullable JsonElement input; @Nullable String id; @Nullable @SerializedName("tool_use_id") String toolUseId; @Nullable JsonElement content; @Nullable @SerializedName("is_error") Boolean isError; }`

---

### `com.forgebook.ai.provider.ClaudeProvider` (HTTP adapter, request-response)

**Analog:** `src/main/java/com/forgebook/util/SafeHttpFetcher.java` (lines 30-119) — HTTP idiom, typed errors, constants-at-top.

**Constants-at-top pattern (SafeHttpFetcher.java lines 30-36):**
```java
public final class SafeHttpFetcher {
    public static final long SIZE_CAP = 1_048_576L;     // 1 MB (D-26)
    public static final int TIMEOUT_MS = 15_000;        // 15 s (D-26)
    public static final int MAX_REDIRECTS = 3;          // D-23
    private static final Set<String> CONTENT_ALLOWLIST = Set.of(
        "text/html", "text/plain", "application/xhtml+xml");
```

**Apply to ClaudeProvider:**
```java
private static final String ANTHROPIC_VERSION = "2023-06-01";   // D-07, RESEARCH §1.1
private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
```

**Test-seam pattern from SafeHttpFetcher (lines 39-51):**
```java
private final Predicate<InetAddress> cidrCheck;
public SafeHttpFetcher() { this(Cidr::isBlocked); }
SafeHttpFetcher(Predicate<InetAddress> cidrCheck) { this.cidrCheck = cidrCheck; }
```

**Apply to ClaudeProvider — inject `HttpExecutor` seam (RESEARCH §9.4):**
```java
public interface HttpExecutor { HttpResponse<String> send(HttpRequest req) throws Exception; }

public final class ClaudeProvider implements AiProvider {
    private final HttpExecutor http;
    private final CircuitBreaker breaker;
    public ClaudeProvider() { this(java.net.http.HttpClient.newHttpClient()::send, new CircuitBreaker()); }
    ClaudeProvider(HttpExecutor http, CircuitBreaker breaker) {
        this.http = http; this.breaker = breaker;
    }
}
```

**Auth/secret pattern (CLAUDE.md "secrets" + Phase 1 D-13):** `ClaudeProvider` is one of the two packages allowed to call `ApiKey.raw()` (Phase 1's CI grep-lint allows `com.forgebook.ai.*` and `com.forgebook.integration.*`):
```java
ConfigSnapshot snap = ConfigHolder.get();
HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
    .header("x-api-key", snap.aiApiKey().raw())   // .raw() allowed here
    .header("anthropic-version", ANTHROPIC_VERSION)
    .header("content-type", "application/json")
    .header("accept", "application/json")
    .timeout(REQUEST_TIMEOUT)
    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(claudeReq)))
    .build();
```

**Logger pattern (every Phase 1 file uses this):**
```java
private static final Logger LOG = LogManager.getLogger();
```

**Error-translation pattern — map HTTP status to `AiTurn.ProviderError.Kind` per RESEARCH §1.5 table:**
```java
if (status == 429) return new AiTurn.ProviderError(Kind.RATE_LIMITED, body, parseRetryAfter(resp));
if (status == 529) return new AiTurn.ProviderError(Kind.OVERLOADED, body, Optional.empty());
if (status >= 500) return new AiTurn.ProviderError(Kind.TRANSPORT, body, Optional.empty());
if (status >= 400) return new AiTurn.ProviderError(Kind.PROVIDER, body, Optional.empty());
```

**Guidance:** Does NOT use `SafeHttpFetcher` (which only allows `text/html|text/plain|application/xhtml+xml` content-types and is for untrusted egress). Use raw `java.net.http.HttpClient` to `api.anthropic.com` (trusted, fixed endpoint). Document this split prominently — see RESEARCH §"Existing-Code Findings → SafeHttpFetcher" gap note.

---

### `com.forgebook.ai.provider.OpenAiProvider` / `OllamaProvider` (stub, throws-on-invocation)

**Analog:** `src/main/java/com/forgebook/util/AiExecutor.java#get()` lines 30-37 — clean fail-fast with `IllegalStateException` and explanatory message.

**Fail-fast pattern (AiExecutor.java lines 30-37):**
```java
public static ExecutorService get() {
    ThreadPoolExecutor e = INSTANCE;
    if (e == null) {
        throw new IllegalStateException(
            "aiExecutor not started — ServerStartingEvent hasn't fired?");
    }
    return e;
}
```

**Apply to stubs (D-17 — fail at invocation, NOT at startup):**
```java
public final class OpenAiProvider implements AiProvider {
    @Override public CompletableFuture<AiTurn> chat(ChatRequest req) {
        return CompletableFuture.completedFuture(new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.NOT_IMPLEMENTED,
            "OpenAI provider is not implemented in v1. Set ai_provider = ANTHROPIC.",
            Optional.empty()));
    }
}
```

**Guidance:** Same `final class implements AiProvider {}` shape as `ClaudeProvider`, but the `chat()` body returns a `completedFuture(ProviderError)` instead of throwing — preserves the `CompletableFuture` contract while still surfacing the error to `AgentLoop`'s sealed-type switch. Constructor takes `ConfigSnapshot` for forward-compat but ignores it.

---

### `com.forgebook.tool.Tool` (interface)

**Analog (closest):** `network/packet/ChatRequestPacket.java` static `handle` method serves as the "minimal callable surface" idiom.

**Pattern (planner-authored):**
```java
public interface Tool {
    String name();                              // e.g., "fetch_mod_docs_page"
    JsonObject schema();                        // Anthropic input_schema
    String description();
    /** MUST run on AiExecutor (caller's responsibility — see AgentLoop §7.2). */
    String invoke(JsonObject input) throws ToolException;
}
```

**Guidance:** No state, no constructor logic. Implementations are `final class implements Tool` records-or-classes; pick whichever is cleaner per impl. Each tool's `invoke` returns the JSON string that becomes `tool_result.content`.

---

### `com.forgebook.tool.ToolRegistry` (registry, lookup)

**Analog:** `src/main/java/com/forgebook/network/ForgebookNetwork.java` (lines 27-62) — static registry populated once at lifecycle event.

**Static registry pattern (ForgebookNetwork.java lines 31-62):**
```java
public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(...);
private static int nextId = 0;
private static int nextId() { return nextId++; }
private ForgebookNetwork() {}
public static void register() {
    CHANNEL.messageBuilder(ChatRequestPacket.class, nextId(), ...).encoder(...).decoder(...).consumerNetworkThread(...).add();
    ...
}
```

**Apply to ToolRegistry — static map populated from a single `init()` call wired in `ForgeBookMod`'s `ServerStartedEvent` listener:**
```java
public final class ToolRegistry {
    private static final java.util.Map<String, Tool> TOOLS = new java.util.LinkedHashMap<>();
    private ToolRegistry() {}
    public static void init(SafeHttpFetcher fetcher, ModList modList) {
        TOOLS.put("list_installed_mods", new ListInstalledModsTool(modList));
        TOOLS.put("fetch_mod_docs_page", new FetchModDocsPageTool(fetcher));
        TOOLS.put("web_search", new WebSearchTool(fetcher));
        TOOLS.put("get_modpack_context", new GetModpackContextTool());
    }
    public static Tool get(String name) {
        Tool t = TOOLS.get(name);
        if (t == null) throw new IllegalArgumentException("Unknown tool: " + name);
        return t;
    }
    public static java.util.Collection<Tool> all() { return TOOLS.values(); }
}
```

**Guidance:** `LinkedHashMap` preserves insertion order so `all()` returns tools in declaration order — useful for deterministic Anthropic `tools[]` JSON arrays and stable test fixtures. Same private-constructor + static-only API as Phase 1's util classes.

---

### `com.forgebook.tool.ToolResult` (DTO record)

**Analog:** `src/main/java/com/forgebook/util/SafeHttpFetcher.java#Result` (line 37) — exact match.

**Record-as-result pattern (SafeHttpFetcher.java line 37):**
```java
public record Result(String body, String contentType, URI finalUri) {}
```

**Apply:**
```java
public record ToolResult(String toolUseId, String content, boolean isError) {}
```

---

### `com.forgebook.tool.ToolException` (typed checked exception)

**Analog:** `src/main/java/com/forgebook/util/UnsafeUrlException.java` — exact pattern, copy structure verbatim.

**Pattern (UnsafeUrlException.java lines 10-34):**
```java
public final class UnsafeUrlException extends Exception {
    public enum Reason { SCHEME, PRIVATE_IP, REDIRECT_LIMIT, SIZE_CAP, CONTENT_TYPE, TIMEOUT }
    private final Reason reason;
    public UnsafeUrlException(Reason reason) {
        super("Unsafe URL: " + reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
```

**Apply to ToolException — Reason values per RESEARCH §7.2 failure-isolation:**
```java
public final class ToolException extends Exception {
    public enum Reason { UNKNOWN_TOOL, INVALID_INPUT, NO_DOCS_URL, FETCH_FAILED, UPSTREAM_TIMEOUT }
    private final Reason reason;
    public ToolException(Reason reason, String detail) {
        super("Tool failed (" + reason + "): " + detail);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
```

**Guidance:** AgentLoop's failure-isolation block (`invokeTool` per RESEARCH §7.2) catches `Exception` broadly and converts to a structured `tool_result` with `is_error=true` — `ToolException.Reason` provides the structured error label inside `{"error":"NO_DOCS_URL", "detail":"..."}`.

---

### `com.forgebook.tool.impl.ListInstalledModsTool` (tool, transform)

**Analog:** No direct analog in Phase 1 (this is the first `ModList` consumer). Use `util/Cidr.java`'s pure-helper shape for the construction approach.

**Guidance:** Constructor takes `ModList` reference (or use `ModList.get()` lazily inside `invoke()`). Per RESEARCH §5.1, iterate `modList.getMods()`, project to `(modId, displayName, version, modURL)`. Optional `filter` substring input. Result is a Gson-serialized `JsonArray`. Apply `TOOL_OUTPUT_CAP = 8_000` truncation (RESEARCH §7.5).

---

### `com.forgebook.tool.impl.FetchModDocsPageTool` (tool, request-response)

**Analog:** `src/main/java/com/forgebook/util/SafeHttpFetcher.java` — direct caller pattern.

**SafeHttpFetcher caller pattern (RESEARCH "Existing-Code Findings"):**
```java
SafeHttpFetcher fetcher = new SafeHttpFetcher();
SafeHttpFetcher.Result r = fetcher.fetch(URI.create("https://..."));
// r.body() — String; r.contentType(); r.finalUri()
```

**Error mapping (catch UnsafeUrlException → ToolException with mapped Reason):**
```java
try {
    SafeHttpFetcher.Result r = fetcher.fetch(URI.create(url));
    String readable = scraper.extractReadable(r.body(), url);
    String truncated = truncate(readable, url);   // RESEARCH §7.5
    return PromptFraming.wrap(truncated, url);    // <mod_doc trust="untrusted" ...>
} catch (UnsafeUrlException e) {
    throw new ToolException(ToolException.Reason.FETCH_FAILED,
        "url=" + url + " reason=" + e.reason());
} catch (IOException e) {
    throw new ToolException(ToolException.Reason.UPSTREAM_TIMEOUT, "io: " + e.getMessage());
}
```

**Empty URL → structured "no docs" error (TOOL-07, success-criterion-5):**
```java
if (url == null || url.isBlank()) {
    throw new ToolException(ToolException.Reason.NO_DOCS_URL,
        "mod has no documentation url; consider web_search");
}
```

**Guidance:** Constructor takes `SafeHttpFetcher` (injected, not `new`'d inside) so tests can pass a stubbed fetcher. Reuse the single shared `SafeHttpFetcher` instance from `ToolRegistry.init` — it is documented thread-safe (RESEARCH "Existing-Code Findings → SafeHttpFetcher", line "Stateless").

---

### `com.forgebook.tool.impl.WebSearchTool` (tool, request-response)

**Analog:** Same as above (`SafeHttpFetcher` caller). Adapter selection via `ConfigHolder.get()`.

**Config-snapshot read pattern (matches Phase 1 D-14 — `ChatRequestHandler` + `ConfigHolder.get()`):**
```java
ConfigSnapshot snap = ConfigHolder.get();
if (!snap.enableWebSearch()) {
    throw new ToolException(ToolException.Reason.INVALID_INPUT, "web_search disabled by operator");
}
WebSearchAdapter adapter = switch (snap.webSearchProvider()) {
    case DUCKDUCKGO -> new DuckDuckGoHtmlAdapter(fetcher);
    case BRAVE      -> new BraveSearchAdapter(snap.webSearchApiKey());  // raw HttpClient
};
List<SearchResult> results = adapter.search(query, 5);
return Gson.toJson(results);
```

**Guidance:** Read `ConfigHolder.get()` ONCE at the top of `invoke` (Phase 1 D-14 — "single read at request entry").

---

### `com.forgebook.tool.impl.GetModpackContextTool` (tool, lookup)

**Analog:** `src/main/java/com/forgebook/config/ConfigHolder.java#get()`.

**Cache-read pattern:**
```java
Optional<ModpackContext> ctx = ModpackContextCache.get();
if (ctx.isEmpty()) return "{\"modpack\":\"<not configured>\"}";
return gson.toJson(ctx.get());
```

**Guidance:** Pure read — no side effects. No-args invoke. Schema is `{"type":"object","properties":{},"required":[]}`.

---

### `com.forgebook.integration.CurseForgeClient` (HTTP adapter, one-shot)

**Analog:** `src/main/java/com/forgebook/util/SafeHttpFetcher.java` (typed errors, constants-at-top) AND `provider/ClaudeProvider` analog above (raw HttpClient for trusted JSON endpoint).

**Pattern — same shape as ClaudeProvider but synchronous (called from `ServerStartedEvent` listener via `AiExecutor` per RESEARCH §6.3):**
```java
public final class CurseForgeClient {
    private static final Logger LOG = LogManager.getLogger();
    private static final String ENDPOINT = "https://api.curseforge.com/v1/mods/";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public static Optional<ModpackContext> fetch(ConfigSnapshot snap) {
        if (snap.curseforgeModpackId().isEmpty() || snap.curseforgeApiKey().raw().isBlank()) {
            return Optional.empty();   // CF-02: missing config = no enrichment, no error
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT + snap.curseforgeModpackId().get()))
                .header("x-api-key", snap.curseforgeApiKey().raw())   // .raw() allowed in com.forgebook.integration.*
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET().build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("CurseForge returned {}; skipping modpack context", resp.statusCode());
                return Optional.empty();
            }
            return Optional.of(parseResponse(resp.body()));
        } catch (Exception e) {
            LOG.warn("CurseForge fetch failed; skipping modpack context", e);
            return Optional.empty();   // CF-02: degrade gracefully
        }
    }
}
```

**Logger pattern, secret pattern, summary truncation:** see RESEARCH §"Open Question 2" — defensively truncate `summary` to 500 chars in `parseResponse`.

**Guidance:** Returns `Optional.empty()` for ALL failure modes (missing config, network error, 4xx, 5xx) — CF-02 mandates graceful degradation. Logs at WARN, never throws. The only `.raw()` call site outside `com.forgebook.ai.*`.

---

### `com.forgebook.integration.ModpackContext` (DTO record)

**Analog:** `config/ConfigSnapshot.java`.

```java
public record ModpackContext(String name, String summary) {}
```

---

### `com.forgebook.integration.ModpackContextCache` (volatile holder)

**Analog:** `config/ConfigHolder.java` — exact match.

```java
public final class ModpackContextCache {
    private static volatile Optional<ModpackContext> current = Optional.empty();
    private ModpackContextCache() {}
    public static Optional<ModpackContext> get() { return current; }
    public static void set(Optional<ModpackContext> ctx) { current = ctx; }
}
```

---

### `com.forgebook.integration.websearch.DuckDuckGoHtmlAdapter` (adapter, HTTP+jsoup)

**Analog:** `util/SafeHttpFetcher.java` caller pattern (above) PLUS jsoup parsing per RESEARCH §3.1.

**Pattern (RESEARCH §3.1):**
```java
SafeHttpFetcher.Result r = fetcher.fetch(URI.create("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, UTF_8)));
Document doc = Jsoup.parse(r.body());
List<SearchResult> results = new ArrayList<>();
for (Element row : doc.select("div.results_links div.links_main")) {
    Element a = row.selectFirst("a.result__a");
    Element s = row.selectFirst(".result__snippet");
    if (a != null) {
        String href = cleanDdgRedirect(a.attr("href"));
        results.add(new SearchResult(a.text(), s == null ? "" : s.text(), href));
    }
    if (results.size() >= limit) break;
}
return results;
```

**Guidance:** Constructor takes `SafeHttpFetcher`. jsoup classes from the relocated package `com.forgebook.shadow.jsoup.*` (D-08, Phase 1). `cleanDdgRedirect(href)` is a private static helper that URL-decodes the `uddg` query param — see RESEARCH §3.1 gotcha.

---

### `com.forgebook.integration.websearch.BraveSearchAdapter` (adapter, HTTP+JSON)

**Analog:** `provider/ClaudeProvider` (raw HttpClient + Gson) AND CurseForgeClient (header `X-Subscription-Token` instead of `x-api-key`).

**Guidance:** Same raw `HttpClient` shape as `ClaudeProvider` because Brave returns `application/json` (not allowlisted in SafeHttpFetcher). Auth header is `X-Subscription-Token: {webSearchApiKey.raw()}` (per RESEARCH §3.2). On 401/network-fail, throw `ToolException(FETCH_FAILED, ...)` so the agent gets a structured tool error — D-12.

---

### `com.forgebook.integration.scraper.ModDocsScraper` (utility, transform)

**Analog:** `util/Cidr.java` (stateless static helper).

**Pattern — pure static method per RESEARCH §4.3:**
```java
public final class ModDocsScraper {
    private static final List<String> READABILITY_SELECTORS = List.of(
        "article", "main", "div.project-description", "div#mw-content-text",
        "div#wiki-body", "div.markdown-body", "div.content", "body");
    private ModDocsScraper() {}
    public static String extractReadable(String html, String sourceUrl) {
        Document doc = Jsoup.parse(html);
        denoise(doc);
        for (String sel : READABILITY_SELECTORS) {
            Element e = doc.selectFirst(sel);
            if (e != null && e.text().length() > 200) return e.text();
        }
        return doc.body() == null ? "" : doc.body().text();
    }
    private static void denoise(Document doc) {
        doc.select("nav, footer, aside, script, style, noscript, " +
                   ".sidebar, .navigation, .advertisement, .ad, .cookie-banner, " +
                   "form, header[role=banner]").remove();
    }
}
```

**Guidance:** No state. Constants list lives at the top of the class (matches `SafeHttpFetcher.CONTENT_ALLOWLIST` style on line 34-35). Optional ninth fallback "largest-text div" per RESEARCH §4.4.

---

### `com.forgebook.integration.scraper.PromptFraming` (utility, transform)

**Analog:** `util/Cidr.java`.

**Pattern (RESEARCH §8.3, applies the nonce framing):**
```java
public final class PromptFraming {
    public static final int TOOL_OUTPUT_CAP = 8_000;   // D-14, D-15
    private PromptFraming() {}
    public static String wrap(String text, String sourceUrl) {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String truncated = truncate(text, sourceUrl);
        return "<mod_doc trust=\"untrusted\" source=\"" + sourceUrl + "\" tag=\"" + nonce + "\">\n"
             + truncated
             + "\n</mod_doc tag=\"" + nonce + "\">";
    }
    static String truncate(String out, String sourceUrl) {
        if (out.length() <= TOOL_OUTPUT_CAP) return out;
        return out.substring(0, TOOL_OUTPUT_CAP)
             + "\n[... truncated at 8,000 chars — full document at " + sourceUrl + "]";
    }
}
```

---

### `com.forgebook.config.WebSearchProviderKind` (enum)

**Analog:** `config/AiProviderKind.java` — exact pattern.

**Pattern (AiProviderKind.java lines 7-11):**
```java
public enum AiProviderKind { ANTHROPIC, OPENAI, OLLAMA }
```

**Apply:**
```java
public enum WebSearchProviderKind { DUCKDUCKGO, BRAVE }
```

---

### MODIFIED: `config/ForgebookServerConfig.java`

**Analog (the file itself):** match the existing `b.comment(...).push(...)` builder idiom from lines 36-68.

**Pattern excerpt (ForgebookServerConfig.java lines 40-46):**
```java
AI_PROVIDER = b.comment("AI provider. One of ANTHROPIC, OPENAI, OLLAMA. Phase 1 ships no provider impl.")
               .defineEnum("ai_provider", AiProviderKind.ANTHROPIC);
AI_API_KEY  = b.comment("API key for the selected provider. Redacted in logs.")
               .define("ai_api_key", "");
AI_MODEL    = b.comment("Model ID to send to the provider. Provider-specific.")
               .define("ai_model", "claude-haiku-4");
```

**New entries to add (under the existing `"ai"` group for `MAX_TOKENS`, new `"websearch"` group for the two web-search fields):**
```java
MAX_TOKENS = b.comment("Max tokens the AI provider may generate per request. Lower = cheaper + faster; higher = more detailed.")
              .defineInRange("max_tokens", 1024, 128, 8192);

// Update AI_MODEL default:
AI_MODEL = b.comment("Model ID to send to the provider. Provider-specific.")
            .define("ai_model", "claude-haiku-4-5");   // was "claude-haiku-4"

b.pop().push("websearch");
WEB_SEARCH_PROVIDER = b.comment("Web search backend. DUCKDUCKGO requires no API key. BRAVE requires web_search_api_key.")
                       .defineEnum("web_search_provider", WebSearchProviderKind.DUCKDUCKGO);
WEB_SEARCH_API_KEY  = b.comment("API key for Brave Search (required only when web_search_provider = BRAVE). Redacted in logs.")
                       .define("web_search_api_key", "");
b.pop();
```

**Guidance:** Same field-declaration order as constructor reading (`buildFromSpec` will mirror). Use `defineInRange` with min=128 max=8192 per RESEARCH §"New ConfigSpec Fields". WEB_SEARCH_API_KEY default `""` matches the `AI_API_KEY` precedent.

---

### MODIFIED: `config/ConfigSnapshot.java`

**Analog (the file itself):** record signature.

**Add 3 fields preserving the existing field order (group AI fields, then CurseForge, then access, then meta):**
```java
public record ConfigSnapshot(
    AiProviderKind aiProvider,
    ApiKey aiApiKey,
    String aiModel,
    int maxTokens,                           // NEW
    Optional<String> curseforgeModpackId,
    ApiKey curseforgeApiKey,
    boolean opOnly,
    int rateLimitPerMinute,
    boolean enableWebSearch,
    WebSearchProviderKind webSearchProvider, // NEW
    ApiKey webSearchApiKey,                  // NEW
    int configVersion
) {}
```

**Test impact:** `ConfigSnapshotTest.record_preservesAllNineFields` becomes `record_preservesAllTwelveFields` (extend the assertion list and the constructor call).

---

### MODIFIED: `config/ConfigHolder.java#buildFromSpec`

**Analog (the file itself):** lines 27-42.

**Pattern (extend existing builder call):**
```java
public static ConfigSnapshot buildFromSpec() {
    java.util.Optional<String> modpackId = java.util.Optional
        .ofNullable(ForgebookServerConfig.CURSEFORGE_MODPACK_ID.get())
        .filter(s -> !s.isBlank());
    return new ConfigSnapshot(
        ForgebookServerConfig.AI_PROVIDER.get(),
        new ApiKey(ForgebookServerConfig.AI_API_KEY.get()),
        ForgebookServerConfig.AI_MODEL.get(),
        ForgebookServerConfig.MAX_TOKENS.get(),                          // NEW
        modpackId,
        new ApiKey(ForgebookServerConfig.CURSEFORGE_API_KEY.get()),
        ForgebookServerConfig.OP_ONLY.get(),
        ForgebookServerConfig.RATE_LIMIT_PER_MINUTE.get(),
        ForgebookServerConfig.ENABLE_WEB_SEARCH.get(),
        ForgebookServerConfig.WEB_SEARCH_PROVIDER.get(),                 // NEW
        new ApiKey(ForgebookServerConfig.WEB_SEARCH_API_KEY.get()),      // NEW
        ForgebookServerConfig.CONFIG_VERSION.get()
    );
}
```

---

### MODIFIED: `network/handler/ChatRequestHandler.java`

**Analog (the file itself):** lines 96-128 — keep the entire executor-hop / RejectedExecutionException scaffolding, only swap the task body.

**Replace lines 99-114 (echo body) with:**
```java
AiExecutor.get().submit(() -> {
    try {
        AiDispatcher.Result result = AiDispatcher.INSTANCE.dispatch(pkt.message(), sender);
        enqueueWork.accept(() -> {
            Object out = (result instanceof AiDispatcher.Reply r)
                ? new ChatResponsePacket(pkt.requestId(), r.text())
                : new ChatErrorPacket(pkt.requestId(),
                                      ((AiDispatcher.Error) result).code(),
                                      ((AiDispatcher.Error) result).humanReadable());
            Consumer<Object> sink = responseSinkForTests;
            if (sink != null) sink.accept(out); else responder.accept(out);
        });
    } catch (Exception ex) {
        LOG.error("Dispatch failed for {}", sender.getUUID(), ex);
        enqueueWork.accept(() -> {
            ChatErrorPacket err = new ChatErrorPacket(
                pkt.requestId(), ErrorCode.PROVIDER, "Internal error.");
            Consumer<Object> sink = responseSinkForTests;
            if (sink != null) sink.accept(err); else responder.accept(err);
        });
    }
});
```

**Guidance:** `responseSinkForTests` and `handleForTest` signature stay untouched. The `RejectedExecutionException` outer catch (lines 116-128) stays as-is — D-20 OVERLOADED translation. Phase 1 `ChatEchoGameTest` will need to be replaced/extended with a Phase 2 test that injects a scripted `AiDispatcher`.

---

### MODIFIED: `ForgeBookMod.java`

**Analog (the file itself):** lines 58-70 — existing `MinecraftForge.EVENT_BUS.addListener(...)` pattern.

**Pattern excerpt (ForgeBookMod.java lines 59-70):**
```java
MinecraftForge.EVENT_BUS.addListener(
    (net.minecraftforge.event.server.ServerStartingEvent e) ->
        com.forgebook.config.ConfigHolder.set(
            com.forgebook.config.ConfigHolder.buildFromSpec()));
MinecraftForge.EVENT_BUS.addListener(
    (net.minecraftforge.event.server.ServerStartingEvent e) ->
        com.forgebook.util.AiExecutor.start());
MinecraftForge.EVENT_BUS.addListener(com.forgebook.util.AiExecutor::onServerStopping);
```

**Add new ServerStartedEvent listener using identical idiom:**
```java
MinecraftForge.EVENT_BUS.addListener(
    (net.minecraftforge.event.server.ServerStartedEvent e) ->
        com.forgebook.ai.SystemPromptBuilder.buildAndCache(e.getServer()));
```

**Guidance:** Use ServerStartedEvent (NOT ServerStartingEvent) per RESEARCH §6.2 — it fires AFTER `AiExecutor.start()` so the CurseForge fetch can run on `AiExecutor`. The listener is fully-qualified inline (matches existing style — no imports added). `SystemPromptBuilder.buildAndCache` is the orchestration entry that internally calls `CurseForgeClient.fetch` + `ModList.get().getMods()` + `SystemPromptBuilder.build(...)` + `SystemPromptCache.set(...)` + `ToolRegistry.init(...)` per RESEARCH §6.3.

**Reload command wiring:** `ForgebookReloadCommand.onRegister` (line 58) currently only rebuilds `ConfigHolder`. Phase 2 should also re-run `SystemPromptBuilder.buildAndCache(server)` from inside the reload command's `.executes(ctx -> ...)` lambda — this is a tiny change to `ForgebookReloadCommand.java` (already analog: same `ConfigHolder.set(buildFromSpec())` pattern, just add a sibling line). Treat as a Phase 2 modification of `ForgebookReloadCommand.java` (effectively a sixth modified file — flag for the planner to confirm scope).

---

## Shared Patterns

### Pattern: Off-tick HTTP via AiExecutor (D-19, D-20)

**Source:** `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java` lines 97-128 + `src/main/java/com/forgebook/util/AiExecutor.java` lines 30-37.

**Apply to:** `AiDispatcher`, `ClaudeProvider`, `AgentLoop` (parallel sub-tasks), `CurseForgeClient` (called via `CompletableFuture.supplyAsync(..., AiExecutor.get())` from the `ServerStartedEvent` listener), `FetchModDocsPageTool`, `WebSearchTool`, both web-search adapters.

**Excerpt:**
```java
AiExecutor.get().submit(() -> { /* HTTP work here */ });
// final game-state mutation:
ctx.enqueueWork(() -> responder.accept(packet));
```

**Anti-pattern (Phase 1 Pitfall 3, must NOT happen anywhere in Phase 2):** never put `AiExecutor.get().submit(...)` *inside* `ctx.enqueueWork(...)` — that freezes the server tick. The server-thread `enqueueWork` should ONLY contain the `responder.accept(...)` send.

---

### Pattern: Single-read config snapshot (D-14)

**Source:** `src/main/java/com/forgebook/config/ConfigHolder.java` lines 14-26.

**Apply to:** `AiDispatcher.dispatch` (read once at entry), every `Tool.invoke(...)`, `ClaudeProvider` (per request), `CurseForgeClient.fetch`, `WebSearchTool.invoke`.

**Excerpt:**
```java
ConfigSnapshot snap = ConfigHolder.get();   // single volatile load
// ... use snap.aiModel(), snap.maxTokens(), snap.aiApiKey().raw(), etc.
// DO NOT re-call ConfigHolder.get() further down the stack — pass `snap` as a param.
```

---

### Pattern: Typed errors with enum Reason (Phase 1 D-13 idiom)

**Source:** `src/main/java/com/forgebook/util/UnsafeUrlException.java` lines 10-34.

**Apply to:** `ToolException` (mirror `Reason`), `AiTurn.ProviderError.Kind` (sealed-record analog), error-code mapping inside `ClaudeProvider`, `BraveSearchAdapter`'s exception translation.

**Excerpt:**
```java
public final class UnsafeUrlException extends Exception {
    public enum Reason { SCHEME, PRIVATE_IP, REDIRECT_LIMIT, SIZE_CAP, CONTENT_TYPE, TIMEOUT }
    private final Reason reason;
    public UnsafeUrlException(Reason reason) {
        super("Unsafe URL: " + reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
```

**Why:** Phase 1 unit tests assert on `.reason()` (one test per enum value, per Phase 1 D-24). Phase 2 tests follow the same pattern for `ToolException` and parameterized `ProviderError.Kind` mapping tests.

---

### Pattern: API-key access restricted to ai/integration packages

**Source:** `src/main/java/com/forgebook/config/ApiKey.java` lines 18-43 + Phase 1 CI grep-lint.

**Apply to:** ONLY `com.forgebook.ai.provider.ClaudeProvider`, `com.forgebook.integration.CurseForgeClient`, `com.forgebook.integration.websearch.BraveSearchAdapter` may call `.raw()`. Any other call site fails CI.

**Excerpt (ApiKey.java):**
```java
public String raw() { return raw; }
@Override public String toString() { return "<redacted>"; }
```

**Pattern at call sites (both packages allowed):**
```java
HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
    .header("x-api-key", snap.aiApiKey().raw())   // OK in com.forgebook.ai.*
    .build();
```

**Log scrubber follow-up:** Phase 1's Log4j2 scrubber covers `x-api-key` and `sk-ant-*`. Brave uses `X-Subscription-Token` — extend the filter (RESEARCH §"New ConfigSpec Fields" log-scrubber note). Treat as a Phase 1 follow-up flagged in PATTERNS.

---

### Pattern: Static-only utility class (private constructor + final + LOG)

**Source:** Every util class — `AiExecutor.java` line 23-29, `ConfigHolder.java` lines 14-17, `ForgebookNetwork.java` lines 27-41.

**Apply to:** `SystemPromptCache`, `ModpackContextCache`, `ToolRegistry`, `RetryPolicy` (with `record DEFAULT`), `PromptFraming`, `ModDocsScraper`, `ForgebookReloadCommand` (already exists in Phase 1 — same shape).

**Excerpt:**
```java
public final class AiExecutor {
    private static final Logger LOG = LogManager.getLogger();
    private static volatile ThreadPoolExecutor INSTANCE;
    private AiExecutor() {}
    public static ExecutorService get() { ... }
}
```

**Logger import (every file):**
```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
private static final Logger LOG = LogManager.getLogger();
```

---

### Pattern: Test seam via package-private constructor + interface

**Source:** `src/main/java/com/forgebook/util/SafeHttpFetcher.java` lines 39-51.

**Apply to:** `ClaudeProvider` (inject `HttpExecutor`), `AgentLoop` (inject `AiProvider`), `FetchModDocsPageTool` (inject `SafeHttpFetcher`), `CircuitBreaker` (inject `Clock` for cool-off tests per RESEARCH §9 SC-2 row "Breaker cool-off").

**Excerpt:**
```java
private final Predicate<InetAddress> cidrCheck;
/** Production constructor — uses Cidr::isBlocked as the CIDR gate. */
public SafeHttpFetcher() { this(Cidr::isBlocked); }
/** Package-private test-only override — production code MUST use the no-arg constructor. */
SafeHttpFetcher(Predicate<InetAddress> cidrCheck) { this.cidrCheck = cidrCheck; }
```

**Why:** RESEARCH §9 mandates "no Mockito" — every Phase 2 testable seam follows this exact "public no-arg + package-private injectable" duo.

---

### Pattern: Test sink for outbound packets

**Source:** `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java` lines 50-79 (responseSinkForTests + handleForTest).

**Apply to:** Phase 2's GameTest (if added per RESEARCH §9.5) — keep the existing sink; only the upstream task changes. The Phase 2 `ChatDispatchGameTest` (if created) replaces `ChatEchoGameTest` and injects a `ScriptedAiProvider` (RESEARCH §9.2) into `AiDispatcher`.

---

### Pattern: Test file co-location

**Source:** Phase 1 tests under `src/test/java/com/forgebook/{util,config,network,gametest}/` mirror the production package structure.

**Apply to:** Phase 2 adds:
- `src/test/java/com/forgebook/ai/` — `ClaudeProviderTest`, `AgentLoopTest`, `CircuitBreakerTest`, `RetryPolicyTest`, `SystemPromptBuilderTest`, `AiTurnTest`
- `src/test/java/com/forgebook/tool/` — `ToolRegistryTest`, `ListInstalledModsToolTest`, `FetchModDocsPageToolTest`, `WebSearchToolTest`, `GetModpackContextToolTest`
- `src/test/java/com/forgebook/integration/` — `CurseForgeClientTest`, `DuckDuckGoHtmlAdapterTest`, `BraveSearchAdapterTest`, `ModDocsScraperTest`, `PromptFramingTest`
- `src/test/resources/forgebook/phase2/` — fixture HTML / JSON per RESEARCH §9 "Fixture directory"

---

## No Analog Found

Files with no close in-codebase match (planner should rely on RESEARCH.md sections and external docs):

| File | Role | Reason | Fallback Reference |
|---|---|---|---|
| `AiProvider.java` | interface | First explicit pluggable interface in the codebase | RESEARCH §1 (Anthropic API shape) — defines the contract |
| `Tool.java` | interface | First tool abstraction | RESEARCH §1 (Anthropic input_schema shape) |
| `WebSearchAdapter.java` | interface | First adapter abstraction | RESEARCH §3.4 (interface shape provided) |
| `dto/*` Gson DTOs | data carriers | First Gson DTOs (Phase 1 packets use Forge `FriendlyByteBuf`, not Gson) | RESEARCH §1.2/§1.3 (full JSON shape); CLAUDE.md §e (DTO field list) |

**Note:** "No analog" is structural only — the *style* (immutable record, package-private constructors, `Logger` field, etc.) still copies from `ConfigSnapshot`/`AiExecutor`/`UnsafeUrlException` per the Shared Patterns section.

---

## Metadata

**Analog search scope:**
- `src/main/java/com/forgebook/util/` (4 files)
- `src/main/java/com/forgebook/config/` (6 files)
- `src/main/java/com/forgebook/network/` (5 files including `handler/` and `packet/` subdirs)
- `src/main/java/com/forgebook/command/` (1 file)
- `src/main/java/com/forgebook/ForgeBookMod.java`
- `src/test/java/com/forgebook/` (cross-checked test-style patterns)

**Files scanned:** 17 production Java files + selected test files.

**Phase-1 → Phase-2 cross-references:**
- D-13 (Phase 1) typed-exception idiom → `ToolException`, `AiTurn.ProviderError.Kind`
- D-14 (Phase 1) snapshot-config idiom → every Phase 2 service entry point
- D-19/D-20 (Phase 1) executor-hop + bounded queue → `AiDispatcher`, `AgentLoop`, all tools
- D-22 — D-26 (Phase 1) SafeHttpFetcher rules → `FetchModDocsPageTool`, `WebSearchTool`/DDG adapter, **NOT** `ClaudeProvider` or `BraveSearchAdapter` (JSON content-type — use raw HttpClient)
- D-05 (Phase 1) package layout → confirms `com.forgebook.{ai,tool,integration}` are net-new top-level packages reserved by Phase 1

**Pattern extraction date:** 2026-04-15

---

## PATTERN MAPPING COMPLETE
