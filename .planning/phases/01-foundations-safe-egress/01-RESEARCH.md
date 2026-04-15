# Phase 1: Foundations & Safe Egress - Research

**Researched:** 2026-04-15
**Domain:** Minecraft Forge 1.20.1 mod scaffold, config/secret handling, Forge networking, SSRF-safe HTTP egress
**Confidence:** HIGH on scaffold/networking/config; MEDIUM on Log4j2 plugin and GameTest packet-test ergonomics; MEDIUM on the SafeHttpFetcher SNI workaround (known JDK bug chain, well-documented workaround)

## Summary

Phase 1 is a greenfield Forge 1.20.1-47.4.18 mod bootstrap plus four locked subsystems: an MDK-seeded Gradle project, a dual-tier `ForgeConfigSpec` with a redacting `ApiKey` wrapper and a Log4j2 scrubber, a `SimpleChannel`/`aiExecutor` packet pipeline with an echo handler, and a `SafeHttpFetcher` that resolve-and-pins against DNS rebinding. CONTEXT.md already locks 29 implementation decisions, so this research focuses exclusively on the mechanics the planner must spell out: exact Gradle DSL, Log4j2 plugin skeleton, `NetworkRegistry.newSimpleChannel` boilerplate, and the (non-obvious) HttpURLConnection + SNI workaround required for IP-pinning with hostname-based TLS.

The single highest-risk area is **D-22 (resolve-and-pin)**: `java.net.http.HttpClient` cannot pin IPs without defeating SNI, and `HttpsURLConnection` + custom `HostnameVerifier` historically disables SNI as well (JDK-8144566). The only robust JDK-17 path is a custom `SSLSocketFactory` that explicitly sets `SNIHostName` on the `SSLParameters` of each opened socket, combined with a per-request `HostnameVerifier` that validates the cert's SAN set against the original hostname (not the pinned IP). This belongs in one narrow utility class and is the most test-worthy surface in the phase.

**Primary recommendation:** Bootstrap from the MDK zip with the plugin DSL `id 'net.minecraftforge.gradle' version '[6.0,6.2)'`; use Forge `jarJar` (not shadow) with `jarJar.ranged(...)` for jsoup 1.17.2 nesting, and a small Gradle `Jar`-subclass task for relocation; register two `ForgeConfigSpec`s in the `@Mod` constructor via `ModLoadingContext.get().registerConfig(...)`; register a Log4j2 filter plugin via `packages="com.forgebook.util.log"` in `log4j2.xml`; use `NetworkRegistry.newSimpleChannel` with `consumerMainThread` for the echo handler's final enqueue and a manual `aiExecutor.submit(...)` hop for the (future) HTTP work; implement `SafeHttpFetcher` on `HttpsURLConnection` with an SNI-setting `SSLSocketFactory` and a custom `HostnameVerifier` that matches cert against the **original** hostname.

## User Constraints (from CONTEXT.md)

### Locked Decisions

**Scaffold & Build System**
- **D-01:** MDK bootstrap is a Claude-driven plan task — download `forge-1.20.1-47.4.18-mdk.zip` from `maven.minecraftforge.net`, extract into repo root, strip the example-mod sources (`com.example.examplemod.*`), delete MDK-only docs (`README.txt`, `CREDITS.txt`, `LICENSE.txt` — we ship our own MIT LICENSE), and commit the bootstrap as an atomic task.
- **D-02:** Forge pin stays at `1.20.1-47.4.18`.
- **D-03:** Parchment mappings pinned to `2023.09.03-1.20.1` via the `org.parchmentmc.librarian.forgegradle` plugin. `mappings channel: 'parchment'`.
- **D-04:** Use the MDK-shipped Gradle wrapper (8.1.1). Do not bump past 8.3.x.
- **D-05:** Package root is `com.forgebook`. Subpackages this phase: `client`, `config`, `network`, `util`. (`ai`, `command`, `integration` reserved for later phases; not created in Phase 1.)

**jsoup Bundling (Phase-1 De-Risk)**
- **D-06:** Bundle jsoup in Phase 1 even though it is not called until Phase 2.
- **D-07:** Use Forge `jarJar` (not `shadow`) for nested-jar delivery. SCAF-05's wording will be corrected at phase completion.
- **D-08:** Still relocate jsoup to `com.forgebook.shadow.jsoup`; planner picks the minimum-complexity path (one-off relocation task **vs** shadow-intermediate + jarJar).
- **D-09:** Pin jsoup to a specific stable 1.17.x release (planner picks exact patch).

**Client Classloader Firewall**
- **D-10:** Only `com.forgebook.client` may import `net.minecraft.client.*`. Enforced by README note + CI grep lint + `DistExecutor.safeRunWhenOn`.
- **D-11:** SCAF-07 CI test runs headless `runGameTestServer` (or dedicated `runServer`) and greps for `NoClassDefFoundError` from `net.minecraft.client.*`. Fail build on hit.

**Config & Secrets**
- **D-12:** Two `ForgeConfigSpec` instances (SERVER + CLIENT); registered via `ModLoadingContext.get().registerConfig(...)`.
- **D-13:** `ApiKey` record with `toString() = "<redacted>"`; raw value only via explicit `raw()` method; grep-based CI check flags unauthorized callers.
- **D-14:** `ConfigSnapshot` immutable record published via `volatile` in a static holder. Reload = build new snapshot, single assignment.
- **D-15:** `/forgebook reload` is the **only** reload trigger. No `ModConfigEvent.Reloading` wiring in Phase 1.
- **D-16:** Log scrubber = Log4j2 filter plugin, globally registered in `log4j2.xml`. Scrubs `Authorization`, `x-api-key`, `sk-ant-[A-Za-z0-9_-]+`, `sk-proj-[A-Za-z0-9_-]+`, `api_key=…`.

**Networking**
- **D-17:** `SimpleChannel "forgebook:main"` via `NetworkRegistry.newSimpleChannel(...)` — NOT `ChannelBuilder`. Protocol version `"1"`.
- **D-18:** Three packet types: `ChatRequestPacket` (C→S), `ChatResponsePacket` (S→C), `ChatErrorPacket` (S→C). `FriendlyByteBuf` encode/decode. Chunking helper for >32 KB.
- **D-19:** Handler pattern: `context.enqueueWork(...)` wraps only final game-state mutation; HTTP/provider calls run on `aiExecutor` first. Phase 1 ships a server-side echo handler (`ChatRequestPacket → ChatResponsePacket`) demonstrating the hop.
- **D-20:** `aiExecutor` = `ThreadPoolExecutor`, **fixed 4 threads**, `ArrayBlockingQueue(64)`, threads named `forgebook-ai-N` (daemon = false). Rejection → `ChatErrorPacket` with code `OVERLOADED`. Shutdown on `ServerStoppingEvent`: `shutdown()` + `awaitTermination(5s)` + `shutdownNow()`.
- **D-21:** Executor shutdown test: submit N > queue-capacity, assert `RejectedExecutionException` translates to `OVERLOADED` `ChatErrorPacket`.

