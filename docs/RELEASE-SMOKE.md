# ForgeBook Release Smoke Protocol

**Before tagging a release, walk this protocol from a clean working tree.**

This protocol catches issues invisible to the dev loop: `./gradlew runClient` / `runServer` run from the exploded classpath, NOT the built jar. The bundled jsoup (relocated + jarJar'd) and the SimpleChannel wiring only get exercised in their final form when the built jar is loaded by a stock Forge installation.

## Prerequisites

- A disposable Minecraft 1.20.1 installation (NOT your main launcher).
- A disposable Forge 1.20.1-47.4.18 dedicated server installed to a fresh folder.
- An Anthropic API key (test key or main key — rate limits apply; a single `/forgebook item` costs about $0.01 at current Haiku pricing).

## KNOWN BLOCKER — jarJar task is SKIPPED (pre-existing Phase 1 defect)

> **STOP — read before running this protocol.**
>
> As of the docs/RELEASE-SMOKE.md authoring date (Phase 5, Plan 05-06), the `jarJar` Gradle task is disabled in the production build. `./gradlew build` produces `build/libs/forgebook-1.0.0.jar`, but the relocated jsoup jar-in-jar is **not** nested inside it. This was discovered during Plan 05-01 execution and was out of scope for that plan — see `.planning/phases/05-release-polish/05-01-SUMMARY.md` "Issues Encountered — Deferred" for the full investigation.
>
> **Evidence on the current tree:**
> ```
> jar tf build/libs/forgebook-1.0.0.jar | grep -E 'jarjar|com/forgebook/shadow/jsoup'
> # returns ZERO lines
> ```
> The `relocateJsoup` task succeeds and produces `build/relocated/jsoup-relocated-<ver>.jar`, but the `jarJar` task logs: `Skipping task ':jarJar' as task onlyIf 'Task is enabled' is false.`
>
> **What this means for this smoke protocol:**
> - **Step 1 automated check will FAIL** (the `jar tf | grep jsoup` assertion is explicit).
> - **Steps 2-9 will FAIL at Step 5 or Step 6** — the first time a command triggers `ModDocsScraper.extract(...)`, the server will throw `NoClassDefFoundError: com/forgebook/shadow/jsoup/...`. The AI request pipeline will short-circuit to an error card.
>
> **Disposition:** This is a **blocking prerequisite for REL-05 physical smoke**. Do NOT attempt Steps 2-9 on a clean dedicated server until a dedicated fix-plan (tracked separately — not part of Phase 5 scope) wires `jarJar` into the build lifecycle correctly. Candidate fixes to investigate (for the author of that fix-plan):
> - (a) ForgeGradle 6's `jarJar` task may require `jarJar` dependency declarations with an explicit version range (e.g. `jarJar(group: 'com.forgebook.shadow', name: 'jsoup-relocated', version: '[1.17,2.0)')`) rather than a raw `files(...)` reference.
> - (b) The `reobfJarJar SKIPPED` log line (adjacent to `jarJar SKIPPED`) suggests the task needs reobfuscation wiring and participation in the `assemble` lifecycle.
> - (c) See `build.gradle` L53-67 for the current (broken) wiring; compare against the ForgeGradle 6 jarJar docs.
>
> Until the fix lands, this protocol documents the intended smoke so a release operator can run it the moment the blocker clears. The automated Step 1 check below is intentionally written so that its failure on the current tree is the signal that the fix is still outstanding.

## Step 1 — Clean build (AUTOMATED — Claude can run this)

```bash
./gradlew clean build --no-daemon
ls build/libs/forgebook-1.0.0.jar     # must exist and be >50 KB
jar tf build/libs/forgebook-1.0.0.jar | grep -E "META-INF/jarjar/.*jsoup.*\.jar"   # must show nested META-INF/jarjar/*.jar with jsoup
jar tf build/libs/forgebook-1.0.0.jar | grep -E "jarjar|com/forgebook/shadow/jsoup" | head   # sanity: at least one hit (nested META-INF/jarjar/ entry OR relocated classes)
```

**Expected:**
- `./gradlew clean build` exits 0.
- `build/libs/forgebook-1.0.0.jar` exists and is >50 KB.
- The first `jar tf` grep returns at least one line matching `META-INF/jarjar/jsoup-relocated-<version>.jar` (the jsoup jar-in-jar is nested).
- The second `jar tf` grep returns at least one line (sanity fallback in case the filename pattern of the nested jar changes across ForgeGradle versions — either a `META-INF/jarjar/` entry or the relocated `com/forgebook/shadow/jsoup/` classes must be observable).

**Current known status:** On the tree as of 2026-04-16 (post-Plan 05-06), **this step FAILS** — see "KNOWN BLOCKER" section above. The `./gradlew clean build` half succeeds (produces `forgebook-1.0.0.jar`); the `jar tf | grep jsoup` half returns zero matches because the `jarJar` task is disabled. **Do not proceed to Step 2 until this step goes green.**

## Step 2 — Dedicated server install (HUMAN)

1. Fresh Forge 1.20.1-47.4.18 dedicated server folder. Start once with no mods to generate the EULA; accept it (edit `eula.txt`, set `eula=true`).
2. Stop the server.
3. Copy `build/libs/forgebook-1.0.0.jar` into the server's `mods/` folder.
4. Start the server.

**Expected in console / `logs/latest.log`:**
- `Loading mod 'forgebook' (version 1.0.0)` appears.
- No `NoClassDefFoundError` referencing `com.forgebook.shadow.jsoup.*` classes. (If the jarJar blocker above is unresolved, this WILL fire the first time ModDocsScraper is invoked — i.e. at Step 5.)
- No client-classloader leakage — nothing about `net.minecraft.client.*` being loaded (firewall is holding).
- Log lines confirming: `ForgebookNetwork` channel `forgebook:main` registered, `ConfigHolder` loaded a snapshot, `SystemPromptBuilder.buildAndCache` completed, `RateLimiterHolder.swap` seeded.

## Step 3 — Set secrets (HUMAN)

1. Stop the server.
2. Edit `config/forgebook-server.toml`:
   ```toml
   ai_api_key = "sk-ant-..."
   ```
3. Restrict file permissions:
   ```bash
   chmod 600 config/forgebook-server.toml
   ```
   On Windows, set the NTFS ACL so only the server-running account can read the file.
4. Restart the server.
5. After startup, verify secret leakage is scrubbed:
   ```bash
   grep "sk-ant-" logs/latest.log
   ```
   **Expected:** zero matching lines. The `ApiKeyScrubFilter` (Phase 1 CFG-05) redacts all `sk-ant-` prefixed substrings from logs.

## Step 4 — Client connect (HUMAN)

1. In your disposable Minecraft 1.20.1 launcher, install Forge 47.4.18.
2. Copy the SAME `forgebook-1.0.0.jar` into the client's `mods/` folder.
3. Launch the client; connect to the dedicated server (`localhost` or the server's IP).
4. Op your player from the server console: `op <yourname>`.

**Expected:** client joins cleanly; no "mod mismatch" disconnect; chat shows no red error messages.

## Step 5 — Smoke `/forgebook item` (HUMAN)

1. In-game, give yourself a diamond pickaxe: `/give <yourname> minecraft:diamond_pickaxe`.
2. Hold the pickaxe.
3. Run `/forgebook item`.

**Expected:**
- An AI reply appears in chat within 5-15 seconds explaining the item.
- A `Source: <url>` citation appears at the bottom of the reply (CMD-07).
- On the server: `logs/latest.log` contains exactly ONE `[forgebook.audit]` line with the shape:
  `uuid=<your-uuid> kind=ITEM tokens=<positive> latency_ms=<positive> outcome=SUCCESS`.

> **Jsoup canary:** if the jarJar blocker (top of this doc) is unresolved, this is where it surfaces — the server will log `NoClassDefFoundError: com/forgebook/shadow/jsoup/Jsoup` (or similar) and the response will arrive as an ErrorCard with code `TRANSPORT` or `INTERNAL` rather than a useful reply.

## Step 6 — Smoke the chat UI (HUMAN)

1. Open inventory (`E`). Verify the "Ask ForgeBook" button appears to the right of the inventory.
2. Click the button. Verify `ChatScreen` opens next to (not replacing) the inventory.
3. Type "What is a diamond pickaxe?" and press Enter.
4. Verify a loading indicator appears, followed by an assistant reply bubble.
5. Close the screen (ESC). Re-open the inventory, click the button — verify the conversation is empty (UI-05 session clear).

## Step 7 — Smoke `/forgebook disable` + `/forgebook enable` (HUMAN)

1. `/forgebook disable` → expect `ForgeBook disabled. New requests will return DISABLED.` (via i18n key `forgebook.command.disable.success` post-Plan 05-04).
2. `/forgebook item` → expect `ForgeBook is temporarily disabled by an operator.` (via `forgebook.command.denied.disabled`).
3. `/forgebook enable` → expect `ForgeBook enabled. New requests will be processed.`.
4. `/forgebook item` → expect normal AI reply (kill switch released).

## Step 8 — Smoke `/forgebook stats` and `/forgebook reload` (HUMAN)

1. `/forgebook stats` → expect a table with at least your request counted (and estimated tokens / latency).
2. Edit `config/forgebook-server.toml` — change `rate_limit_per_minute` to `1`.
3. `/forgebook reload` → expect `ForgeBook config + system prompt reloaded.` (via `forgebook.command.reload.success`).
4. As a non-OP player (de-op yourself or ask a second account): run `/forgebook ask hi` twice in quick succession.
   **Expected:** the second call is RATE_LIMITED with a retry-after message — `Rate limit reached. Try again in Ns.` (via `forgebook.command.denied.rate_limited`).

## Step 9 — Teardown (HUMAN)

- Stop the client and server.
- **Delete the API key from `config/forgebook-server.toml` before archiving**.
- Delete the disposable server folder and client instance.

## Step 10 — Tag and publish release (HUMAN, post-smoke)

Only run this step if Steps 1-9 all PASSED. If any step FAILED, see "Pass / Fail Criteria" below — tag as `v1.0.0-rcN` instead.

```bash
# From repo root, on a clean main branch at the exact commit that produced the smoked jar:
git tag -a v1.0.0 -m "ForgeBook 1.0.0"
git push origin v1.0.0

# Create the GitHub release with the smoked jar attached:
gh release create v1.0.0 \
    build/libs/forgebook-1.0.0.jar \
    --title "ForgeBook 1.0.0" \
    --notes "See CHANGELOG.md for highlights. See docs/RELEASE-SMOKE.md for the release verification protocol."
```

**Expected:**
- `v1.0.0` tag visible at `https://github.com/<owner>/ForgeBook/tags`.
- GitHub release published with `forgebook-1.0.0.jar` as a downloadable asset.
- No other jars on the release (do not attach `build/libs/forgebook-1.0.0-sources.jar` — that's a dev artifact).

## Pass / Fail Criteria

**PASS** if every "Expected" line matches observed behaviour AND no stack traces AND no secret leakage (Step 3 grep returns zero).

**FAIL** = tag the release candidate as `v1.0.0-rc<N>` (not `v1.0.0`); file GitHub issues for each failure; fix; re-run this protocol from Step 1.

## What's automated vs. human

| Step | Automated? | Why |
|------|-----------|-----|
| 1 — Clean build + jar-integrity | Yes | Pure Gradle + `jar tf`; no game needed. Claude can run this. |
| 2-9 — Everything else | No | Needs a disposable MC launcher, disposable Forge server, real Anthropic API key, visual in-game verification. |
| 10 — Tag + `gh release create` | Partial | The `git tag` + `gh release create` commands are scriptable, but the *decision* to tag v1.0.0 vs v1.0.0-rcN comes from the human operator's Steps 2-9 PASS/FAIL verdict. |

Under GSD `--auto` chain, Steps 2-10 are deferred to a human operator; the physical smoke happens pre-tag (can be minutes before `git tag v1.0.0`).
