# Phase 1: Foundations & Safe Egress - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-15
**Phase:** 01-foundations-safe-egress
**Areas discussed:** MDK bootstrap & Forge pin, jsoup bundling, SafeHttpFetcher DNS rebinding defense, Reload + executor + log-scrubber plumbing

---

## MDK Bootstrap & Forge Pin

### How should the Forge MDK be brought into the repo?

| Option | Description | Selected |
|--------|-------------|----------|
| Claude downloads & extracts | Plan task fetches MDK zip from maven.minecraftforge.net, extracts into repo root, deletes example mod + MDK-only docs, commits. Reproducible. | ✓ |
| User extracts, Claude adapts | User drops MDK zip contents into repo manually; plan assumes files exist. | |
| Hand-author from scratch | No MDK; author build.gradle, settings.gradle, wrapper, mods.toml by hand. | |

**User's choice:** Claude downloads & extracts.
**Notes:** Initially asked for an explanation of what the MDK is before answering; got the explanation, then picked the recommended option.

---

### Which Forge 1.20.1 build should we pin?

| Option | Description | Selected |
|--------|-------------|----------|
| 47.4.18 | Keeps the user's existing PROJECT.md / ROADMAP.md pin. | ✓ |
| 47.4.10 | Documented 'Recommended' build on files.minecraftforge.net. | |
| 47.4.20 | Latest build on files.minecraftforge.net. | |

**User's choice:** 47.4.18.

---

### Parchment mappings version?

| Option | Description | Selected |
|--------|-------------|----------|
| parchment-2023.09.03-1.20.1 | Latest stable 1.20.1 parchment release per research. | ✓ |
| Official Mojang (no Parchment) | Zero unofficial deps; accept p_60999_-style parameter names. | |

**User's choice:** parchment-2023.09.03-1.20.1.

---

### Where does the Gradle wrapper come from?

| Option | Description | Selected |
|--------|-------------|----------|
| MDK-shipped wrapper (8.1.1) | Known-good with ForgeGradle 6.0.x. | ✓ |
| Bump to 8.3.x | Newer Gradle; still in FG6-compatible range. | |

**User's choice:** MDK-shipped 8.1.1.

---

## jsoup Bundling

### Should jsoup be bundled in Phase 1 at all, or deferred to Phase 2?

| Option | Description | Selected |
|--------|-------------|----------|
| Bundle in Phase 1 | De-risks the build-system surprise for Phase 2. | ✓ |
| Defer to Phase 2 | Keeps Phase 1 Gradle config minimal. | |

**User's choice:** Bundle in Phase 1.

---

### Which bundling mechanism for jsoup?

| Option | Description | Selected |
|--------|-------------|----------|
| Forge jarJar | Forge's first-class nested-jar mechanism. CLAUDE.md flags shadow as risky. | ✓ |
| Shadow plugin | johnrengelman/shadow as written in SCAF-05. | |
| Both: shadow for relocation + jarJar for nesting | Hybrid; most robust, most moving parts. | |

**User's choice:** Forge jarJar.
**Notes:** SCAF-05 requirement text carries a spec drift (says "shadow"); will be corrected at phase completion.

---

### Relocation target package?

| Option | Description | Selected |
|--------|-------------|----------|
| com.forgebook.shadow.jsoup | Matches SCAF-05 convention; avoids class-path collisions. | ✓ |
| No relocation | Ship jsoup under org.jsoup. | |

**User's choice:** com.forgebook.shadow.jsoup.

---

### Do we document a jsoup version pin?

| Option | Description | Selected |
|--------|-------------|----------|
| Pin to specific stable release | Upgrades are intentional; no transitive surprises. | ✓ |
| Use dynamic [1.17,) | Always-latest; rebuilds can break subtly. | |

**User's choice:** Pin to specific stable release (planner chooses exact 1.17.x patch at planning time).

---

## SafeHttpFetcher DNS Rebinding Defense

### How should SafeHttpFetcher handle DNS rebinding attacks?

| Option | Description | Selected |
|--------|-------------|----------|
| Resolve-and-pin per request | Resolve → check → connect to pinned IP with Host header set to original hostname. Immune to DNS rebinding. | ✓ |
| Re-resolve per redirect only | Let HttpClient re-lookup each hop; check IP each time but don't pin. | |
| Re-resolve + timing-bounded check | Hybrid: resolve/check/connect, reject on mismatch. | |