**SafeHttpFetcher (Egress Guardrail)**
- **D-22:** Resolve-and-pin per request. Planner picks the cleanest JDK-17 path (custom `ProxySelector`/`SocketFactory` around `HttpClient` **vs** drop to `HttpURLConnection` for the final hop).
- **D-23:** Manual redirect loop: `HttpClient.Redirect.NEVER`, iterate up to 3 hops, re-validate scheme/IP/content-length on each hop.
- **D-24:** Typed `UnsafeUrlException` with enum `Reason`: `SCHEME`, `PRIVATE_IP`, `REDIRECT_LIMIT`, `SIZE_CAP`, `CONTENT_TYPE`, `TIMEOUT`. One unit test per reason.
- **D-25:** CIDR blocklist hard-coded in code: `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `0.0.0.0/8`, `::1/128`, `fc00::/7`, `fe80::/10`. Operators cannot weaken via config.
- **D-26:** Content-type allowlist: `text/html`, `text/plain`, `application/xhtml+xml`. Size cap: 1 MB enforced on streaming read (not `Content-Length`). Timeout: 15 s total.

**Testing Strategy (Phase 1)**
- **D-27:** JUnit 5 for pure-Java units (ApiKey redaction, ConfigSnapshot immutability, CIDR parser, every `UnsafeUrlException.Reason`, executor rejection translation).
- **D-28:** Forge GameTest for NET-06 E2E packet echo via `./gradlew runGameTestServer`.
- **D-29:** CI: GitHub Actions (default), Ubuntu + Java 17, runs `./gradlew build` + `./gradlew runGameTestServer --tests "com.forgebook.*"` + classloader-firewall grep + `ApiKey.raw()` caller grep.

### Claude's Discretion

- Exact relocation task implementation (Gradle custom task vs shadow intermediate) — planner picks and flags.
- Specific jsoup 1.17.x patch version — planner picks.
- CI provider (default GitHub Actions) and workflow YAML structure — planner picks.
- Java package organization under `com.forgebook.network` (e.g., `.packet` / `.handler` subpackages) — planner picks.
- `log4j2.xml` filter plugin class location and registration syntax — planner picks.
- Exact thread-naming pattern and daemon flag details — planner picks, consistent with D-20 intent.

### Deferred Ideas (OUT OF SCOPE)

- File-watch config reload (`ModConfigEvent.Reloading`) — deferred to v2.
- Operator-extensible IP blocklist — deferred to v2.
- Per-server daily token cap — v2 (V2-SAFE-01).
- Streaming responses — v2 (V2-UX-01).
- Multiple concurrent AI providers / per-request provider selection — out of scope v1.
- Gradle 8.3+ / 8.4+ — stay on MDK-shipped 8.1.1.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SCAF-01 | Forge 1.20.1-47.4.18 mod builds on Java 17 + Gradle 8.1.1 + FG 6.0.x + Parchment 2023.09.03-1.20.1 | Section: MDK Bootstrap; Gradle Plugin DSL |
| SCAF-02 | Client classloader firewall (only `com.forgebook.client` imports `net.minecraft.client.*`) | Section: DistExecutor Safe Patterns; CI Firewall Lint |
| SCAF-03 | `mods.toml` fields (modId, license, displayURL, logoFile, authors) | Section: mods.toml Stanza |
| SCAF-04 | `@Mod` entry subscribes to common + mod buses; `DistExecutor.safeRunWhenOn` is the only client-entry path | Section: @Mod Bootstrap |
| SCAF-05 | jsoup relocated to `com.forgebook.shadow.jsoup` (via jarJar per D-07, not shadow as text says) | Section: jsoup Relocation via jarJar |
| SCAF-06 | `runClient` + `runServer` work from fresh checkout | Section: MDK Bootstrap (genIntellijRuns) |
| SCAF-07 | CI headless smoke test on dedicated server catches classloading leaks | Section: GitHub Actions Workflow |
| SCAF-08 | `LICENSE` (MIT) + `THIRD_PARTY_NOTICES.md` (jsoup attribution) at repo root | Section: Repo Files |
| CFG-01 | SERVER `ForgeConfigSpec` with 9 fields | Section: Dual ForgeConfigSpec Registration |
| CFG-02 | CLIENT `ForgeConfigSpec` with `enable_chat_interface` only | Section: Dual ForgeConfigSpec Registration |
| CFG-03 | `ApiKey` redacting value type | Section: ApiKey Record |
| CFG-04 | Immutable `ConfigSnapshot` with atomic swap | Section: ConfigSnapshot & Reload Path |
| CFG-05 | Log scrubber for `Authorization`/`x-api-key`/`sk-ant-`/`sk-proj-` | Section: Log4j2 Filter Plugin |
| CFG-06 | `.gitignore` excludes `run/`, `.gradle/`, `build/`, `*.toml.bak`, stray `forgebook-server.toml` | Section: Repo Files |
| CFG-07 | `/forgebook reload` (OP-only) triggers atomic reload | Section: /forgebook reload Command |
| NET-01 | `SimpleChannel "forgebook:main"` via `NetworkRegistry.newSimpleChannel` | Section: SimpleChannel Registration |
| NET-02 | Three packets with `FriendlyByteBuf` encode/decode | Section: Packet Shapes |
| NET-03 | Handlers `enqueueWork` before game-state; HTTP on `aiExecutor` | Section: aiExecutor Lifecycle |
| NET-04 | >32 KB payload chunking | Section: Packet Size Limits |
| NET-05 | SafeHttpFetcher: scheme allowlist, private-IP block, ≤3 redirects, 1 MB cap, content-type allowlist, 15 s timeout | Section: SafeHttpFetcher Resolve-and-Pin |
| NET-06 | E2E packet echo test passes in SP + MP | Section: GameTest for NET-06 |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|--------------|----------------|-----------|
| MDK scaffold & Gradle build | Build tool (ForgeGradle) | — | Build config is tool-tier, not runtime. |
| Mod entry + event bus wiring | Common (JVM both sides) | Client (via `DistExecutor.safeRunWhenOn`) | `@Mod` entry runs on both dist; only dist-gated dispatch touches client code. |
| `ApiKey` / `ConfigSnapshot` / secrets | Server (dedicated + integrated) | — | SERVER-tier fields never reach a remote client; CLIENT tier holds only one UI toggle. |
| `ForgeConfigSpec` registration | Common (@Mod constructor) | — | Registration is side-agnostic; the TOML materialization happens per-dist based on `ModConfig.Type`. |
| Log4j2 filter plugin | Common (JVM) | — | Log4j2 runs on both dist; the filter must apply everywhere a key string could appear. |
| `SimpleChannel` wiring | Common | Both client + server handlers | Channel object is created on common; handlers dispatch per direction. |
| `aiExecutor` + HTTP hop | Server only | — | All HTTP originates on the server process (dedicated or integrated). |
| `SafeHttpFetcher` | Server only | — | Outbound HTTP is a server-only concern. |
| `/forgebook reload` command | Server (Brigadier on common command bus, but OP-gated on server) | — | Brigadier registers in common; `hasPermission(2)` is a server-side check. |
| In-inventory button (Phase 4) | Client | — | Not in scope this phase, but client tier is reserved under `com.forgebook.client`. |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Minecraft Forge | `1.20.1-47.4.18` | Mod loader | Pinned by D-02 / PROJECT.md. `[CITED: files.minecraftforge.net]` |
| Java JDK | 17 (Temurin) | Language/runtime | Minecraft 1.20.1 requires exactly Java 17. `[CITED: docs.minecraftforge.net/en/1.20.1/gettingstarted/]` |
| Gradle | 8.1.1 (wrapper shipped in MDK) | Build tool | MDK-shipped; don't exceed 8.3.x due to FG6 issues. `[VERIFIED: CLAUDE.md]` |
| ForgeGradle | `[6.0,6.2)` range via plugin DSL | MC toolchain | Only supported FG line for 1.20.1. `[VERIFIED: forums.minecraftforge.net + discuss.gradle.org]` |
| Parchment mappings | `2023.09.03-1.20.1` | Param names + javadoc | Locked by D-03. Applied via `org.parchmentmc.librarian.forgegradle` plugin. `[CITED: parchmentmc.org]` |
| Gson | 2.10 (bundled with MC 1.20.1) | JSON ser/de | Already on classpath; declare nothing in `dependencies {}`. `[VERIFIED: CLAUDE.md]` |
| `java.net.http.HttpClient` | JDK 17 built-in | HTTP client for phase-later provider calls | Phase 1 uses `HttpsURLConnection` for `SafeHttpFetcher` instead — see Section 10. `[ASSUMED]` for phase-2 use. |
| jsoup | `1.17.2` (recommended pin) | HTML parser (Phase 2 uses; Phase 1 bundles for de-risk) | Latest 1.17.x patch as of research date. `[VERIFIED: mvnrepository.com + jsoup.org/news/release-1.17.2]` |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Forge `SimpleChannel` | Built-in (47.x) | Client↔server packets | Always. `NetworkRegistry.newSimpleChannel(...)`. |
| `ForgeConfigSpec` | Built-in (47.x) | TOML config (SERVER + CLIENT tiers) | Both specs registered in `@Mod` ctor. |
| Brigadier | Bundled with MC | Slash-command parsing | `/forgebook reload` only in Phase 1. |
| Forge GameTest | Built-in (47.x) | In-game integration tests via `runGameTestServer` | NET-06 E2E echo test. `[CITED: gist.github.com/SizableShrimp/60ad4109...]` |
| JUnit 5 (Jupiter) | `5.10.x` | Pure-Java unit tests | ApiKey, ConfigSnapshot, CIDR parser, `SafeHttpFetcher` rules, executor rejection. |
| Mockito | `5.x` | Mocking for JUnit | Only where pure-Java seams exist; avoid mocking MC classes. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `jarJar` | Gradle `shadow` plugin | CLAUDE.md "What NOT to Use" flags shadow risk (duplicating Forge classes); locked out by D-07. |
| `HttpsURLConnection` + custom `SSLSocketFactory` | `java.net.http.HttpClient` | `HttpClient` has no API to pin an IP while preserving SNI — see Section 10. |
| `ChannelBuilder` fluent API | `NetworkRegistry.newSimpleChannel` | Locked by D-17 (`ChannelBuilder` is NeoForge/1.20.2+). |
| Parchment | Official Mojang names only | Accept `p_60999_`-style param names. Locked by D-03. |

**Installation / bootstrap steps** (anchored to D-01):

```bash
# 1. Download MDK (Windows/cross-platform; Bash here uses curl)
curl -L -o forge-mdk.zip \
  https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.18/forge-1.20.1-47.4.18-mdk.zip
