# Phase 1: Foundations & Safe Egress - Context

**Gathered:** 2026-04-15
**Status:** Ready for planning

<domain>
## Phase Boundary

Deliver a loadable Forge 1.20.1-47.4.18 mod on both client and dedicated server, with every CRITICAL-pitfall guardrail locked down **before any AI code lands**:

1. Scaffold: MDK-based project that builds clean on Java 17 with the client classloader firewall in place.
2. Config: SERVER-tier secrets + CLIENT-tier UI toggle via `ForgeConfigSpec`, with a redacting `ApiKey` wrapper, an immutable `ConfigSnapshot`, a log scrubber, and `/forgebook reload`.
3. Networking: `SimpleChannel "forgebook:main"` carrying `ChatRequestPacket` / `ChatResponsePacket` / `ChatErrorPacket` with off-tick HTTP dispatch via a dedicated `aiExecutor`.
4. Safe egress: `SafeHttpFetcher` as the single outbound HTTP chokepoint enforcing scheme, IP, redirect, size, content-type, and timeout rules.

Out of scope this phase: any `AiProvider` implementation, any tool execution, any command beyond `/forgebook reload`, any chat UI, any rate limiting.

</domain>

<decisions>
## Implementation Decisions

### Scaffold & Build System
- **D-01:** MDK bootstrap is a Claude-driven plan task — download `forge-1.20.1-47.4.18-mdk.zip` from `maven.minecraftforge.net`, extract into repo root, strip the example-mod sources (`com.example.examplemod.*`), delete MDK-only docs (`README.txt`, `CREDITS.txt`, `LICENSE.txt` — we ship our own MIT LICENSE), and commit the bootstrap as an atomic task. Reproducible: anyone regenerating the project gets identical bootstrap.
- **D-02:** Forge pin stays at `1.20.1-47.4.18` per PROJECT.md / ROADMAP.md. Not switching to the 47.4.10 "Recommended" build.
- **D-03:** Parchment mappings pinned to `2023.09.03-1.20.1` via the `org.parchmentmc.librarian.forgegradle` plugin. `mappings channel: 'parchment'`.
- **D-04:** Use the MDK-shipped Gradle wrapper (8.1.1). Do not bump past 8.3.x (FG6 compatibility).
- **D-05:** Package root is `com.forgebook`. Subpackages: `client` (client-dist only), `config`, `network`, `util`. `ai`, `command`, `integration` are reserved for later phases but not created in this phase.

### jsoup Bundling (Phase-1 De-Risk)
- **D-06:** Bundle jsoup in Phase 1 even though it is not called until Phase 2. Rationale: the shadow/jarJar/reobf interaction is the riskiest Gradle surprise in the roadmap; getting it green while the project is small is cheap insurance.
- **D-07:** Use Forge `jarJar` (not the `shadow` plugin) for nested-jar delivery. CLAUDE.md's "What NOT to Use" section flags shadow as risky (can duplicate Forge classes into the output jar). **SCAF-05 carries a spec drift** (it says "Gradle shadow relocates jsoup"); the plan should reflect jarJar and REQUIREMENTS.md SCAF-05 wording will be corrected at phase completion.
- **D-08:** Still relocate jsoup to `com.forgebook.shadow.jsoup` — jarJar handles *nesting*, not *package renaming*. The planner picks the minimum-complexity relocation path: either a one-off relocation Gradle task (recommended) or a hybrid where the shadow plugin is used only to produce a relocated intermediate jar that jarJar then embeds. Planner to flag the chosen path.
- **D-09:** Pin jsoup to a specific stable release (planner picks the exact patch version from the current `1.17.x` line at planning time — e.g., `1.17.2`). Declared in `gradle.properties`.

### Client Classloader Firewall
- **D-10:** The *only* package allowed to import `net.minecraft.client.*` is `com.forgebook.client`. Enforced by: (a) a README note, (b) a CI lint (a small gradle task that greps `import net.minecraft.client.` outside `src/main/java/com/forgebook/client/`), (c) every client-entry path going through `DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)`.
- **D-11:** SCAF-07's CI smoke test runs `./gradlew runGameTestServer` (or a dedicated headless `runServer` invocation) and greps for `NoClassDefFoundError` from `net.minecraft.client.*`. Fails the build on hit.

### Config & Secrets
- **D-12:** Two `ForgeConfigSpec` instances: a SERVER spec (every AI/CurseForge/op/rate/enable_web_search field) and a CLIENT spec (`enable_chat_interface` only). Both registered in the `@Mod` constructor via `ModLoadingContext.get().registerConfig(...)`.
- **D-13:** `ApiKey` is a value record wrapping the raw string. `toString()` returns `"<redacted>"`. Raw value is reachable only via an explicit `raw()` method; the only call sites permitted to invoke `raw()` are HTTP adapters (Claude/OpenAI/Ollama when implemented). A simple grep-based CI check flags any other caller.
- **D-14:** `ConfigSnapshot` is an immutable record published via `volatile` reference in a static holder. Reload builds a new snapshot and swaps it in a single assignment — in-flight requests see a consistent view because each request reads the reference once at entry.
- **D-15:** `/forgebook reload` is the **only** reload trigger. `ModConfigEvent.Reloading` (file-watch) is deliberately NOT wired in Phase 1 — operators opt in explicitly via the command. Reduces surprise reloads mid-request and keeps a single audited reload path. (Revisitable in v2 if operators ask for it.)
- **D-16:** Log scrubber is implemented as a **Log4j2 filter plugin** registered globally in `log4j2.xml`. Scrubs: `Authorization` header values, `x-api-key` header values, any substring matching `sk-ant-[A-Za-z0-9_-]+` or `sk-proj-[A-Za-z0-9_-]+`, and any `api_key=…` query param. Invasive by design — catches logs from libraries we don't control (HttpClient, jsoup).