**User's choice:** Resolve-and-pin per request.
**Notes:** Requires custom JDK-17 plumbing; planner researches the cleanest path.

---

### What's the redirect-following strategy?

| Option | Description | Selected |
|--------|-------------|----------|
| Manual redirect loop in SafeHttpFetcher | Disable auto-redirect; iterate up to 3 times with full re-validation each hop. | ✓ |
| HttpClient.Redirect.NORMAL + post-hoc check | Let HttpClient follow; inspect previousResponse chain after. | |

**User's choice:** Manual redirect loop.

---

### What's the failure-mode vocabulary?

| Option | Description | Selected |
|--------|-------------|----------|
| Enum of violation reasons | UnsafeUrlException carries enum: SCHEME, PRIVATE_IP, REDIRECT_LIMIT, SIZE_CAP, CONTENT_TYPE, TIMEOUT. | ✓ |
| String error messages | Exception messages as plain strings. | |
| HTTP-style integer codes | Custom ints like 451, 452, 453. | |

**User's choice:** Enum of violation reasons.

---

### Where does the IP blocklist live?

| Option | Description | Selected |
|--------|-------------|----------|
| Hard-coded in SafeHttpFetcher | CIDR ranges compiled in as constants; operators can't weaken via config. | ✓ |
| Hard-coded + config extension | Defaults + TOML lets operators ADD (never remove) more blocked ranges. | |
| Fully configurable | Entire list in TOML. | |

**User's choice:** Hard-coded in SafeHttpFetcher.

---

## Reload + Executor + Log-Scrubber Plumbing

### What triggers a config reload?

| Option | Description | Selected |
|--------|-------------|----------|
| /forgebook reload command only | Operators explicitly opt in via command. Satisfies CFG-07 literally. | ✓ |
| Command + ModConfigEvent.Reloading (file watch) | Both file edits and command trigger reload. | |
| File watch only | Drop /forgebook reload. | |

**User's choice:** /forgebook reload command only.
**Notes:** File-watch reload deferred to v2 quality-of-life if operators request it.

---

### aiExecutor thread count?

| Option | Description | Selected |
|--------|-------------|----------|
| Fixed 4 threads | Concurrency = 4; saturates ~12–20 concurrent players at default rate limit. | ✓ |
| Fixed 8 threads | Higher headroom for 30+ concurrent chatters. | |
| Cached thread pool (unbounded) | Grows to demand; removes backpressure signal. | |

**User's choice:** Fixed 4 threads.

---

### aiExecutor queue + rejection policy?

| Option | Description | Selected |
|--------|-------------|----------|
| Bounded queue (64) + CallerRuns | Run on submitting thread when queue full. | |
| Bounded queue (64) + reject with ChatError | Return OVERLOADED ChatErrorPacket when queue full. | ✓ |
| Unbounded queue | Never rejects; unbounded memory risk. | |

**User's choice:** Bounded queue (64) + reject with ChatError.

---

### Log scrubber mechanism?

| Option | Description | Selected |
|--------|-------------|----------|
| Log4j2 filter plugin | Global RewritePolicy/Filter in log4j2.xml; scrubs every log line. | ✓ |
| SLF4J wrapper at AI/HTTP call sites only | Redact only at specific call sites. | |
| Both: Log4j2 filter + call-site care | Belt-and-suspenders. | |

**User's choice:** Log4j2 filter plugin.

---

## Claude's Discretion

- Exact relocation task implementation (Gradle custom task vs shadow intermediate jar).
- Specific jsoup patch version in the 1.17.x line.
- CI provider (default GitHub Actions) and workflow YAML structure.
- Java package organization under `com.forgebook.network` (`.packet` / `.handler` subpackages).
- log4j2.xml filter plugin class location and registration syntax.
- Thread-naming pattern and daemon flag details, consistent with D-20 intent.

## Deferred Ideas

- File-watch config reload via `ModConfigEvent.Reloading` (v2 QoL).
- Operator-extensible IP blocklist (v2).
- Per-server daily token cap (already V2-SAFE-01).
- Streaming responses (already V2-UX-01).
- Multiple concurrent AI providers / per-request provider selection.
- Gradle 8.3+ / 8.4+ upgrade.