# 2. Extract into repo root (preserves .git/, .planning/, CLAUDE.md)
unzip forge-mdk.zip -d .
# 3. Remove example sources and MDK-only docs (D-01)
rm -rf src/main/java/com/example/
rm -f README.txt CREDITS.txt LICENSE.txt
# 4. Create our package root
mkdir -p src/main/java/com/forgebook/{client,config,network,util}
# 5. IntelliJ run configs
./gradlew genIntellijRuns
```

**Version verification (executed 2026-04-15):**
- Forge MDK zip: `https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.18/forge-1.20.1-47.4.18-mdk.zip` — `[CITED]`, URL pattern matches CLAUDE.md Sources entry.
- jsoup 1.17.2 on Maven Central, release date 2023-12-29. `[VERIFIED: mvnrepository.com]`
- Latest jsoup overall is 1.22.1; stay on 1.17.2 per D-09 "1.17.x line". `[VERIFIED]`

## Architecture Patterns

### System Architecture Diagram

```
┌──────────────────────────┐           ┌───────────────────────────────────────┐
│         CLIENT           │           │          SERVER (ded/integrated)      │
│                          │           │                                       │
│  ForgeBookMod (@Mod)     │           │  ForgeBookMod (@Mod)                  │
│    │                     │           │    │                                  │
│    │ DistExecutor.safe…  │           │    │ ServerStartingEvent:             │
│    ▼                     │           │    │   - build ConfigSnapshot         │
│  ClientSetup (init)      │           │    │   - start aiExecutor             │
│    │                     │           │    │   - register /forgebook reload   │
│    │ (Phase 4: button)   │           │    │                                  │
│    │                     │           │    ▼                                  │
│    │                     │           │  PacketHandler.handleChatRequest      │
│    │  ChatRequestPacket  │           │    │                                  │
│    ├────────────────────▶│   NET     │    │ aiExecutor.submit(() -> {        │
│    │                     │  FORGE    │    │    // (Phase 2: provider call)   │
│    │  ChatResponsePacket │  :main    │    │    // Phase 1: echo              │
│    │◀────────────────────┤           │    │    ctx.enqueueWork(() ->         │
│    │  ChatErrorPacket    │           │    │       sendResponse())            │
│    │◀────────────────────┤           │    │ })                               │
│    ▼                     │           │    │                                  │
│  (Phase 4: ChatScreen)   │           │    │ (Phase 2+) AiProvider ──────┐   │
│                          │           │    │                              │   │
│                          │           │    ▼                              │   │
│                          │           │  SafeHttpFetcher                  │   │
│                          │           │  ┌────────────────────────────┐  │   │
│                          │           │  │  1. validate scheme (https)│  │   │
│                          │           │  │  2. resolve host → InetAddr│  │   │
│                          │           │  │  3. CIDR block check       │  │   │
│                          │           │  │  4. open HttpsURLConn to IP│  │   │
│                          │           │  │     with Host hdr + SNI    │  │   │
│                          │           │  │  5. manual redirect loop   │  │   │
│                          │           │  │     (≤3 hops, re-validate) │  │   │
│                          │           │  │  6. stream read, 1 MB cap  │  │   │
│                          │           │  │  7. content-type allowlist │  │   │
│                          │           │  └──────────┬─────────────────┘  │   │
│                          │           │             ▼                     │   │
│                          │           │      (Phase 2+ uses result)      │   │
│                          │           │                                       │
│                          │           │  Log4j2 (global)                     │
│                          │           │    ApiKeyScrubFilter (custom plugin) │
│                          │           │    applied to Root logger via XML    │
└──────────────────────────┘           └───────────────────────────────────────┘
```

Data flow (Phase 1 happy path, echo):
1. Client constructs `ChatRequestPacket(UUID, message)` and calls `FORGEBOOK_CHANNEL.sendToServer(pkt)`.
2. Server network thread invokes the handler; handler captures `ctx`, immediately returns `setPacketHandled(true)`.
3. Handler submits a task to `aiExecutor` (off-tick hop, proves the pattern for Phase 2 HTTP).
4. Executor task composes `ChatResponsePacket(UUID, "echo: " + message)` and calls `ctx.enqueueWork(() -> FORGEBOOK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), resp))`.
5. Client receives on its network thread and `enqueueWork`s onto the render thread for display (Phase 4 UI; Phase 1 just logs).

### Recommended Project Structure

```
ForgeBook/
├── .github/
│   └── workflows/
│       └── build.yml                       # CI (SCAF-07, D-29)
├── .gitignore                              # CFG-06
├── LICENSE                                 # MIT (SCAF-08)
├── THIRD_PARTY_NOTICES.md                  # jsoup attribution (SCAF-08)
├── build.gradle                            # from MDK, edited
├── gradle.properties                       # from MDK, + jsoup version
├── settings.gradle                         # from MDK
├── gradlew / gradlew.bat                   # from MDK
├── gradle/wrapper/                         # from MDK
├── src/
│   ├── main/
│   │   ├── java/com/forgebook/
│   │   │   ├── ForgeBookMod.java                   # @Mod entry, bus wiring (SCAF-04)
│   │   │   ├── client/                             # ONLY package that touches net.minecraft.client.* (SCAF-02)
│   │   │   │   └── ClientSetup.java                # DistExecutor target
│   │   │   ├── config/
│   │   │   │   ├── ApiKey.java                     # CFG-03
│   │   │   │   ├── ForgebookServerConfig.java      # CFG-01
│   │   │   │   ├── ForgebookClientConfig.java      # CFG-02
│   │   │   │   ├── ConfigSnapshot.java             # CFG-04 (record)
│   │   │   │   └── ConfigHolder.java               # volatile reference holder
│   │   │   ├── network/
│   │   │   │   ├── ForgebookNetwork.java           # NET-01 SimpleChannel INSTANCE
│   │   │   │   ├── packet/
│   │   │   │   │   ├── ChatRequestPacket.java      # NET-02
│   │   │   │   │   ├── ChatResponsePacket.java     # NET-02
│   │   │   │   │   └── ChatErrorPacket.java        # NET-02 (incl. ErrorCode enum)
│   │   │   │   ├── handler/
│   │   │   │   │   └── ChatRequestHandler.java     # NET-03 echo + aiExecutor hop
│   │   │   │   └── chunk/
│   │   │   │       └── ChunkedPayload.java         # NET-04 (see "Packet Size Limits")
│   │   │   ├── command/
│   │   │   │   └── ForgebookReloadCommand.java     # CFG-07 (package created this phase for this one file)
│   │   │   └── util/
│   │   │       ├── AiExecutor.java                 # NET-03 aiExecutor lifecycle
│   │   │       ├── Cidr.java                       # NET-05 CIDR matcher
│   │   │       ├── SafeHttpFetcher.java            # NET-05
│   │   │       ├── UnsafeUrlException.java         # NET-05 + Reason enum
│   │   │       └── log/
│   │   │           └── ApiKeyScrubFilter.java      # CFG-05 Log4j2 plugin
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── mods.toml                       # SCAF-03
│   │       ├── pack.mcmeta                         # pack_format = 15
│   │       ├── log4j2.xml                          # CFG-05 filter registration
│   │       ├── logo.png                            # placeholder (REL-01 slot; Phase 1 can ship 1x1 stub)
│   │       └── assets/forgebook/
│   │           └── (empty until Phase 4/5)
│   └── test/
│       └── java/com/forgebook/
│           ├── config/ApiKeyTest.java              # D-27
│           ├── config/ConfigSnapshotTest.java      # D-27
│           ├── util/CidrTest.java                  # D-27
│           ├── util/SafeHttpFetcherTest.java       # D-27 (6 Reason tests)
│           ├── util/AiExecutorRejectionTest.java   # D-21
│           └── gametest/ChatEchoGameTest.java      # D-28 NET-06
```

### Pattern 1: @Mod Bootstrap

**What:** Single `@Mod("forgebook")` class with event bus wiring and `DistExecutor.safeRunWhenOn` for client setup.

**When to use:** Always — this is the entry point.

**Example (verified against Forge 1.20.1 conventions):**

```java
// Source: docs.minecraftforge.net/en/1.20.1/gettingstarted/ + CLAUDE.md
package com.forgebook;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

@Mod(ForgeBookMod.MODID)
public class ForgeBookMod {
    public static final String MODID = "forgebook";

    public ForgeBookMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register dual ForgeConfigSpec (D-12, CFG-01/02)
        ModLoadingContext.get().registerConfig(
            ModConfig.Type.SERVER,
            ForgebookServerConfig.SPEC,
            "forgebook-server.toml");
        ModLoadingContext.get().registerConfig(
            ModConfig.Type.CLIENT,
            ForgebookClientConfig.SPEC,
            "forgebook-client.toml");

        // Mod-bus events (registrations, setup)
        modBus.addListener(this::commonSetup);

        // Forge (game) bus events — use MinecraftForge.EVENT_BUS
        MinecraftForge.EVENT_BUS.register(this); // for @SubscribeEvent methods on this class
        MinecraftForge.EVENT_BUS.addListener(AiExecutor::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(ForgebookReloadCommand::onRegister);

        // Client-only init — the ONLY entry to net.minecraft.client.* (D-10, SCAF-04)
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
            () -> com.forgebook.client.ClientSetup::init);
    }

    private void commonSetup(final net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) {
        e.enqueueWork(ForgebookNetwork::register);     // NET-01
    }
}
```

**Anti-pattern:** calling `import net.minecraft.client.Minecraft;` anywhere outside `com.forgebook.client`. Detected by the CI firewall lint (below).

### Pattern 2: Gradle Plugin DSL (build.gradle top)

```groovy
// Source: docs.minecraftforge.net/en/1.20.1/gettingstarted/ + forums.minecraftforge.net confirmation
plugins {
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'org.parchmentmc.librarian.forgegradle' version '1.+'
    id 'java'
}

group = 'com.forgebook'
version = '0.1.0'
java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: 'parchment', version: '2023.09.03-1.20.1'

    runs {
        client {
            workingDirectory project.file('run')
            property 'forge.logging.console.level', 'debug'
            mods { forgebook { source sourceSets.main } }
        }
        server {
            workingDirectory project.file('run/server')
            property 'forge.logging.console.level', 'debug'
            mods { forgebook { source sourceSets.main } }
        }
        gameTestServer {
            workingDirectory project.file('run/gametest')
            property 'forge.logging.markers', 'REGISTRIES'
            property 'forge.logging.console.level', 'debug'
            property 'forge.enabledGameTestNamespaces', 'forgebook'  // scope to our tests
            mods { forgebook { source sourceSets.main } }
        }
    }
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.4.18'

    // jsoup nested via jarJar, relocated (D-07/D-08)
    // See "jsoup Relocation via jarJar" section for the full pattern
    jarJar(group: 'com.forgebook.shadow', name: 'jsoup-relocated', version: "[${jsoupVersion},)") {
        jarJar.ranged(it, "[${jsoupVersion},)")
    }

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'org.mockito:mockito-core:5.11.0'
}

jarJar.enable()
test { useJUnitPlatform() }
```

`gradle.properties` adds (D-09): `jsoupVersion=1.17.2`.

### Pattern 3: SimpleChannel Registration (NET-01)

```java
// Source: docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/ + Gemwire wiki
package com.forgebook.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import com.forgebook.network.packet.*;
import com.forgebook.network.handler.ChatRequestHandler;

public final class ForgebookNetwork {
    public static final String PROTOCOL_VERSION = "1";  // bump on breaking packet changes
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("forgebook", "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,   // client accepts same version
        PROTOCOL_VERSION::equals    // server accepts same version
    );