### Networking
- **D-17:** `SimpleChannel "forgebook:main"` registered via `NetworkRegistry.newSimpleChannel(...)` — NOT the `ChannelBuilder` fluent API (that is NeoForge / 1.20.2+). Protocol version string: `"1"` for v1; bump on breaking packet changes.
- **D-18:** Three packet types registered in Phase 1: `ChatRequestPacket` (C→S), `ChatResponsePacket` (S→C), `ChatErrorPacket` (S→C). Encode/decode via `FriendlyByteBuf`. Payload chunking (NET-04) implemented as a helper in `com.forgebook.network` that splits >32 KB payloads across multiple wire packets with a re-assembly marker.
- **D-19:** Packet handlers follow the canonical pattern: `context.enqueueWork(...)` wraps only the final game-state mutation; any HTTP / provider call must run on `aiExecutor` first, then hop back via `enqueueWork`. Phase 1 includes an **echo handler** on the server (`ChatRequestPacket → ChatResponsePacket`) that demonstrates the executor hop without calling any AI provider — this is the NET-06 E2E test target.
- **D-20:** `aiExecutor` is a `ThreadPoolExecutor` with **fixed 4 threads**, **bounded `ArrayBlockingQueue(64)`**, threads named `forgebook-ai-N` (daemon = false so a JVM shutdown waits for in-flight work up to a timeout). Rejection policy: on queue overflow, the handler sends a `ChatErrorPacket` with code `OVERLOADED` back to the client. Shutdown sequence: registered on `ServerStoppingEvent`, calls `shutdown()` + awaitTermination(5s) + `shutdownNow()`.
- **D-21:** Phase 1 executor shutdown test: unit test that submits N > queue-capacity tasks and asserts RejectedExecutionException is translated to an OVERLOADED ChatErrorPacket.

### SafeHttpFetcher (Egress Guardrail)
- **D-22:** **Resolve-and-pin per request**: SafeHttpFetcher resolves the hostname → `InetAddress`, checks against the hard-coded CIDR blocklist, then opens the connection to the **pinned IP** while setting the `Host` header to the original hostname. Immune to DNS rebinding: the attacker cannot re-resolve the name to a private IP between our check and our connect. Requires custom plumbing around `java.net.http.HttpClient` — either a custom `ProxySelector` + `SocketFactory`, or dropping to `HttpURLConnection` for the fetcher's final hop. Planner to research the cleanest JDK-17 path and flag the chosen approach.
- **D-23:** **Manual redirect loop** in SafeHttpFetcher: `HttpClient.Redirect.NEVER`, we iterate up to 3 hops and re-validate scheme / resolved-IP / content-length on each hop. Gives us the explicit NET-05 enforcement point and exact hop counting.
- **D-24:** Failures surface as a typed `UnsafeUrlException` carrying an enum `Reason`: `SCHEME`, `PRIVATE_IP`, `REDIRECT_LIMIT`, `SIZE_CAP`, `CONTENT_TYPE`, `TIMEOUT`. Unit tests in NET-05 assert on the enum value — one test per reason, matching the Success Criteria #4 language.
- **D-25:** CIDR blocklist is **hard-coded** in SafeHttpFetcher as constants: `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `0.0.0.0/8`, `::1/128`, `fc00::/7`, `fe80::/10`. Operators cannot weaken the list via config (defense-in-depth stance). Can be extended in v2 if needed.
- **D-26:** Content-type allowlist: `text/html`, `text/plain`, `application/xhtml+xml` only. Size cap: 1 MB (1,048,576 bytes), enforced by counting bytes on the streaming read — we do NOT rely on `Content-Length` header. Timeout: 15 s total (connect + read), configurable via constant but not exposed to operator config in Phase 1.

### Testing Strategy (Phase 1)
- **D-27:** JUnit 5 for pure-Java units: `ApiKey` redaction, `ConfigSnapshot` immutability, CIDR parser, `SafeHttpFetcher` rule enforcement (one test per `UnsafeUrlException.Reason`), executor rejection translation.
- **D-28:** Forge `GameTest` for the NET-06 E2E packet echo — spin up a dedicated headless server via `./gradlew runGameTestServer`, send `ChatRequestPacket` from a fake client, assert `ChatResponsePacket` comes back.
- **D-29:** CI (planner picks provider — default GitHub Actions unless the user changes it) runs: `./gradlew build` + `./gradlew runGameTestServer --tests "com.forgebook.*"` on Linux + Java 17. Also runs the classloader-firewall grep and the `ApiKey.raw()` caller grep.

### Claude's Discretion
- Exact relocation task implementation (Gradle custom task vs shadow intermediate) — planner picks and flags.
- Specific jsoup patch version (1.17.x line) — planner picks at planning time.
- CI provider (default GitHub Actions) and workflow YAML structure — planner picks.
- Java package organization under `com.forgebook.network` (e.g., `.packet` / `.handler` subpackages) — planner picks using common Forge conventions.
- `log4j2.xml` filter plugin class location and registration syntax — planner picks.
- Exact thread-naming pattern and daemon flag details — planner picks, consistent with D-20 intent.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap & Requirements
- `.planning/ROADMAP.md` — Phase 1 section, goal, success criteria (1–4), requirement IDs
- `.planning/REQUIREMENTS.md` — SCAF-01…08, CFG-01…07, NET-01…06 (the 21 requirements this phase delivers)
- `.planning/PROJECT.md` — Constraints (Tech stack, Secrets, Cost, Asset sourcing, Licensing), Key Decisions table
- `.planning/STATE.md` — Architecture Invariants section (client classloader firewall, SERVER-tier secrets, off-tick HTTP, SafeHttpFetcher rules), Research Flags for Phase 1

### Project-Level Conventions
- `CLAUDE.md` — "Technology Stack", "Per-Feature Stack Decisions", "What NOT to Use", "Version Compatibility", "Sources" sections. Especially: jarJar vs shadow guidance, `NetworkRegistry.newSimpleChannel` vs `ChannelBuilder`, `IModInfo.getModURL()` (not `getDisplayURL()`).

### Upstream Research Outputs
- `.planning/research/` — domain ecosystem research from project initialization (any files present; planner to cross-reference)

### External Specs (Forge / ParchmentMC)
- Forge MDK download: `https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.18/forge-1.20.1-47.4.18-mdk.zip` (referenced during D-01 bootstrap task)
- Forge docs: `https://docs.minecraftforge.net/en/1.20.1/` — getting started, mod files, config (CLIENT/SERVER/COMMON tiers), jarJar, SimpleImpl (SimpleChannel)
- ParchmentMC docs: `https://parchmentmc.org/docs/getting-started` — plugin setup for the mappings date string chosen in D-03