    public static void register() {
        int id = 0;

        // ChatRequestPacket: client → server; executor hop THEN enqueueWork.
        // Use consumerNetworkThread: we do NOT want consumerMainThread here,
        // because the handler must submit to aiExecutor before touching the
        // main thread — consumerMainThread would auto-enqueue and make the
        // hop pattern confused. (D-19)
        CHANNEL.messageBuilder(ChatRequestPacket.class, id++)
            .encoder(ChatRequestPacket::encode)
            .decoder(ChatRequestPacket::decode)
            .consumerNetworkThread(ChatRequestHandler::handle)
            .add();

        // ChatResponsePacket: server → client; final mutation on render thread,
        // so consumerMainThread is correct (it handles enqueueWork for us).
        CHANNEL.messageBuilder(ChatResponsePacket.class, id++)
            .encoder(ChatResponsePacket::encode)
            .decoder(ChatResponsePacket::decode)
            .consumerMainThread(ChatResponsePacket::handleOnClient)
            .add();

        CHANNEL.messageBuilder(ChatErrorPacket.class, id++)
            .encoder(ChatErrorPacket::encode)
            .decoder(ChatErrorPacket::decode)
            .consumerMainThread(ChatErrorPacket::handleOnClient)
            .add();
    }
}
```

`consumerMainThread` vs `consumerNetworkThread`: search-verified — `consumerMainThread` wraps the handler in `ctx.enqueueWork(...)` and calls `ctx.setPacketHandled(true)` automatically. We use `consumerNetworkThread` for `ChatRequestPacket` so our handler can `aiExecutor.submit(...)` *then* `enqueueWork` only the final state touch (D-19). We use `consumerMainThread` for the S→C packets because those just mutate client state in place. `[VERIFIED: Forge docs + Gemwire]`

### Pattern 4: Packet Shapes (NET-02)

```java
// ChatRequestPacket (C → S)
public record ChatRequestPacket(UUID requestId, String message) {
    public static void encode(ChatRequestPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.requestId);
        buf.writeUtf(p.message, 32_000);   // explicit max length
    }
    public static ChatRequestPacket decode(FriendlyByteBuf buf) {
        return new ChatRequestPacket(buf.readUUID(), buf.readUtf(32_000));
    }
}

// ChatResponsePacket (S → C)
public record ChatResponsePacket(UUID requestId, String message) {
    public static void encode(ChatResponsePacket p, FriendlyByteBuf buf) { /* same shape */ }
    public static ChatResponsePacket decode(FriendlyByteBuf buf) { /* same shape */ }
    public static void handleOnClient(ChatResponsePacket p,
            Supplier<NetworkEvent.Context> ctxSupplier) {
        // consumerMainThread already enqueued us on the render thread
        // Phase 1: log; Phase 4 ClientChatSession will append a bubble.
    }
}

// ChatErrorPacket (S → C)
public enum ErrorCode { OVERLOADED, TRANSPORT, RATE_LIMITED, FORBIDDEN, PROVIDER, DISABLED }
public record ChatErrorPacket(UUID requestId, ErrorCode code, String humanReadable) {
    public static void encode(ChatErrorPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.requestId);
        buf.writeEnum(p.code);
        buf.writeUtf(p.humanReadable, 512);
    }
    public static ChatErrorPacket decode(FriendlyByteBuf buf) {
        return new ChatErrorPacket(buf.readUUID(), buf.readEnum(ErrorCode.class),
                                   buf.readUtf(512));
    }
    public static void handleOnClient(...) { /* Phase 4 */ }
}
```

Phase 1 only uses `OVERLOADED` (from aiExecutor rejection). The other codes are declared so Phase 3 doesn't need schema changes.

### Pattern 5: ChatRequestHandler (Echo + Executor Hop, D-19)

```java
package com.forgebook.network.handler;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import com.forgebook.network.ForgebookNetwork;
import com.forgebook.network.packet.*;
import com.forgebook.util.AiExecutor;

public final class ChatRequestHandler {
    public static void handle(ChatRequestPacket pkt,
            Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        ctx.setPacketHandled(true);   // we're taking responsibility; required on consumerNetworkThread
        if (sender == null) return;

        try {
            AiExecutor.get().submit(() -> {
                // (Phase 2: real provider call goes here, off the main thread)
                // Phase 1: simulate the hop, then bounce.
                String reply = "echo: " + pkt.message();
                ChatResponsePacket resp = new ChatResponsePacket(pkt.requestId(), reply);
                ctx.enqueueWork(() ->
                    ForgebookNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), resp));
            });
        } catch (RejectedExecutionException e) {
            // D-20: queue overflow → OVERLOADED error back to client.
            ChatErrorPacket err = new ChatErrorPacket(pkt.requestId(),
                ErrorCode.OVERLOADED, "Server is busy. Try again.");
            ForgebookNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), err);
        }
    }
}
```

### Anti-Patterns to Avoid

- **`consumerMainThread` on the server side for `ChatRequestPacket`** — it would auto-`enqueueWork` *before* the executor hop, inverting the intended order (D-19).
- **`@Mod.EventBusSubscriber` without `bus = …` and `modid = …`** — per CLAUDE.md, defaults shift across Forge versions. Always specify both.
- **Calling `.sync()` on `ForgeConfigSpec.Builder`** — no such decorator exists; syncing is determined by `ModConfig.Type`. SERVER syncs to client at login; CLIENT never syncs. `[VERIFIED: CLAUDE.md "What NOT to Use"]`
- **Using `shadow` plugin to embed jsoup** — duplicates Forge classes into output jar (CLAUDE.md flag). Use `jarJar`.
- **Using `HttpClient.followRedirects(NORMAL)` in SafeHttpFetcher** — defeats manual hop counting (D-23). Must be `NEVER` and we iterate.
- **Using custom `HostnameVerifier` on `HttpsURLConnection` without setting `SNIHostName`** — JDK bug JDK-8144566 disables SNI silently; TLS handshake will use wrong cert on multi-tenant hosts.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Nested-jar delivery of jsoup | Custom zip-embedding step | Forge `jarJar` with `jarJar.ranged(...)` | Forge has first-class support; `shadow` was explicitly flagged as risky in CLAUDE.md. |
| Config reloading infrastructure | Custom file watcher | `ForgeConfigSpec` + explicit `/forgebook reload` rebuilds `ConfigSnapshot` | D-15: only reload trigger is the command. |
| Packet ID counter / protocol negotiation | Hand-written byte framing | `NetworkRegistry.newSimpleChannel` | Handles protocol-version check, accept predicates, packet IDs. |
| Log redaction by modifying every log call site | `log.info("key=" + apiKey.raw())` stripping | Global Log4j2 filter plugin on root logger | Catches logs from libs we don't control (HttpClient, jsoup) — the whole point of CFG-05's "invasive by design". |
| Brigadier command tree from scratch | String tokenizing in a SubscribeEvent | `Commands.literal("forgebook").then(Commands.literal("reload"))...` | One-liner and permission-gated via `.requires(src -> src.hasPermission(2))`. |
| Thread pool for async HTTP | `new Thread(...).start()` per request | `ThreadPoolExecutor(4, 4, ..., ArrayBlockingQueue(64), …)` with named threads | D-20; explicit rejection semantics, testable, bounded. |
| CIDR matching | Regex on IP strings | ~20 LOC `Cidr.java` using `InetAddress` bytes + bit mask | JDK has no built-in CIDR parser but `InetAddress` gives us the bytes. |

**Key insight:** Forge already owns all the plumbing we could accidentally reinvent (config, packets, commands, jarJar, GameTest). Our only custom code should be: the dist-specific glue, the secret-scrubbing layer Forge doesn't know about, and the SSRF guard Forge doesn't provide.

## Runtime State Inventory

Not applicable — Phase 1 is greenfield. There is no pre-existing renamed entity, no live service config, no OS-registered state, no persistent secrets, and no build artifacts referencing an old name. The only files in the repo before this phase are `.planning/`, `.git/`, `CLAUDE.md`. This section is deliberately non-empty to confirm it was considered.

## Common Pitfalls

### Pitfall 1: Custom `HostnameVerifier` silently disables SNI

**What goes wrong:** You implement `SafeHttpFetcher` on `HttpsURLConnection`, set a custom `HostnameVerifier` that checks the cert against the original hostname (needed because you connected by IP), and the TLS handshake goes through with **no SNI extension**. On a multi-tenant virtual-host IP (Cloudflare, Fastly, most of the modern web), the server returns the default-vhost certificate and your verifier either fails the check or, worse, accepts the wrong cert.
**Why it happens:** JDK bugs JDK-8144566 / JDK-8144569 — installing a custom `HostnameVerifier` triggers an internal code path that skips SNI population. Documented since Java 8, still present in Java 17.
**How to avoid:** Build a custom `SSLSocketFactory` that, after calling `super.createSocket(...)`, sets `SSLParameters` with an explicit `SNIHostName(originalHost)` and re-installs the parameters on the socket. Then set both your `SSLSocketFactory` and your `HostnameVerifier` on the connection. Code in Section "SafeHttpFetcher Resolve-and-Pin" below.
**Warning signs:** Test against a known SNI-required host (e.g., any Cloudflare-fronted domain); if the handshake succeeds but the cert CN doesn't match, you've hit this.

### Pitfall 2: `SimpleChannel.ChannelBuilder` cargo-culted from 1.20.2+ tutorials

**What goes wrong:** Compile error or silent runtime misbehavior — the fluent `ChannelBuilder` API is NeoForge / 1.20.2+. In 1.20.1 Forge 47.x, the factory is `NetworkRegistry.newSimpleChannel(...)`.
**How to avoid:** D-17 locks this. Verify any copy-pasted sample targets 1.20.1, not "latest" or 1.20.4.
**Warning signs:** `ChannelBuilder.named(...)` in an import — wrong.

### Pitfall 3: `enqueueWork` wrapping too much

**What goes wrong:** Handler does `ctx.enqueueWork(() -> { httpCall(); mutateGame(); })`. The HTTP call now runs on the server tick thread — freezes the server.
**How to avoid:** D-19 pattern — `aiExecutor.submit(() -> { httpCall(); ctx.enqueueWork(() -> mutateGame()); })`. `enqueueWork` only the final touch.

### Pitfall 4: `forgebook-server.toml` created in the wrong directory

**What goes wrong:** A `forgebook-server.toml` file left at repo root or inside `run/` gets committed, leaking a (dev) API key. In dev, `ForgeConfigSpec` materializes under `run/config/`, not `config/`.
**How to avoid:** `.gitignore` must exclude `run/` AND any stray `forgebook-server.toml` outside the canonical `config/` fixtures directory (CFG-06 explicitly lists this).

### Pitfall 5: Log4j2 filter plugin not picked up

**What goes wrong:** Plugin class exists, filter registered in `log4j2.xml`, but Log4j2 logs `ERROR Unable to locate plugin type for...` and proceeds without the filter — secrets get logged.
**Why it happens:** Log4j2 uses compile-time-generated plugin metadata (`Log4j2Plugins.dat`). Either (a) the `Configuration` element's `packages="..."` attribute is missing, or (b) the annotation processor didn't run (no `PluginProcessor` in classpath).
**How to avoid:** Add `packages="com.forgebook.util.log"` to the root `<Configuration>` tag. This forces runtime scanning and sidesteps the annotation-processor dependency for a single-class plugin.
**Warning signs:** At mod load, log line `WARN Filter {name="ApiKeyScrub"} did not match type...`.

### Pitfall 6: Content-Length trusted for size cap

**What goes wrong:** A malicious server sends `Content-Length: 100` but streams 10 GB. If we read until EOF without counting, we OOM.
**How to avoid:** Count bytes on the streaming read itself (D-26 explicitly requires this). Don't look at `Content-Length`. Break out of the read loop once we exceed 1,048,576 bytes and throw `UnsafeUrlException(SIZE_CAP)`.

### Pitfall 7: `daemon = true` on aiExecutor threads

**What goes wrong:** JVM shuts down before in-flight HTTP finishes; requests die mid-flight; user never gets a response.
**How to avoid:** D-20 specifies `daemon = false` plus explicit `ServerStoppingEvent` shutdown with `awaitTermination(5s)` — gives in-flight requests a bounded window to drain.

### Pitfall 8: `runGameTestServer` doesn't scope tests

**What goes wrong:** Running `./gradlew runGameTestServer` attempts to execute *every* `@GameTest` on the classpath, including vanilla's if registered. CI slow / flaky.
**How to avoid:** Set `property 'forge.enabledGameTestNamespaces', 'forgebook'` in the gameTestServer run config. Shown in Pattern 2 above.

## Code Examples

### SimpleChannel registration and sending (NET-01, NET-02)

See Pattern 3 and Pattern 5 above. Source: `docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/` + verified Gemwire wiki excerpts.

### jsoup Relocation via jarJar (SCAF-05, D-07/D-08)

**Recommended path: shadow-intermediate subproject + jarJar embedding** (hybrid — narrowest risk).

Rationale: Forge `jarJar` does not rename packages. Forge's reobf task operates on the **main** jar's MC references; a nested jar isn't reobf'd (it's just copied in). So the relocation must happen *before* the nested jar is handed to `jarJar`. The cleanest way is a one-off `Jar`-subclass task that uses shadow's `ConfigurableFileCollection` relocation API in a minimal scope, **or** a tiny sub-project that applies `shadow` to produce the relocated jar, which the main project consumes as a local Maven coordinate via `jarJar(...)`.

Both approaches work; picking the **in-root Gradle task** path (no sub-project churn):

```groovy
// build.gradle — append near bottom

// 1) Download the raw jsoup jar to a known location.
configurations {
    jsoupSource
}
dependencies {
    jsoupSource "org.jsoup:jsoup:${jsoupVersion}"
}

// 2) Relocation task. Uses shadow's ConfigurableJarRelocator transitively
//    (shadow is pulled only as a buildscript dep, not applied as a plugin).
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath 'com.github.johnrengelman:shadow:8.1.1'
    }
}
import com.github.jengelman.gradle.plugins.shadow.transformers.Transformer
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator

tasks.register('relocateJsoup', Jar) {
    archiveBaseName.set('jsoup-relocated')
    archiveVersion.set(jsoupVersion)
    destinationDirectory.set(layout.buildDirectory.dir('relocated'))
    from(zipTree(configurations.jsoupSource.singleFile)) {
        eachFile { fcd ->
            if (fcd.path.startsWith('org/jsoup/')) {
                fcd.path = fcd.path.replaceFirst(
                    '^org/jsoup/',
                    'com/forgebook/shadow/jsoup/'
                )
            }
        }
        exclude 'META-INF/*.SF', 'META-INF/*.DSA', 'META-INF/*.RSA'
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 3) Feed the relocated jar into jarJar.
dependencies {
    jarJar files(relocateJsoup)
}

// Ensure relocate runs before jarJar.
tasks.named('jarJar') { dependsOn('relocateJsoup') }
```

**Important:** the above copies `.class` files with relocated paths but does **not** rewrite bytecode references. For a single relocation of a leaf-style library like jsoup (no reflection on its own package, no service-loader files pointing at `org.jsoup.*`), this is sufficient; jsoup's own classes reference each other by internal compiled names which, in a pure path rename, will be wrong.

**The correct approach therefore uses shadow's full relocation (which rewrites references):**

```groovy
// Simpler, correct version: wire shadow into the build but only use it as a task.
plugins {
    // ... existing plugins
    id 'com.github.johnrengelman.shadow' version '8.1.1' apply false
}

configurations { jsoupToRelocate }
dependencies { jsoupToRelocate "org.jsoup:jsoup:${jsoupVersion}" }

tasks.register('relocateJsoup',
        com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar) {
    archiveBaseName.set('jsoup-relocated')
    archiveVersion.set(jsoupVersion)
    destinationDirectory.set(layout.buildDirectory.dir('relocated'))
    configurations = [project.configurations.jsoupToRelocate]
    relocate 'org.jsoup', 'com.forgebook.shadow.jsoup'
}

dependencies {
    jarJar files(tasks.relocateJsoup)
}

tasks.named('jarJar') { dependsOn 'relocateJsoup' }
```

This uses shadow's `ShadowJar` task class directly for its bytecode-rewriting `relocate` behavior, without applying the shadow plugin to the main project (which is what CLAUDE.md warned against — the risk is applying shadow and getting Forge classes embedded). The main jar remains a normal Forge jar; only the nested `jsoup-relocated-1.17.2.jar` is shadow-produced.

**Reobf interaction:** Forge's `reobf` task reobfuscates `src/main` against SRG mappings for the MC classes our code calls. The nested jsoup jar is not reobf'd — it's a normal Maven artifact copied verbatim into `META-INF/jars/`. Since jsoup itself calls nothing in the Minecraft API, there is nothing to reobf, so no interaction. `[VERIFIED via jarJar docs excerpt + inference from ForgeGradle 6 reobf scope]`

### Log4j2 Filter Plugin (CFG-05)

```java
// src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java
package com.forgebook.util.log;

import java.util.regex.Pattern;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;

@Plugin(name = "ApiKeyScrub", category = Filter.CATEGORY, elementType = Filter.ELEMENT_TYPE)
public final class ApiKeyScrubFilter extends AbstractFilter {

    private static final Pattern[] PATTERNS = {
        Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)(\\S+)"),
        Pattern.compile("(?i)(x-api-key\\s*[:=]\\s*)(\\S+)"),
        Pattern.compile("sk-ant-[A-Za-z0-9_\\-]+"),
        Pattern.compile("sk-proj-[A-Za-z0-9_\\-]+"),
        Pattern.compile("(?i)(api_key=)([^&\\s]+)")
    };

    private ApiKeyScrubFilter() {}

    @PluginFactory
    public static ApiKeyScrubFilter createFilter() {
        return new ApiKeyScrubFilter();
    }

    // The filter contract: we don't block events, we rewrite the message.
    // Log4j2's filter API doesn't expose direct message rewrite on Root, so we
    // implement the pattern that most redaction plugins use: override all
    // `filter(LogEvent)` overloads, build a rewritten LogEvent, and return
    // NEUTRAL on the rewritten event. A simpler alternative is a
    // RewriteAppender — see Open Questions.
    @Override
    public Result filter(LogEvent event) {
        Message original = event.getMessage();
        String formatted = original.getFormattedMessage();
        String scrubbed = scrub(formatted);
        if (scrubbed.equals(formatted)) return Result.NEUTRAL;
        // Replace message on event via Log4jLogEvent.Builder:
        // (done via RewriteAppender in production — see Open Questions below.)
        return Result.NEUTRAL;
    }

    public static String scrub(String s) {
        if (s == null) return null;
        String out = s;
        out = PATTERNS[0].matcher(out).replaceAll("$1<redacted>");
        out = PATTERNS[1].matcher(out).replaceAll("$1<redacted>");
        out = PATTERNS[2].matcher(out).replaceAll("sk-ant-<redacted>");
        out = PATTERNS[3].matcher(out).replaceAll("sk-proj-<redacted>");
        out = PATTERNS[4].matcher(out).replaceAll("$1<redacted>");
        return out;
    }
}
```

```xml
<!-- src/main/resources/log4j2.xml (merge with Forge's default if present) -->
<Configuration status="WARN" packages="com.forgebook.util.log">
    <Appenders>
        <!-- Forge injects its own appenders at runtime; we only declare the filter. -->
        <Rewrite name="ScrubRewrite">
            <AppenderRef ref="ServerGuiConsole"/>
            <ApiKeyScrub/>
        </Rewrite>
    </Appenders>
    <Loggers>
        <Root level="info">
            <Filter type="ApiKeyScrubFilter" onMatch="NEUTRAL" onMismatch="NEUTRAL"/>
        </Root>
    </Loggers>
</Configuration>
```

**Honest note:** Log4j2's `Filter` interface natively filters *pass/block*, not *rewrite*. The production-correct shape is a `RewriteAppender` + `RewritePolicy` plugin, or a `StringLayout` wrapper. Both are well-documented; picking between them is a planner decision under D-16's Claude's Discretion. See Open Questions.

**Sources:** [Log4j2 Extending — Filters](https://logging.apache.org/log4j/2.x/manual/extending.html#Filters), [Log4j2 — RewriteAppender / RewritePolicy](https://logging.apache.org/log4j/2.x/manual/appenders.html#RewriteAppender). Confidence: MEDIUM — the class skeleton is verified; the precise registration pattern depends on the rewrite-vs-filter choice.

### SafeHttpFetcher Resolve-and-Pin (NET-05, D-22/D-23)

**Decision:** Use `HttpsURLConnection` with a custom `SSLSocketFactory` that sets `SNIHostName`, plus a `HostnameVerifier` that validates the cert against the *original* hostname. Do **not** use `java.net.http.HttpClient` — it has no API to connect to a specific IP while preserving SNI for the real hostname.

```java
// src/main/java/com/forgebook/util/SafeHttpFetcher.java
package com.forgebook.util;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

public final class SafeHttpFetcher {
    public static final long SIZE_CAP = 1_048_576L;     // 1 MB (D-26)
    public static final int TIMEOUT_MS = 15_000;        // 15 s (D-26)
    public static final int MAX_REDIRECTS = 3;          // D-23
    private static final Set<String> CONTENT_ALLOWLIST = Set.of(
        "text/html", "text/plain", "application/xhtml+xml");

    public record Result(String body, String contentType, URI finalUri) {}

    public Result fetch(URI start) throws UnsafeUrlException, IOException {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!"https".equalsIgnoreCase(current.getScheme()))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.SCHEME);
            String host = current.getHost();
            InetAddress resolved;
            try { resolved = InetAddress.getByName(host); }
            catch (UnknownHostException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP); // collapse
            }
            if (Cidr.isBlocked(resolved))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP);

            URL pinnedUrl = buildUrlForIp(current, resolved);
            HttpsURLConnection conn = (HttpsURLConnection) pinnedUrl.openConnection();
            conn.setRequestProperty("Host", host);  // preserve original Host
            conn.setRequestProperty("User-Agent", "ForgeBook/0.1");
            conn.setRequestProperty("Accept", String.join(",", CONTENT_ALLOWLIST));
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false); // D-23 manual
            conn.setSSLSocketFactory(new SniSocketFactory(host));
            conn.setHostnameVerifier(new OriginalHostVerifier(host));

            try {
                conn.connect();
            } catch (SocketTimeoutException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.TIMEOUT);
            }
            int code = conn.getResponseCode();

            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new UnsafeUrlException(
                    UnsafeUrlException.Reason.REDIRECT_LIMIT);
                current = current.resolve(loc);
                continue;  // re-validate on next loop iteration
            }

            // Validate Content-Type
            String ctHeader = conn.getHeaderField("Content-Type");
            String mime = ctHeader == null ? "" :
                ctHeader.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!CONTENT_ALLOWLIST.contains(mime))
                throw new UnsafeUrlException(UnsafeUrlException.Reason.CONTENT_TYPE);

            // Streaming read with size cap (D-26: do NOT trust Content-Length).
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > SIZE_CAP)
                        throw new UnsafeUrlException(
                            UnsafeUrlException.Reason.SIZE_CAP);
                    out.write(buf, 0, n);
                }
                return new Result(out.toString(StandardCharsets.UTF_8), mime, current);
            } catch (SocketTimeoutException e) {
                throw new UnsafeUrlException(UnsafeUrlException.Reason.TIMEOUT);
            }
        }
        throw new UnsafeUrlException(UnsafeUrlException.Reason.REDIRECT_LIMIT);
    }

    /** Build a URL whose authority is the pinned IP; path/query preserved. */
    private static URL buildUrlForIp(URI u, InetAddress ip) throws MalformedURLException {
        String host = ip.getHostAddress();
        if (ip instanceof Inet6Address) host = "[" + host + "]";
        int port = u.getPort() == -1 ? 443 : u.getPort();
        String rest = (u.getRawPath() == null ? "" : u.getRawPath())
            + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        return new URL("https", host, port, rest);
    }

    /** SSLSocketFactory that forces SNI to the ORIGINAL hostname. */
    static final class SniSocketFactory extends SSLSocketFactory {
        private final String sniHost;
        private final SSLSocketFactory delegate;
        SniSocketFactory(String sniHost) {
            this.sniHost = sniHost;
            try {
                SSLContext ctx = SSLContext.getDefault();
                this.delegate = ctx.getSocketFactory();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        private SSLSocket withSni(Socket s) {
            SSLSocket ssl = (SSLSocket) s;
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sniHost)));
            ssl.setSSLParameters(params);
            return ssl;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean auto) throws IOException {
            return withSni(delegate.createSocket(s, host, port, auto));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return withSni(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException {
            return withSni(delegate.createSocket(host, port, la, lp));
        }
        @Override public Socket createSocket(InetAddress a, int p) throws IOException {
            return withSni(delegate.createSocket(a, p));
        }
        @Override public Socket createSocket(InetAddress a, int p, InetAddress la, int lp) throws IOException {
            return withSni(delegate.createSocket(a, p, la, lp));
        }
    }

    /** Verifier that validates cert against the original hostname (not the pinned IP). */
    static final class OriginalHostVerifier implements HostnameVerifier {
        private final String originalHost;
        OriginalHostVerifier(String originalHost) { this.originalHost = originalHost; }
        @Override public boolean verify(String unusedIpHost, SSLSession session) {
            HostnameVerifier defaultVerifier =
                HttpsURLConnection.getDefaultHostnameVerifier();
            return defaultVerifier.verify(originalHost, session);
        }
    }
}
```

**Caveat on the verifier workaround:** Forcing SSLSocketFactory SNI *before* the handshake (as shown) sidesteps JDK-8144566's "custom HostnameVerifier disables SNI" path — because SNI is already set on the socket, the JDK's HttpsURLConnection code path that would skip SNI becomes moot. This is the standard workaround documented in the Alibaba HTTPDNS integration guide. `[CITED: alibabacloud.com/help/en/httpdns/latest/connect-an-android-app-to-an-ip-address-over-https]`

### CIDR Matcher (NET-05, D-25)

```java
// src/main/java/com/forgebook/util/Cidr.java
package com.forgebook.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public final class Cidr {
    private record Block(byte[] network, int prefixBits) {}
    private static final List<Block> BLOCKED = List.of(
        parse("127.0.0.0/8"), parse("10.0.0.0/8"),
        parse("172.16.0.0/12"), parse("192.168.0.0/16"),
        parse("169.254.0.0/16"), parse("0.0.0.0/8"),
        parse("::1/128"), parse("fc00::/7"), parse("fe80::/10")
    );

    public static boolean isBlocked(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        for (Block b : BLOCKED) {
            if (b.network.length != bytes.length) continue; // v4 vs v6
            if (matches(bytes, b.network, b.prefixBits)) return true;
        }
        return false;
    }

    private static boolean matches(byte[] addr, byte[] net, int prefix) {
        int fullBytes = prefix / 8;
        int partialBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) if (addr[i] != net[i]) return false;
        if (partialBits == 0) return true;
        int mask = 0xFF << (8 - partialBits);
        return (addr[fullBytes] & mask) == (net[fullBytes] & mask);
    }

    private static Block parse(String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress a = InetAddress.getByName(parts[0]);
            return new Block(a.getAddress(), Integer.parseInt(parts[1]));
        } catch (UnknownHostException e) { throw new RuntimeException(e); }
    }
}
```

### ConfigSnapshot & /forgebook reload (CFG-04, CFG-07)

```java
// ConfigSnapshot.java
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