Note: External specs are listed by URL rather than local path because this is a greenfield phase — no ADRs exist yet in the repo.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None. Repo contains only `.planning/`, `.git/`, and `CLAUDE.md`. This is a true greenfield phase; every file under `src/` is new.

### Established Patterns
- None in-repo yet. Patterns are borrowed from CLAUDE.md (technology choices, anti-patterns, version compatibility) and from the Forge 1.20.1 MDK conventions.

### Integration Points
- `src/main/java/com/forgebook/` — root for all mod code (to be created).
- `src/main/resources/META-INF/mods.toml` — mod manifest (new).
- `src/main/resources/pack.mcmeta` — resource-pack metadata (new).
- `src/main/resources/log4j2.xml` — where the log scrubber filter plugin registers (new).
- `config/forgebook-server.toml`, `config/forgebook-client.toml` — materialized on first server/client launch; Phase 1 delivers the spec, not example values.

</code_context>

<specifics>
## Specific Ideas

- Phase 1 includes a **server-side echo handler** for `ChatRequestPacket → ChatResponsePacket` (no AI call). This is the concrete NET-06 test artifact — it proves the full packet round-trip and the `aiExecutor → enqueueWork` hop without introducing the Phase 2 provider surface.
- Success Criterion #4 enumerates six rejection conditions; the plan should have exactly six `UnsafeUrlException.Reason` enum values and exactly six unit-test methods mapping 1:1 to the criterion's rules.
- CI smoke test's value is catching `NoClassDefFoundError` from a client-only class leaking into common code. The test must explicitly check the headless-server log for that specific error string, not rely on exit code alone (dedicated servers can exit cleanly even with class-load errors during startup).
- The log scrubber's regex set must include `sk-ant-` AND `sk-proj-` prefixes even though Phase 1 ships no Anthropic adapter yet — the infrastructure must be in place before any key can be written to a log line in Phase 2.

</specifics>

<deferred>
## Deferred Ideas

- **File-watch config reload** (auto-reload on `forgebook-server.toml` edits via `ModConfigEvent.Reloading`) — deferred; may land in a v2 quality-of-life phase if operators request it.
- **Operator-extensible IP blocklist** — deferred to v2; v1 keeps the CIDR list hard-coded for defense-in-depth.
- **Per-server daily token cap** — already v2 per REQUIREMENTS.md (V2-SAFE-01).
- **Streaming responses** — already v2 per REQUIREMENTS.md (V2-UX-01).
- **Multiple concurrent AI providers / per-request provider selection** — out of scope; v1 is single-provider-per-server.
- **Gradle 8.3+ / Gradle 8.4+** — stay on MDK-shipped 8.1.1 for Phase 1; revisit after a Forge-side upgrade lands.

</deferred>

---

*Phase: 01-foundations-safe-egress*
*Context gathered: 2026-04-15*