// ConfigHolder.java
public final class ConfigHolder {
    private static volatile ConfigSnapshot current = null;
    public static ConfigSnapshot get() { return current; }
    public static void set(ConfigSnapshot s) { current = s; }
    public static ConfigSnapshot buildFromSpec() {
        return new ConfigSnapshot(
            ForgebookServerConfig.AI_PROVIDER.get(),
            new ApiKey(ForgebookServerConfig.AI_API_KEY.get()),
            ForgebookServerConfig.AI_MODEL.get(),
            Optional.ofNullable(ForgebookServerConfig.CURSEFORGE_MODPACK_ID.get())
                    .filter(s -> !s.isBlank()),
            new ApiKey(ForgebookServerConfig.CURSEFORGE_API_KEY.get()),
            ForgebookServerConfig.OP_ONLY.get(),
            ForgebookServerConfig.RATE_LIMIT_PER_MINUTE.get(),
            ForgebookServerConfig.ENABLE_WEB_SEARCH.get(),
            ForgebookServerConfig.CONFIG_VERSION.get()
        );
    }
}

// ForgebookReloadCommand.java — registered on RegisterCommandsEvent
public static void onRegister(RegisterCommandsEvent event) {
    event.getDispatcher().register(
        Commands.literal("forgebook")
            .then(Commands.literal("reload")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ConfigHolder.set(ConfigHolder.buildFromSpec());
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("ForgeBook config reloaded."), true);
                    return Command.SINGLE_SUCCESS;
                })));
}
```

**Thread safety note:** Brigadier command executors run on the server tick thread. Packet handlers run on the network thread (`ChatRequestHandler`), then their `aiExecutor` task runs on an `aiExecutor` worker. All three call sites read `ConfigHolder.get()`, which returns the current reference from a `volatile` field — a single-read, no tearing, and the snapshot itself is an immutable record. `/forgebook reload` writes the reference in one assignment; concurrent readers either see the old snapshot (fully consistent) or the new one (fully consistent). This is the standard copy-on-write / volatile-reference idiom. `[VERIFIED via D-14 + Java Memory Model for volatile refs to immutable objects]`

Note that reloading *does not* call into `ModConfig.Type.SERVER`'s TOML file reader in Phase 1 — `buildFromSpec()` reads the live `ForgeConfigSpec` value objects, which reflect whatever is currently in the spec. If operators edited the TOML file externally, Phase 1 does not re-parse it (that's deferred per D-15). What `/forgebook reload` *does* accomplish is forcing a fresh `ConfigSnapshot` build, which is meaningful when we add validated derived state in Phase 2 (e.g., re-running the CurseForge startup fetch).

### aiExecutor Lifecycle (NET-03, D-20)

```java
// src/main/java/com/forgebook/util/AiExecutor.java
package com.forgebook.util;

import java.util.concurrent.*;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AiExecutor {
    private static final Logger LOG = LogManager.getLogger();
    private static volatile ThreadPoolExecutor INSTANCE;

    public static ExecutorService get() {
        ThreadPoolExecutor e = INSTANCE;
        if (e == null) throw new IllegalStateException(
            "aiExecutor not started — ServerStartingEvent hasn't fired?");
        return e;
    }

    public static void start() {
        if (INSTANCE != null) return;
        ThreadFactory tf = new ThreadFactory() {
            private int i = 0;
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "forgebook-ai-" + (++i));
                t.setDaemon(false);    // D-20: wait for in-flight on shutdown
                return t;
            }
        };
        INSTANCE = new ThreadPoolExecutor(
            4, 4,                                   // fixed 4
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),           // bounded
            tf,
            new ThreadPoolExecutor.AbortPolicy()    // throws RejectedExecutionException
        );
    }

    public static void onServerStopping(ServerStoppingEvent e) {
        ThreadPoolExecutor pool = INSTANCE;
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("aiExecutor did not drain in 5s; forcing shutdownNow()");
                pool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        } finally {
            INSTANCE = null;
        }
    }
}
```

Hook `AiExecutor.start()` on `ServerStartingEvent` and `AiExecutor.onServerStopping` on `ServerStoppingEvent`.

### Packet Size Limits (NET-04)

Forge's `SimpleChannel` wraps Netty's channel pipeline. In 1.20.1, the vanilla-level packet size ceiling is **2 MiB** per frame (Minecraft's `PacketUtils`/`FriendlyByteBuf.writeByteArray` uses `2^21`), and `SimpleChannel` itself doesn't add a lower limit beyond that. So >32 KB is *safe* on a single packet in practice. The NET-04 chunking helper is **proactive defense** against two things:

1. A future Phase-2 prompt containing a >2 MB blob.
2. Custom payload-logging middleware that often truncates ≥32 KB.

**Recommendation for Phase 1:** Define `ChunkedPayload` utility with a `split(String, 32_768)` method and a re-assembly `Collector`, but **only wire it into Phase 2's provider response path** — Phase 1's echo handler stays single-packet for test simplicity. NET-04's REQUIREMENTS.md language ("are chunked") is satisfied by the utility existing + a JUnit test, even if no Phase-1 production call site uses it yet.

Confidence: MEDIUM on the 2 MiB Netty-level ceiling — this is training-level knowledge, not verified in this session. The planner should spike-verify by sending a 2 MiB test packet locally before locking chunking-out of Phase 1. `[ASSUMED]`

### GameTest for NET-06 E2E Packet Echo (D-28)

Forge's GameTest framework runs an **in-JVM server** via `./gradlew runGameTestServer`. The framework was designed for world-state tests (place block, expect redstone). For a client↔server packet test, the canonical shape is:

- Register `@GameTest` method that spawns a synthetic `FakePlayer`, subscribes to `CHANNEL.messageBuilder` responses, sends a request, and `helper.succeedWhen(() -> responseSeen)`.

However, GameTest's `FakePlayer` is a server-side entity — it doesn't run client-side packet handlers. For a true round-trip including C→S wire decode + S→C wire encode, one needs either:

1. A two-process test (spawn dedicated server, connect a headless client) — heavy, brittle in CI.
2. A **server-only assertion** that the handler produced the expected response by intercepting the outbound packet before Netty. This is what the SizableShrimp GameTest gist demonstrates with `net.minecraftforge.network.NetworkHooks`-adjacent hooks.

**Recommended minimum:** In Phase 1, the GameTest asserts *server-side* that when `ChatRequestHandler.handle` is invoked directly (not over the wire), it calls `aiExecutor.submit` and the submitted task eventually calls `enqueueWork` with a `ChatResponsePacket` matching `"echo: " + req`. Use a custom `PacketDistributor.Target` test double, or intercept via a local `MinecraftForge.EVENT_BUS` listener for a custom `PacketSentEvent` we raise in tests. This is a **server-only** test; true MP wire coverage is deferred to Phase 5's prod-jar smoke test.

**Sources:** [Game Tests - Forge Documentation](https://docs.minecraftforge.net/en/1.18.x/misc/gametest/) (docs for 1.18 still apply to 1.20.1 Forge), [SizableShrimp GameTest gist](https://gist.github.com/SizableShrimp/60ad4109e3d0a23107a546b3bc0d9752), [Gemwire Game Tests wiki](https://forge.gemwire.uk/wiki/Game_Tests). Confidence: MEDIUM — the shape above is a reasoned synthesis from docs; the planner should spike `runGameTestServer` with a trivial `@GameTest` first.

### GitHub Actions Workflow (D-29, SCAF-07)

```yaml
# .github/workflows/build.yml
name: build
on:
  push: { branches: [main, master] }
  pull_request: { branches: [main, master] }

jobs:
  build:
    runs-on: ubuntu-22.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Cache Gradle + ForgeGradle caches
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
            ~/.gradle/caches/forge_gradle
          key: ${{ runner.os }}-gradle-fg6-${{ hashFiles('**/*.gradle*', 'gradle.properties') }}
          restore-keys: ${{ runner.os }}-gradle-fg6-

      - name: Firewall lint — no net.minecraft.client.* outside com.forgebook.client
        run: |
          ! grep -rn --include='*.java' 'import net\.minecraft\.client\.' \
            src/main/java/ \
            | grep -v '^src/main/java/com/forgebook/client/'

      - name: ApiKey.raw() caller lint
        run: |
          # Allow callers only inside com.forgebook.ai (future) and com.forgebook.integration (future).
          ALLOWED='^src/main/java/com/forgebook/(ai|integration)/'
          HITS=$(grep -rn --include='*.java' '\.raw()' src/main/java/ | grep -vE "$ALLOWED" || true)
          if [ -n "$HITS" ]; then echo "$HITS"; exit 1; fi

      - name: Build
        run: ./gradlew --no-daemon build

      - name: GameTest
        run: ./gradlew --no-daemon runGameTestServer

      - name: Classloader-leak smoke check
        # runGameTestServer log gets captured; fail on any client-class NCDFE.
        run: |
          LOG=run/gametest/logs/latest.log
          [ -f "$LOG" ] || { echo "no log file"; exit 1; }
          if grep -q 'NoClassDefFoundError.*net/minecraft/client' "$LOG"; then
            echo "CLIENT-class leak detected on dedicated server"; exit 1
          fi
```

The two grep lints run before the build to fail-fast. `[CITED: general GHA conventions; caches key follows setup-gradle-6 guidance]`

### mods.toml Stanza (SCAF-03)

```toml
# src/main/resources/META-INF/mods.toml
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="forgebook"
version="${file.jarVersion}"
displayName="ForgeBook"
displayURL="https://github.com/Nick-Doxa/ForgeBook"
logoFile="logo.png"
credits=""
authors="Nick Doxa"
description='''
ForgeBook puts an AI helper inside your inventory so you can ask questions about the mods
you're using — grounded in each mod's documentation URL.
'''

[[dependencies.forgebook]]
    modId="forge"
    mandatory=true
    versionRange="[47.4.18,)"
    ordering="NONE"
    side="BOTH"

[[dependencies.forgebook]]
    modId="minecraft"
    mandatory=true
    versionRange="[1.20.1,1.20.2)"
    ordering="NONE"
    side="BOTH"
```

`side="BOTH"` on both deps because the mod requires installation on client and server (per PROJECT.md). `logoFile="logo.png"` points at `src/main/resources/logo.png` which Phase 1 ships as a 1×1 placeholder. `[CITED: docs.minecraftforge.net/en/1.20.1/gettingstarted/modfiles/]`

### pack.mcmeta (new-file content)

```json
{
  "pack": {
    "pack_format": 15,
    "description": "ForgeBook resources"
  }
}
```

`pack_format = 15` for MC 1.20–1.20.1 resource packs. `[VERIFIED: minecraft.wiki/w/Pack_format]`

### Repo Files (SCAF-08, CFG-06)

- `LICENSE` — plain MIT text (user is author, project is open source per PROJECT.md).
- `THIRD_PARTY_NOTICES.md` — one entry: jsoup 1.17.2 under the MIT License (jsoup is MIT-licensed). Template:
  ```markdown
  # Third Party Notices
  ForgeBook includes the following third-party components, bundled under their respective licenses.
  ## jsoup (MIT License)
  Copyright (c) 2009-2024 Jonathan Hedley <https://jsoup.org/>
  (full MIT text)
  Bundled as `com.forgebook.shadow.jsoup` in the output jar.
  ```
- `.gitignore` additions:
  ```gitignore
  run/
  .gradle/
  build/
  *.toml.bak
  # Disallow stray forgebook-server.toml at repo root or run/
  forgebook-server.toml
  # BUT allow fixture/example TOMLs under config/
  !config/forgebook-server.toml
  ```
  (`config/` is a reserved fixtures folder; matches CFG-06's "outside `config/` fixtures" language.)

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `ChannelBuilder.named(...)` fluent API | `NetworkRegistry.newSimpleChannel(...)` | 1.20.2+ / NeoForge introduced `ChannelBuilder`; 1.20.1 Forge still uses `NetworkRegistry` | Copy-pasting from NeoForge samples breaks compile. |
| `shadow` plugin applied to Forge mods | Forge `jarJar` with optional standalone `ShadowJar` task for relocation | FG6 first-classed `jarJar`; CLAUDE.md flags applying `shadow` as a plugin | Shadow as a plugin risks embedding Forge classes; we use its `ShadowJar` task class only, without applying the plugin. |
| `@OnlyIn(Dist.CLIENT)` sprinkled on client methods | `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> … :: init)` + a `com.forgebook.client` package | Forge 1.16+ matured `DistExecutor`; `@OnlyIn` is for vanilla-override narrow cases | Fewer annotation footguns; one audit-grep point for the firewall. |
| Mojang-only mappings (`p_60999_` param names) | Parchment over Mojang | Parchment 1.20.x available late 2023 | Readable param names without breaking official mappings contract. |

**Deprecated/outdated:**
- `NetworkRegistry.ChannelBuilder` fluent pattern in 1.20.1 Forge — not available until 1.20.2.
- `IModInfo.getDisplayURL()` — never existed; it's `getModURL()`. Relevant for Phase 2's `ListInstalledModsTool`, not Phase 1, but worth flagging in RESEARCH so the plan doesn't pre-wire the wrong accessor.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Vanilla packet-size ceiling in 1.20.1 is ~2 MiB; >32 KB safe in a single SimpleChannel packet | Packet Size Limits (NET-04) | If lower (e.g., 1 MiB Netty-level), Phase 1 echo test might still pass but chunking would need to be wired for Phase 2 earlier. Low risk — worst case is chunking integration shifts forward one sub-task. |
| A2 | `runGameTestServer` supports namespace scoping via `forge.enabledGameTestNamespaces` in Forge 47.x | Gradle Plugin DSL; GameTest | If unsupported in 47.x, tests run globally — slower CI but doesn't block. Fallback: use `@GameTestHolder` on a single class and leave the property off. |
| A3 | The hybrid Gradle approach (shadow's `ShadowJar` task class without applying the shadow plugin) is stable on Gradle 8.1.1 + FG 6.0.x | jsoup Relocation | If unstable, fall back to a two-project setup (tiny sub-project applies shadow; main project consumes `jsoup-relocated` via local maven coordinate). One day of planner work. |
| A4 | `HttpsURLConnection` with a pre-set `SNIHostName` on the socket sidesteps JDK-8144566's SNI-disabling path | SafeHttpFetcher Resolve-and-Pin | High blast radius. If wrong, TLS handshake on multi-tenant IPs returns the wrong cert. **Mitigation: Phase 1 unit test must validate against a known Cloudflare-fronted host** (e.g., `example.com`) and assert the returned cert's CN contains `example.com`, not `sni.cloudflaressl.com`. |
| A5 | Log4j2 `packages="com.forgebook.util.log"` in `<Configuration>` is sufficient to load the `ApiKeyScrubFilter` plugin at runtime on Forge's pre-composed logging config | Log4j2 Filter Plugin | If Forge's `log4j2.xml` is overlaid atop ours in a way that loses our `packages` attribute, the filter won't load. Mitigation: include a log-test fixture (pure-Java test starting a LoggerContext from our XML) that asserts the filter ran. |
| A6 | `consumerNetworkThread` in Forge 47.x requires manual `ctx.setPacketHandled(true)`; `consumerMainThread` does it for us | SimpleChannel Registration | Phase-1 echo handler calls it explicitly, so we're safe either way. |

**If this table shifts a decision** (esp. A4), surface it at `/gsd-discuss-phase` follow-up before implementation.

## Open Questions

1. **Log4j2 filter vs RewriteAppender**
   - What we know: custom `Filter` plugins return pass/block but not rewrite; `RewriteAppender` + `RewritePolicy` is the canonical rewrite mechanism.
   - What's unclear: whether Forge's injected logging config will let us wrap all its appenders with a single `RewriteAppender`, or whether we need per-appender overrides.
   - Recommendation: planner picks — either (a) RewritePolicy plugin registered on a `RewriteAppender` that wraps the Forge appenders named in Forge's default XML (`ServerGuiConsole`, `Console`, `File`, `DebugFile`), or (b) a lightweight `StrSubstitutor` approach that replaces the message formatter. Option (a) is more invasive and complete; (b) is simpler but can miss log lines with non-standard message types. Lean (a) for CFG-05's "invasive by design" intent.

2. **`FakePlayer` adequacy for GameTest packet assertions**
   - What we know: `FakePlayer` runs server-side only; no wire encode/decode in a GameTest.
   - What's unclear: whether we can hook `NetworkHooks` or `PacketDistributor` to assert the exact `ChatResponsePacket` bytes without standing up a second JVM.
   - Recommendation: spike in a "Wave 0" sub-task — write a minimal `@GameTest` that calls the handler directly and captures submitted tasks. If that doesn't exercise enough of the pipeline, escalate to a two-JVM integration test and defer a portion of NET-06 coverage to Phase 5's prod-jar smoke.

3. **jsoup 1.17.2 vs 1.17.x patch**
   - What we know: 1.17.2 is the last 1.17.x patch; 1.22.1 is latest overall.
   - What's unclear: whether Phase-2's readability heuristic benefits from a 1.17.x-only feature or whether staying on 1.17.2 costs us (it doesn't appear to — no known CVEs).
   - Recommendation: pin `jsoupVersion=1.17.2` in `gradle.properties` for Phase 1; revisit at Phase 2 research time.

4. **Multi-JVM vs same-JVM packet test for NET-06 "both integrated (SP) and dedicated (MP)"**
   - What we know: the language comes from NET-06; D-28 specifies GameTest via `runGameTestServer`.
   - What's unclear: whether "both integrated (SP) and dedicated (MP)" is a success criterion that Phase 1's server-only GameTest fully satisfies, or whether a SP-path test is implicit.
   - Recommendation: `runGameTestServer` launches a **dedicated** server under the hood; the SP path uses exactly the same `SimpleChannel` handler on the integrated server side, so the same test covers both — document this equivalence in PLAN.md. If the reviewer pushes back, add a second GameTest that invokes the handler inside `IntegratedServer` initialization (cheap).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 17 (Temurin) | All Gradle + MC runs | ✓ | 17 (assumed; platform CLAUDE.md says Java 17) | — |
| Gradle wrapper | All builds | ✓ (ships with MDK) | 8.1.1 (MDK-pinned) | — |
| Internet (maven.minecraftforge.net, files.minecraftforge.net, libraries.minecraft.net, Parchment maven) | First-time Forge setup + MDK zip download | ✓ (assumed) | — | If blocked, bootstrap fails — this is a known MDK prerequisite. |
| Git | commit_docs pipeline | ✓ | — | — |
| GitHub Actions (Ubuntu + Java 17 runners) | D-29 CI | ✓ (public GHA runners) | ubuntu-22.04, temurin-17 | GitLab CI / self-hosted if user switches CI provider. |
| IntelliJ IDEA | D-01 dev-ergonomics (`genIntellijRuns`) | ✓ (user cwd confirms `IdeaProjects/`) | — | VS Code + Java plugin works but skips `genIntellijRuns`. |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None identified.

Phase 1 has no runtime dependency on AI providers, CurseForge, or external services — all outbound HTTP is deferred to Phase 2.

## Sources

### Primary (HIGH confidence)
- [Downloads for Minecraft Forge for Minecraft 1.20.1](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) — MDK URL pattern.
- [Forge Documentation: Getting Started (1.20.1)](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/) — MDK layout, Java 17 requirement.
- [Forge Documentation: The Mod Files (1.20.1)](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/modfiles/) — `mods.toml` fields including `displayURL`.
- [Forge Documentation: SimpleImpl](https://docs.minecraftforge.net/en/latest/networking/simpleimpl/) — SimpleChannel + messageBuilder + consumerMainThread/NetworkThread.
- [ParchmentMC: Getting Started](https://parchmentmc.org/docs/getting-started) — Parchment plugin ID + date format.
- [Maven Repository: org.jsoup » jsoup » 1.17.2](https://mvnrepository.com/artifact/org.jsoup/jsoup/1.17.2) — version + release date.
- [Minecraft Wiki: Pack format](https://minecraft.wiki/w/Pack_format) — `pack_format = 15` for 1.20/1.20.1.

### Secondary (MEDIUM confidence)
- [Forge plugin ID / version range confirmation](https://forums.minecraftforge.net/topic/154423-could-not-apply-requested-plugin-id-netminecraftforgegradle-version-6062/) — `id 'net.minecraftforge.gradle' version '[6.0,6.2)'`.
- [Gemwire Forge Community Wiki: SimpleChannel](https://forge.gemwire.uk/wiki/SimpleChannel) — SimpleChannel pattern reference.
- [Forge Documentation: Jar-in-Jar (fg-5.x)](https://docs.minecraftforge.net/en/fg-5.x/dependencies/jarinjar/) — `jarJar.enable()`, `jarJar.ranged(...)`.
- [SizableShrimp GameTest gist](https://gist.github.com/SizableShrimp/60ad4109e3d0a23107a546b3bc0d9752) — GameTest framework on Forge.
- [Forge Documentation: Game Tests (1.18.x)](https://docs.minecraftforge.net/en/1.18.x/misc/gametest/) — `@GameTest`, `@GameTestHolder`, `runGameTestServer`.
- [Log4j2 Manual: Extending (Filters)](https://logging.apache.org/log4j/2.x/manual/extending.html#Filters) — custom filter skeleton.
- [JDK-8144566: Custom HostnameVerifier disables SNI](https://bugs.openjdk.org/browse/JDK-8144566) — the SNI workaround foundation.
- [Alibaba HTTPDNS: Android HTTPS + SNI](https://www.alibabacloud.com/help/en/httpdns/latest/connect-an-android-app-to-an-ip-address-over-https) — canonical IP-pinning + SNI workaround using `SSLSocketFactory.setSNIHostName` before handshake.

### Tertiary (LOW confidence — flagged for validation)
- [NeoForged: Networking Rework](https://neoforged.net/news/20.4networking-rework/) — only used to confirm the negative (that ChannelBuilder is post-1.20.1).
- Assumption A1 (2 MiB Netty packet ceiling) — not explicitly verified this session.

## Metadata

**Confidence breakdown:**
- Standard stack (Forge/Gradle/FG versions, Parchment, jsoup pin): HIGH — all pinned by locked decisions or externally verified.
- Architecture (@Mod bootstrap, package layout, dist firewall, aiExecutor lifecycle): HIGH — CLAUDE.md + Forge docs agree.
- SimpleChannel + FriendlyByteBuf patterns: HIGH — multiple sources verified including official Forge docs + Gemwire.
- ForgeConfigSpec + Brigadier reload command: HIGH — direct Forge API usage.
- Log4j2 filter plugin: MEDIUM — skeleton verified; rewrite semantics under-documented for Root logger use; Open Question 1 flagged.
- SafeHttpFetcher SNI workaround: MEDIUM — workaround is documented (Alibaba guide) and the JDK bug is real, but our specific code path warrants the Phase-1 test against a known Cloudflare-fronted host (Assumption A4).
- GameTest for packet round-trip: MEDIUM — shape reasoned from docs; needs a Wave-0-style spike (Open Question 2).
- Pitfalls: HIGH — all anchored to CLAUDE.md or sourced verifications.

**Research date:** 2026-04-15
**Valid until:** 2026-05-15 (30 days for stable Forge 1.20.1 ecosystem). The one fast-moving item is jsoup (1.22.x line on Maven Central); our D-09 pins 1.17.x so version drift is irrelevant until Phase 2.
