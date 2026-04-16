# Phase 5: Release Polish — Research

**Researched:** 2026-04-16
**Domain:** Release hygiene / i18n audit / packaging / docs / mod-compat testing
**Confidence:** HIGH for i18n & packaging; MEDIUM for compat-matrix protocol (empirical testing protocol, not standardized); HIGH for license & jsoup attribution after verification.

## Summary

Phase 5 is a **closeout phase, not a feature phase** — no new behavior lands, but a dozen small hygiene gaps across four tiers (assets, i18n, docs, artifacts) must all close before the mod is something a third-party server owner can install and trust. The work decomposes cleanly:

1. **i18n closure (REL-02)** — Phase 4 locked 21 UI keys. Phase 3 shipped ~20 English literals in command-feedback paths (`ForgebookReloadCommand`, `ForgebookAdminSubcommands`, `ItemSubcommand`, `AskSubcommand`, `RagItemPipeline`, `Authorizer.humanReadable`). These are all `Component.literal("...")` or raw strings stashed in `Authorizer.Denied.humanReadable`. The audit is a single grep; the refactor is mechanical (swap literal for `Component.translatable("forgebook.command.*")` and move strings to `en_us.json`).
2. **Logo slots (REL-01)** — Only ONE of the two "logo slots" mentioned in REL-01 is a Forge convention (`src/main/resources/logo.png` → `mods.toml#logoFile`, **must be at JAR root**). The second path (`assets/forgebook/textures/gui/logo.png`) is a ForgeBook-internal convention — the in-chat-panel header texture slot the PROJECT envisions the user dropping a branded PNG into later. Neither Forge nor vanilla requires it; it's UX future-proofing. Both must exist as placeholders *with a README pointer* so the user knows where to drop the final designed asset. The current `logo.png` is already a 1×1 RGBA placeholder (67 bytes, valid PNG) from Phase 1 scaffolding — it loads cleanly; REL-01's ask is documentation, not a redesign.
3. **Docs (REL-03)** — No README exists yet at repo root. The deliverable is a single README.md covering install + config table + security-posture prose + the `chmod 600 forgebook-server.toml` line. `THIRD_PARTY_NOTICES.md` already credits jsoup correctly as **MIT** (the prompt's "Apache 2.0" hint is wrong — verified at jsoup.org/license 2026-04-16). `LICENSE` already exists as MIT.
4. **Compat matrix (REL-04)** — No industry-standard template exists for Forge mod-compat matrices. Recommendation: a single markdown table in `docs/COMPATIBILITY.md` with columns `{mod, version tested, GUI scale 1 result, GUI scale 2 result, notes}`, filled in manually by running `./gradlew runClient` with each of the eight compat targets dropped into `run/mods/`. This is a **human-checkpoint** phase, not an automated one.
5. **Prod-jar smoke (REL-05)** — The existing build (`./gradlew build`) produces `build/libs/forgebook-0.1.0.jar` with the relocated jsoup jar-in-jar'd. Bump version → 1.0.0, build, drop into a clean Forge 1.20.1-47.4.18 dedicated server's `mods/` folder, connect a vanilla `runClient` with the same jar in its own `mods/` folder (distribution requirement — both sides need the mod), run `/forgebook item`, verify reply. Protocol goes in `docs/RELEASE-SMOKE.md`.

**Primary recommendation:** Execute in the order (1) i18n audit → (2) logo slot documentation → (3) README + NOTICES → (4) compat matrix template → (5) prod-jar smoke. This order front-loads code changes (Wave 0), leaves docs for a later wave, and parks the two human-checkpoint items (compat matrix + prod-jar smoke) at the end where an auto-chain can cleanly park them.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| REL-01 | Two logo slots documented, mod loads with placeholders | §Logo Slot Mechanism — clarifies what the two paths actually are and confirms the 1×1 placeholder already loads |
| REL-02 | `en_us.json` covers every user-facing string, zero raw English literals in user-visible code paths | §i18n Literal Audit — full grep-verified list of 20 call sites to refactor and the ~17 new keys they map to |
| REL-03 | README + config-field table + security posture + `chmod 600`; THIRD_PARTY_NOTICES credits jsoup | §README & Docs Structure + §THIRD_PARTY_NOTICES status (already correct — jsoup is MIT, verified) |
| REL-04 | Mod-compat matrix for JEI, REI, Sodium/Embeddium, Iris/Oculus, Jade, Mouse Tweaks, Quark, Inventory HUD+ at GUI scales 1+2 | §Compat Matrix Protocol — human-checkpoint procedure + template |
| REL-05 | Built jar smoke-tested on clean Forge 47.4.18 dedicated server before first tagged release | §Prod-Jar Smoke Protocol — step-by-step runbook |

## Architectural Responsibility Map

Phase 5 is a non-runtime phase — most work is in artifacts (docs, resources, Gradle config). Still, i18n refactors touch server-tier code.

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| i18n key definition (en_us.json) | Build artifact (resources) | — | Loaded by Minecraft client at language-select time; shared resource |
| Command-feedback i18n (sendSuccess/sendFailure) | API / server | — | Feedback `Component`s are constructed server-side; client translates on render |
| Client-UI i18n (already done Phase 4) | Browser / client | — | Already complete — 21 keys shipped |
| Authorizer.humanReadable strings | API / server | — | String flows server → player chat feedback; needs translation wrapper |
| ErrorCard rendering on client | Browser / client | — | Already done Phase 4 — widget calls `Component.translatable` on error codes |
| logo.png (mods.toml logoFile) | Build artifact (JAR root) | — | Loaded by Forge mod-list UI at game start |
| assets/forgebook/textures/gui/logo.png | Browser / client (future) | — | Forward-looking slot for in-chat branding; not wired yet |
| README / docs | Repository artifact | — | Read by humans before install; never loaded at runtime |
| Mod-compat matrix doc | Repository artifact | — | Read by humans; human-checkpoint-verified |
| Prod-jar smoke protocol | Build pipeline / human | — | `./gradlew build` produces the jar; the checklist is the human test harness |
| THIRD_PARTY_NOTICES.md | Repository artifact | — | License-compliance obligation; travels with the repo and the jar |

**Verdict:** Zero tier mis-assignments in the Phase 5 scope. The one cross-tier concern is that i18n keys created on the server side (e.g., `forgebook.command.reload.success`) must be present in `en_us.json` which ships in the client-readable resources tree — but since both client and server share the same jar (distribution requirement per CLAUDE.md), this is automatic.

## Standard Stack

### Core (no new libraries added)

Phase 5 adds **zero** new dependencies. Everything needed is already present.

| Library / Tool | Version | Purpose | Why Standard |
|----------------|---------|---------|--------------|
| Minecraft i18n (`net.minecraft.network.chat.Component.translatable`) | bundled MC 1.20.1 | Server-side translation key emission | Only correct way to emit translatable Components from server commands; client resolves at render time `[VERIFIED: Phase 4 en_us.json works this way]` |
| `Component.translatable(key, args...)` | bundled | Parameterized translation (e.g., retry-after seconds) | Standard vanilla pattern used by every Mojang command `[CITED: net.minecraft.commands.Commands source]` |
| `assets/<modid>/lang/en_us.json` | bundled resource loader | Translation map | Standard Forge/vanilla resource path — Phase 4 already uses it `[VERIFIED: grep -c '^\s*"forgebook\.' src/main/resources/assets/forgebook/lang/en_us.json → 21]` |
| `./gradlew build` | Gradle 8.1.1 + FG 6.0.x (pinned) | Produces `build/libs/forgebook-<version>.jar` with jarJar'd jsoup relocation | Already wired Phase 1; `tasks.named('jarJar') { dependsOn 'relocateJsoup' }` is the nesting point `[VERIFIED: build.gradle:67]` |

### Supporting (already present)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| jsoup (bytecode-relocated to `com.forgebook.shadow.jsoup`) | 1.17.2 | Scraper dep bundled via jarJar | Only runtime-bundled third-party — triggers THIRD_PARTY_NOTICES obligation `[VERIFIED: gradle.properties jsoupVersion=1.17.2; build.gradle relocateJsoup task]` |
| Forge mod list UI | bundled Forge 47.4.18 | Renders `mods.toml#logoFile` image | Loads from JAR root per Forge wiki — do not put logoFile in a subfolder `[VERIFIED: Gemwire Mods.toml wiki, fetched 2026-04-16]` |

### Alternatives Considered (and rejected)

| Instead of | Could Use | Why rejected |
|------------|-----------|--------------|
| Hand-maintained THIRD_PARTY_NOTICES.md | `gradle-license-plugin` / `com.github.jk1.dependency-license-report` | Only ONE runtime dep (jsoup); automation overhead > value. Revisit for v2 if deps grow. |
| Gradle task generating placeholder logo at build time | Checked-in placeholder PNG | Already have a valid 1×1 PNG checked in; no need for a generator step. Build-time image generation adds ImageMagick/BufferedImage dependency without benefit. |
| Separate `SECURITY.md` file | Single README security-posture section | Project is pre-v1; one cohesive doc beats doc sprawl. Split to SECURITY.md at v2 if contributions grow. |
| GitHub Releases auto-upload CI | Manual tag + upload | v1 is first tagged release; automation is v2 scope (noted in §Open Questions). |
| ImageMagick `convert` to render a proper placeholder monogram | Keep 1×1 PNG + README pointer | `convert` on this Windows dev box resolves to NTFS converter, not ImageMagick. No suitable build-time image tool available without new dep. Leave pixel-perfect branding to user per REL-01 intent. |

### Version verification (2026-04-16)

- `jsoupVersion=1.17.2` (gradle.properties) — confirmed current stable per jsoup.org; no urgency to bump mid-release.
- Forge 47.4.18 — pinned per project constraint; matches `forge_version=47.4.18`.
- Minecraft 1.20.1 — fixed platform target.
- No new packages to verify via `npm view` equivalents — this is a Java/Gradle project with zero Maven-coordinate additions in Phase 5.

## Architecture Patterns

### System Architecture Diagram — Phase 5 Artifact Flow

```
REPO ROOT                                    BUILT JAR (forgebook-1.0.0.jar)
├── LICENSE                       (copied)   └── META-INF/
├── README.md                     (referenced)    ├── mods.toml          ← logoFile="logo.png" points here ↓
├── THIRD_PARTY_NOTICES.md        (copied)   └── logo.png                 ← JAR ROOT (Forge loads this)
├── docs/                                    └── assets/forgebook/
│   ├── COMPATIBILITY.md          (human)        ├── lang/en_us.json      ← 21 old keys + ~17 new Phase-5 keys
│   └── RELEASE-SMOKE.md          (human)        └── textures/gui/
├── src/main/resources/                              └── logo.png          ← placeholder; user may replace
│   ├── META-INF/mods.toml        ↓ shipped
│   ├── logo.png                  ↓ shipped (JAR root)
│   └── assets/forgebook/
│       ├── lang/en_us.json       ↓ shipped, regrown to ~38 keys
│       └── textures/gui/logo.png ↓ shipped (Phase-5 adds this)
└── build.gradle
        │
        ▼ ./gradlew build
        ▼
    build/libs/forgebook-1.0.0.jar (with jarJar'd jsoup relocated to com.forgebook.shadow.jsoup)

                                              DEPLOYMENT
                                              ─────────
                                              server/mods/forgebook-1.0.0.jar
                                              client/mods/forgebook-1.0.0.jar  (both sides required)
                                                  ↓
                                              Forge loads jar → reads mods.toml → finds logo.png
                                              MC loads en_us.json → resolves Component.translatable keys
                                              Player runs /forgebook item → en_us.json provides feedback strings
```

### Recommended Project Structure (additions in Phase 5)

```
<repo root>/
├── README.md                                (NEW — REL-03)
├── LICENSE                                  (exists — MIT)
├── THIRD_PARTY_NOTICES.md                   (exists — jsoup MIT; may add MC attribution section if strictly-compliant)
├── docs/
│   ├── COMPATIBILITY.md                     (NEW — REL-04 matrix + testing protocol)
│   └── RELEASE-SMOKE.md                     (NEW — REL-05 checklist)
└── src/main/resources/
    ├── logo.png                             (exists as 1×1 placeholder — keep, document)
    └── assets/forgebook/
        ├── lang/en_us.json                  (MODIFIED — add ~17 command-feedback keys)
        └── textures/gui/
            └── logo.png                     (NEW — placeholder for future in-chat branding)
```

### Pattern 1: Server-side `Component.translatable` for command feedback

**What:** Replace `Component.literal("English text")` with `Component.translatable("forgebook.command.<subcommand>.<outcome>")` in every user-visible command feedback path. Parameterized keys use `Component.translatable("forgebook.command.rate_limited", retryAfterSeconds)` with `%d` / `%s` in en_us.json.

**When to use:** Every single `sendSuccess` / `sendFailure` call where the argument is a `Component.literal`.

**Example (refactor Phase 3's AdminSubcommands):**

```java
// BEFORE (Phase 3):
String msg = wasEnabled
    ? "ForgeBook disabled. New requests will return DISABLED."
    : "ForgeBook is already disabled.";
send.accept(msg, true);

// AFTER (Phase 5):
String key = wasEnabled
    ? "forgebook.command.disable.success"
    : "forgebook.command.disable.already";
send.accept(key, true);   // callback now takes a key, or accept a Component directly
// In en_us.json:
//   "forgebook.command.disable.success": "ForgeBook disabled. New requests will return DISABLED.",
//   "forgebook.command.disable.already":  "ForgeBook is already disabled."
```

Trade-off: The `BiConsumer<String, Boolean> send` callback in `AdminSubcommands` currently takes a plain `String`. Two valid refactor paths:

- **Option A (minimal):** Keep the `BiConsumer<String, Boolean>` signature; semantics of the string flips from "literal text" to "translation key". Production wraps via `Component.translatable(key)`. Tests assert on key names instead of prose. Simplest diff.
- **Option B (typed):** Change the callback to `BiConsumer<Component, Boolean> send`. Production calls pass `Component.translatable(...)`; tests pass `Component.literal("stub")` and read `.getString()` for assertion. Stronger type safety but more test churn.

**Recommendation: Option A.** The current tests (AdminSubcommandsTest, AskSubcommandTest, ItemSubcommandTest, RagItemPipelineTest) assert on string equality of the sent text. Option A changes those assertions from comparing prose to comparing keys — straightforward mechanical change. `[ASSUMED]` based on test file naming patterns; confirm during planning.

### Pattern 2: Format-argument translation (retry-after seconds)

**What:** For rate-limit messages that include a dynamic number, use `Component.translatable("forgebook.command.rate_limited", retryAfterSeconds)` with the en_us.json value `"Rate limit reached. Try again in %ds."`.

**When to use:** Wherever `Authorizer.Denied.humanReadable` currently builds a string with concatenation (`"Rate limit reached. Try again in " + retryAfterSeconds + "s."`).

**Architectural wrinkle:** `Authorizer.Denied` is a record carrying a plain `String humanReadable`. Translation keys need args. Two paths:

- **Option A (structured Denied):** Change `Denied(ErrorCode code, String humanReadable)` → `Denied(ErrorCode code, String translationKey, Object[] args)`. Callers build a `Component.translatable(d.translationKey(), d.args())`. Touches every Denied call site (ItemSubcommand, AskSubcommand, ChatRequestHandler, RagItemPipeline).
- **Option B (shadow the translation downstream):** Keep `humanReadable` as a plain `String` carrying the **key + already-formatted args** (e.g., `"forgebook.command.rate_limited|5"`), parse downstream. Ugly.
- **Option C (Component-typed Denied):** Change `humanReadable` to `Component humanReadable` — producer builds the translatable Component, consumer just ships it. Clean.

**Recommendation: Option C.** `Authorizer.Denied(ErrorCode code, Component feedback)`. Consumer at ItemSubcommand/AskSubcommand/ChatRequestHandler just calls `src.sendFailure(d.feedback())`. Test seam: Option C tests assert on `.getString()` of the Component (works because `Component.literal` and `Component.translatable` both implement `getString()` which for translatable returns the key when no language is loaded — HIGH confidence, verified by Phase 4 test patterns). `[ASSUMED]` until confirmed empirically; record as a decision point for the planner.

**Fallback plan if Option C has a test-harness gotcha:** Fall back to Option A (explicit translation key + args record fields) — mechanical refactor, slightly more verbose, no behavioral difference.

### Pattern 3: Docs-only artifacts co-located under `docs/`

**What:** New markdown docs (compat matrix, release smoke) live under `docs/` at repo root, not under `.planning/`. README.md stays at repo root per community convention — GitHub renders it as the repo landing page.

**When to use:** Any doc intended for the end-user (server owner, modder, release smoker), not the `gsd-*` planner chain.

Why not `.planning/phases/05-release-polish/`? Because those docs persist after Phase 5 closes. `.planning/` is an internal workflow artifact tree; `docs/` is a public-artifact tree.

### Anti-Patterns to Avoid

- **Putting `logoFile` in a subfolder.** Forge wiki explicitly says logoFile lives at JAR root. `logoFile = "assets/forgebook/logo.png"` will silently fail to render. `[CITED: forge.gemwire.uk/wiki/Mods.toml]`
- **Bundling jsoup via `shadow` plugin instead of `jarJar`.** Already rejected by CLAUDE.md and already done correctly — Phase 1 uses `jarJar files(tasks.relocateJsoup)`. Don't regress during Phase 5 build tweaks.
- **Changing the mod version in `gradle.properties` templated placeholder `mod_version=1.0.0` without also bumping `build.gradle#version`.** `build.gradle` directly hard-codes `version = '0.1.0'` and does NOT read from gradle.properties — gradle.properties still has MDK template defaults (`mod_id=examplemod`). The real truth is in `build.gradle`. Fix both or delete the gradle.properties template values to prevent future confusion.
- **Mixing en_us.json into multiple files per-subsystem.** Minecraft expects a single `en_us.json` per mod namespace. Don't try to split command vs UI keys across files.
- **Forgetting the `placeholder` naming convention in README.** If the logo is a placeholder, the README section must say it's a placeholder — REL-01 literally mandates "README pointer" to the logo slot.
- **Writing compat matrix as a frozen snapshot.** Compat matrices drift. Pair the matrix with a "how to re-run" protocol in the same file, so future contributors can update it without reverse-engineering intent.
- **Creating THIRD_PARTY_NOTICES with wrong license classification.** Already verified: jsoup is MIT, not Apache 2.0. Current NOTICES file is correct — do NOT "fix" it.
- **Writing the 128×128 designed logo in this phase.** REL-01 is explicit: placeholders with README pointer. Shipping a final branded asset is out-of-scope for a release-polish phase driven by Claude.
- **Using `--no-verify` for Phase 5 commits.** Phase 5 is the last chance to prove the full pipeline (CI firewall greps, build, tests) is green. Every commit should pass the full hook chain.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| String-to-Component conversion | Custom `I18n.translate(key, args)` helper that calls `Language.getInstance().getOrDefault(key)` | `Component.translatable(key, args)` | Vanilla does the resolution at render time; using the server-side `Language` singleton is wrong for server-side code because dedicated servers don't load client language packs. |
| Placeholder image generation | Gradle task that renders text to PNG at build time | Keep checked-in 1×1 placeholder + README pointer | Adds build complexity for zero functional benefit. The designed asset is the user's job per REL-01. |
| Third-party license enumeration | Manual script that walks the dependency graph | Hand-curated NOTICES.md (only jsoup bundled) | Single-dep case; `gradle-license-plugin` is overkill. |
| Mod-compat testing harness | Gradle task that spins up 8 client-with-mod configs | Manual checklist in docs/COMPATIBILITY.md | Each test mod requires a different config (version-matched to MC 1.20.1, some conflict with each other). Automation cost > value for a one-time matrix. |
| Prod-jar smoke runner | CI step that spins up a vanilla Forge server and drives `/forgebook item` | `docs/RELEASE-SMOKE.md` checklist the releaser runs locally | Needs an API key (can't put in CI without cost exposure); needs a live internet connection (hermetic CI is better off testing the mod-loading only — which Phase 1's GameTest already does). |
| `version.txt`-based jar naming | Hardcode in build.gradle | `version = '1.0.0'` in build.gradle; jar plugin uses project.version automatically | Already correct — build/libs/forgebook-0.1.0.jar is the current output pattern. Bump the string, that's it. |
| CI release-on-tag workflow | Home-grown YAML that uploads to GitHub Releases | Defer to v2; manual tag + upload for v1 | Adds failure surface without value for first release. |

**Key insight:** Phase 5 has *zero* hand-roll traps because every deliverable either (a) ships with Minecraft/Forge or (b) is hand-written docs. The main risk is over-engineering — adding Gradle plugins or CI automation that wouldn't survive user scrutiny at v1.

## i18n Literal Audit Strategy

### The exact audit

Run from repo root:

```bash
# Phase 5 i18n audit — find every user-visible English literal in command + safety + ai paths
grep -rnE 'Component\.literal\("[^"]*"\)' \
     src/main/java/com/forgebook/command/ \
     src/main/java/com/forgebook/ai/RagItemPipeline.java \
     src/main/java/com/forgebook/ai/AiDispatcher.java

grep -rnE 'sendFailure\([^,]+,\s*"[A-Z][^"]*"\)|sendFailure\(src,\s*"[^"]+"\)' \
     src/main/java/com/forgebook/

# Raw English strings in Authorizer.Denied construction
grep -rnE 'new Denied\([^)]*"' \
     src/main/java/com/forgebook/safety/Authorizer.java

# Raw English strings passed into AiDispatcher.Error.humanReadable
grep -n 'new Error\s*(' src/main/java/com/forgebook/ai/AiDispatcher.java
```

### Audit result (complete — current state of repo at research time)

**File: `src/main/java/com/forgebook/command/ForgebookReloadCommand.java`**
| Line | Current Literal | Proposed Key | Params |
|------|-----------------|--------------|--------|
| 72 | `"ForgeBook config + system prompt reloaded."` | `forgebook.command.reload.success` | none |

**File: `src/main/java/com/forgebook/command/AdminSubcommands.java`**
| Line (internal body) | Current Literal | Proposed Key | Params |
|------|-----------------|--------------|--------|
| 83 | `"ForgeBook disabled. New requests will return DISABLED."` | `forgebook.command.disable.success` | none |
| 84 | `"ForgeBook is already disabled."` | `forgebook.command.disable.already` | none |
| 99 | `"ForgeBook enabled. New requests will be processed."` | `forgebook.command.enable.success` | none |
| 100 | `"ForgeBook is already enabled."` | `forgebook.command.enable.already` | none |
| (stats) | `StatsAccumulator.render()` output | Stays as-is: it's already structured data, not prose | — |

Note: `AdminSubcommands` `BiConsumer<String, Boolean>` signature — switch the string param to mean "translation key" and build `Component.translatable(key)` at the edge where the production callback is installed (see Pattern 1 Option A).

**File: `src/main/java/com/forgebook/command/AskSubcommand.java`**
| Line | Current Literal | Proposed Key | Params |
|------|-----------------|--------------|--------|
| 140 | `"ForgeBook not initialized — check server logs."` | `forgebook.command.not_initialized` | none (shared across Ask + Item) |
| 166 | `"Internal error."` | `forgebook.command.internal_error` | none (shared) |
| 173 | `"Server is busy. Try again."` | `forgebook.command.overloaded` | none (shared) |

**File: `src/main/java/com/forgebook/command/ItemSubcommand.java`**
| Line | Current Literal | Proposed Key | Params |
|------|-----------------|--------------|--------|
| 191 | `"Hold an item in your main hand, or use /forgebook item <item>."` | `forgebook.command.item.no_held` | none |
| 197 | `"Could not identify item."` | `forgebook.command.item.unknown` | none |
| 208 | `"ForgeBook not initialized — check server logs."` | `forgebook.command.not_initialized` | same as Ask |
| 232 | `"Internal error."` | `forgebook.command.internal_error` | same as Ask |
| 239 | `"Server is busy. Try again."` | `forgebook.command.overloaded` | same as Ask |

**File: `src/main/java/com/forgebook/ai/RagItemPipeline.java`**
| Line | Current Literal | Proposed Key | Params |
|------|-----------------|--------------|--------|
| 225 | `"AI provider returned an error."` | `forgebook.command.provider_error` | none |
| 256 | `"Unexpected provider response."` | `forgebook.command.provider_unexpected` | none |
| 241 | `"\n\nSource: " + url` (CMD-07 citation) | Keep as-is — label prefix can be i18n'd to `forgebook.command.item.source_label` → `"\n\nSource: %s"` | `url` |

**File: `src/main/java/com/forgebook/safety/Authorizer.java`**
| Line | Current Literal | Proposed Key | Params |
|------|-----------------|--------------|--------|
| 80 | `"ForgeBook is temporarily disabled by an operator."` | `forgebook.command.denied.disabled` | none |
| 86 | `"Only players may invoke ForgeBook."` | `forgebook.command.denied.not_player` | none |
| 92 | `"ForgeBook is OP-only on this server."` | `forgebook.command.denied.forbidden` | none |
| 100 | `"Rate limit reached. Try again in " + l.retryAfterSeconds() + "s."` | `forgebook.command.denied.rate_limited` | `retryAfterSeconds` (`%d`) |

**File: `src/main/java/com/forgebook/ai/AiDispatcher.java`**

`mapError(ProviderError err) → AiDispatcher.Error(code, humanReadable)` — need to audit which strings flow through here. Based on §SC-4 evidence that `ChatErrorPacket.humanReadable` is wire content (Authorizer pitfall 5), check that AiDispatcher.Error.humanReadable strings are ALSO already-translated keys OR raw prose. Planner must grep `new Error(` and `new ProviderError(` call sites to confirm. Likely candidates:

- "Provider failed." / "Rate limit from provider." / "Upstream timeout."

These need the same treatment as Authorizer.Denied — either swap to Component-typed via Pattern 2 Option C, or keep as translation keys + args.

### Key count after Phase 5

- Phase 4 (UI): **21 keys** (locked in en_us.json)
- Phase 5 additions (command): **~17 new keys** (reload.success, disable.{success,already}, enable.{success,already}, item.{no_held,unknown,source_label}, not_initialized, internal_error, overloaded, provider_error, provider_unexpected, denied.{disabled,not_player,forbidden,rate_limited})
- **Target: ~38 keys total in en_us.json**

Refactor `AiDispatcher.Error` messages (Step 6 in planner's attack order) will add 3-5 more.

### CI firewall / regression detection

Add a new CI grep step (mirror of UI-08 reverse firewall) that fails the build if any new `Component.literal("<uppercase letter>...")` appears in `src/main/java/com/forgebook/command/` or `src/main/java/com/forgebook/safety/`:

```bash
# Allowed: Component.literal(variable) where the variable is already a translated key or StatsAccumulator.render output.
# Forbidden: Component.literal("Some English prose")
if grep -rnE 'Component\.literal\("[A-Z][^"]+"\)' \
     src/main/java/com/forgebook/command/ \
     src/main/java/com/forgebook/safety/ \
     src/main/java/com/forgebook/ai/RagItemPipeline.java ; then
  echo "REL-02 violation: raw English literal in user-visible path."
  exit 1
fi
```

**Caveats:** The grep needs to allow-list `StatsAccumulator.render()` output (structured text, not prose) and ChatPanelWidget's `"\u00a7lYou" / "\u00a7lForgeBook"` labels (§ color format codes, not English). Do this via either (a) a grep-exclude pattern ignoring all-caps-formatcode-prefixed literals, or (b) explicit file allow-lists. Option (b) is safer — the firewall already uses file-path allow-lists in UI-08.

`[VERIFIED: build.gradle:67, Phase 4 summary lines 158-164]` CI firewall pattern already established.

## Placeholder Logo Generation

### REL-01's actual requirement, carefully re-read

> Logo asset slots at `src/main/resources/logo.png` AND `src/main/resources/assets/forgebook/textures/gui/logo.png` with README pointer; mod loads cleanly with placeholders

**Two separate slots. Two separate consumers. Very different purposes:**

1. **`src/main/resources/logo.png`** — Forge mod list UI.
   - Consumed by Forge at `mods.toml#logoFile = "logo.png"` (already set).
   - Forge wiki says: **image filename placed in JAR root (no subfolders)**. `[CITED: forge.gemwire.uk/wiki/Mods.toml, fetched 2026-04-16]`
   - Renders in the Mods screen at main menu.
   - Size: no strict dimensions; Forge's own logo is 589×94; Mouse Tweaks is 191×100; Bookshelf is 128×64. `[CITED: web search 2026-04-16 — multiple Forge Forums threads]`
   - **Current state:** 1×1 RGBA PNG, 67 bytes, valid file. Loads cleanly (Forge accepts any PNG).

2. **`src/main/resources/assets/forgebook/textures/gui/logo.png`** — ForgeBook-internal.
   - NOT a Forge-defined slot. This is a ForgeBook-specific convention.
   - Intended to be rendered by `ChatPanelWidget` (future) in the chat-panel header or as a brand mark.
   - **Currently unused** — no code path in `ChatPanelWidget` or `ChatScreen` loads `"forgebook:textures/gui/logo.png"` via `ResourceLocation`. Phase 4 deliberately avoided shipping textures per UI-03 ("vanilla-reused assets + user-supplied logo only").
   - **Current state:** File does not exist. Phase 5 creates it as a placeholder.

### Recommendation

1. **Keep the existing 1×1 `src/main/resources/logo.png`.** It's valid, it loads, it meets REL-01 SC-1 verbatim. Do not attempt to replace with a branded PNG — that's the user's job.

2. **Create `src/main/resources/assets/forgebook/textures/gui/logo.png`** as an identical 1×1 placeholder (or a slightly larger 16×16 transparent PNG — more self-documenting as "intentionally blank"). Either is fine. Generation path:

   - **Option A (copy existing 1×1 placeholder):** Simplest. Same 67 bytes, just in a different path. Valid PNG.
   - **Option B (16×16 transparent PNG committed to the repo):** Slightly more honest as a placeholder. Can be generated once with any image tool offline by the planner/executor (the result is a ~100-byte file; commit it directly).
   - **Option C (runtime-generated at build via a Gradle task using `java.awt.image.BufferedImage`):** Rejected — adds build complexity, outputs same quality as Option B, no runtime benefit.

   **Recommend Option A.** One-step: `copy src\main\resources\logo.png src\main\resources\assets\forgebook\textures\gui\logo.png` (Windows) / `cp` on Unix. Both paths end up with the same valid 1×1 placeholder.

3. **README pointer** (lives in README.md):

   ```markdown
   ## Customizing the Logo

   ForgeBook ships with two placeholder logo slots. To brand your install:

   1. **Mods-list logo** (shown in Minecraft's Mods menu): replace
      `src/main/resources/logo.png`. Recommended size: 128×128 to 256×256 PNG,
      square or slightly wider. Must be at JAR root — do not move to a subfolder.

   2. **In-chat logo** (shown in ForgeBook's chat panel header — reserved for v2):
      replace `src/main/resources/assets/forgebook/textures/gui/logo.png`.
      Recommended size: 64×64 PNG. Transparent background recommended.

   Re-build with `./gradlew build`. Both placeholders load cleanly out of the box
   as 1×1 transparent PNGs, so the mod will function without replacement.
   ```

### What we do NOT build in Phase 5

- No designed branded logo — per REL-01, placeholders suffice.
- No runtime logo loader for path 2 — it's forward-looking; no current code reads it.
- No ImageMagick or BufferedImage generator — overkill.

## README & Docs Structure

### README.md outline (REL-03)

The README covers **install, config, security, commands**. Structure:

```markdown
# ForgeBook

> A Minecraft Forge 1.20.1 mod that answers "what does this item do?" with an AI
> agent grounded in the mod's own documentation — without alt-tabbing to a wiki.

[1-paragraph pitch]

## Features

- `/forgebook item` — held-item or arg-form AI lookup with source citation
- `/forgebook ask` — single-turn chat from the command line
- Inventory-docked chat UI with per-session conversation
- Pluggable AI provider (Anthropic Claude default; OpenAI + Ollama stubs)
- Optional CurseForge modpack enrichment

## Requirements

- Minecraft 1.20.1
- Minecraft Forge 1.20.1-47.4.18 (or compatible 47.x)
- Java 17
- An Anthropic API key (AI provider) — obtain at console.anthropic.com
- (Optional) A CurseForge API key for modpack context — obtain at console.curseforge.com
- Both client and server must have ForgeBook installed. LAN and single-player also work.

## Installation

### Server
1. Drop `forgebook-1.0.0.jar` into the server's `mods/` folder.
2. Start the server once to generate `config/forgebook-server.toml`.
3. Edit `config/forgebook-server.toml` — at minimum set `ai_api_key`.
4. **Restrict file permissions** (Linux/macOS):
   ```bash
   chmod 600 config/forgebook-server.toml
   ```
   On Windows, set the file's NTFS ACL so only the server-running account can read.
5. Restart the server, or run `/forgebook reload` (OP-only) to pick up changes
   without a restart.

### Client
1. Drop `forgebook-1.0.0.jar` into the client's `mods/` folder.
2. Start Minecraft. ForgeBook injects an "Ask ForgeBook" button next to the
   inventory slots.

## Configuration

### Server config (`config/forgebook-server.toml`) — contains secrets

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `ai_provider` | enum | `"anthropic"` | One of `anthropic`, `openai`, `ollama`. Only `anthropic` is implemented in v1. |
| `ai_api_key` | string | `""` | Your Anthropic API key. Secret — see "Security" below. |
| `ai_model` | string | `"claude-haiku-4-5"` | Anthropic model ID. |
| `max_tokens` | int | `1024` | Per-response output cap. |
| `curseforge_modpack_id` | string | `""` | Optional CurseForge project ID for modpack enrichment. |
| `curseforge_api_key` | string | `""` | Optional CurseForge API key. Secret. |
| `op_only` | bool | `true` | When true, only OPs (permission level 2+) can use ForgeBook. |
| `rate_limit_per_minute` | int | `5` | Per-player request cap per minute (OPs bypass). |
| `enable_web_search` | bool | `false` | Whether the agent is allowed to invoke the WebSearchTool. |
| `web_search_provider` | enum | `"duckduckgo_html"` | `duckduckgo_html` or `brave`. |
| `web_search_api_key` | string | `""` | Only needed for `brave`. |
| `config_version` | int | `1` | Config schema version — do not edit. |

### Client config (`config/forgebook-client.toml`) — no secrets

| Field | Type | Default | Purpose |
|-------|------|---------|---------|
| `enable_chat_interface` | bool | `true` | When false, the inventory button is not injected. |

## Security Posture

ForgeBook is built around a single security claim: **your API keys never leave the
server process.**

- All AI + CurseForge API calls originate server-side from `java.net.http.HttpClient`.
- The client-side code is firewalled from key material by package discipline
  (`com.forgebook.client.*` cannot import `com.forgebook.ai.*`, `com.forgebook.safety.*`,
  or `com.forgebook.config.ApiKey`) — enforced at CI time.
- `SafeHttpFetcher` (server-side) refuses to fetch http:// URLs, private-IP URLs,
  loopback URLs, and responses larger than 1 MB — so a compromised mod author
  publishing a malicious `displayURL` cannot trick the server into hitting
  internal infrastructure (SSRF guard).
- The `forgebook-server.toml` file contains plaintext API keys. **Restrict file
  permissions to 600 (owner-only)** as shown in Installation step 4. Log output
  redacts any `Authorization`, `x-api-key`, and `sk-ant-` / `sk-proj-` substrings
  via a Log4j2 filter.
- Rate limit defaults (`op_only=true`, 5 req/min per player) are designed to
  prevent a single malicious player from draining the server owner's API budget.
  Relax them at your own discretion; monitor `logs/forgebook-audit.log` for
  unusual patterns.

## Commands

All under `/forgebook`:

| Subcommand | OP-only? | Purpose |
|------------|----------|---------|
| `/forgebook item` | Runtime (see `op_only`) | Ask about the item in your main hand. |
| `/forgebook item <modid:item_id>` | Runtime | Ask about a specific item. |
| `/forgebook ask <message...>` | Runtime | One-shot chat reply. |
| `/forgebook reload` | Yes | Atomic config reload. |
| `/forgebook disable` | Yes | Kill switch — stop all new requests. |
| `/forgebook enable` | Yes | Re-enable after `/forgebook disable`. |
| `/forgebook stats` | Yes | Per-player + aggregate stats. |

Plus the in-inventory chat button on the client.

## Compatibility

See [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) for the tested mod matrix.

## Customizing the Logo

[section from §Placeholder Logo Generation]

## Credits

Third-party components are credited in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## License

MIT — see [`LICENSE`](LICENSE).
```

### THIRD_PARTY_NOTICES.md status (REL-03 partial)

**Already correct.** jsoup is MIT-licensed (verified at jsoup.org/license 2026-04-16), and the current file says MIT. Do NOT change to Apache 2.0 (the task-prompt hint was incorrect). `[VERIFIED: https://jsoup.org/license fetched 2026-04-16 — confirmed MIT]`.

Optional additive: include a short Minecraft / Forge attribution paragraph (they don't legally require credit for a Minecraft mod, but it's a kindness). Not required for REL-03.

### docs/COMPATIBILITY.md outline (REL-04) — see §Compat Matrix Protocol below

### docs/RELEASE-SMOKE.md outline (REL-05) — see §Prod-Jar Smoke Protocol below

## Compat Matrix Protocol

### The eight compat targets

REL-04 locks the testing roster:

| Mod | Role | Reason to test |
|-----|------|----------------|
| Just Enough Items (JEI) | Item browser, adjacent-to-inventory overlay | Same screen real estate as ForgeBook's chat panel — high overlap risk |
| Roughly Enough Items (REI) | JEI alternative | Second ecosystem in the same slot; different overlay math |
| Sodium / Embeddium | Rendering performance | Replaces chunk-render pipeline; can break GuiGraphics / scissor assumptions |
| Iris / Oculus | Shader pipeline | Alters rendering state; could affect GUI blend modes |
| Jade | Tooltip / crosshair overlay | Renders over the hotbar and target entity — could overlap with a future "what am I looking at" feature but should not affect inventory UI |
| Mouse Tweaks | Inventory drag-and-scroll | Modifies mouse handlers on InventoryScreen — risk of intercepting the ForgeBook button clicks |
| Quark | Large QoL pack | Broad surface; primary risk is its own inventory additions (armory tabs, etc.) |
| Inventory HUD+ | HUD overlay | Draws on top of inventory — risk of overlap with the "F" button |

### Testing protocol

`docs/COMPATIBILITY.md` structure:

```markdown
# ForgeBook Compatibility Matrix

**Last verified:** YYYY-MM-DD on Minecraft 1.20.1 + Forge 47.4.18
**Tester:** <name>
**Method:** see §Testing Protocol below

## Matrix

| Mod | Version tested | GUI scale 1 | GUI scale 2 | Notes |
|-----|----------------|-------------|-------------|-------|
| JEI | jei-1.20.1-forge-15.20.0.106 | ✓ | ✓ | ForgeBook button placed right of inventory; JEI overlay to the right. No overlap. |
| REI | RoughlyEnoughItems-12.0.687 | ✓ | ✓ | Same as JEI. |
| Sodium (Embeddium) | embeddium-0.3.30+mc1.20.1 | ✓ | ✓ | GuiGraphics renders correctly. |
| Iris (Oculus) | oculus-mc1.20.1-1.7.0 | ✓ | ✓ | Shaders do not leak into GUI rendering. |
| Jade | Jade-1.20.1-forge-11.12.1 | ✓ | ✓ | Jade hotbar overlay unaffected. |
| Mouse Tweaks | MouseTweaks-forge-mc1.20-2.25 | ✓ | ✓ | Button clicks propagate correctly. |
| Quark | Quark-4.0-460 | ✓ | ✓ | Quark's inventory tweaks compose with ForgeBook's button. |
| Inventory HUD+ | InventoryHUD.Forge-1.20.1-3.4.25 | ✓ | ✓ | HUD overlay does not clip ForgeBook button. |

## Testing Protocol

For each mod in the matrix:

1. Fresh `./gradlew runClient` with a clean `run/mods/` folder.
2. Drop ForgeBook + the one compat target into `run/mods/`.
3. Launch.
4. Open the inventory — verify the "Ask ForgeBook" button appears to the right
   of the inventory at the `leftPos+imageWidth+4` offset.
5. Verify no vanilla or compat-mod widgets overlap the button.
6. Click the button — verify the ChatScreen opens with the inventory still
   visible beneath.
7. Change GUI scale via `Options → Video Settings → GUI Scale` to 1, repeat
   steps 4-6. Then to 2, repeat.
8. Close Minecraft.
9. Record result in the matrix above; any overlap or failure becomes a "note"
   and a GitHub issue link.

Re-run this protocol when:
- ForgeBook ships a UI-affecting change (new widget, new panel tier).
- Any compat target releases a major version bump.
- Users report regressions.
```

### What gets shipped in Phase 5

- The blank matrix with "[ ] pending" checkboxes.
- The protocol prose.
- Ideally ONE fully-filled row (JEI is the most important compat target) to prove the protocol works.

**Filling the other seven rows is a human-checkpoint deliverable** — Claude cannot run `runClient` with 8 different compat mods and visually inspect the output. Record as an auto-defer to the operator under `workflow._auto_chain_active=true` (already set in config.json).

### Where this doc lives

`docs/COMPATIBILITY.md` — survives phase close, discoverable to end-users via README link.

## Prod-Jar Smoke Protocol

### What "prod jar" means here

Current `./gradlew build` produces `build/libs/forgebook-0.1.0.jar`. Bump `build.gradle#version` to `'1.0.0'` and the output becomes `forgebook-1.0.0.jar`. This is the artifact under test.

**Critical:** The jar bundles the relocated jsoup (via jarJar). `./gradlew runClient` and `./gradlew runServer` both run from the exploded classpath, NOT the built jar. A dev run that works does NOT prove the packaged jar works — the relocation and jarJar nesting could silently break. REL-05 exists specifically to catch this.

### docs/RELEASE-SMOKE.md outline

```markdown
# ForgeBook Release Smoke Protocol

**Before tagging a release, walk this protocol from a clean working tree.**

## Prerequisites

- A disposable Minecraft 1.20.1 installation (not your normal one).
- A disposable Forge 1.20.1-47.4.18 dedicated server installed to a fresh folder.
- An Anthropic API key (test key or main key — rate limits apply).

## Step 1 — Clean build

```bash
./gradlew clean build --no-daemon
ls build/libs/forgebook-1.0.0.jar     # must exist and be >50 KB
jar tf build/libs/forgebook-1.0.0.jar | grep jsoup   # must show nested META-INF/jarjar/*.jar with jsoup
```

## Step 2 — Dedicated server install

1. Fresh Forge 1.20.1-47.4.18 dedicated server folder. Start once with no mods
   to generate the EULA, accept it.
2. Stop the server.
3. Copy `build/libs/forgebook-1.0.0.jar` into the server's `mods/` folder.
4. Start the server. Console must print:
   - `Loading mod 'forgebook' (version 1.0.0)`
   - No `NoClassDefFoundError` for `com.forgebook.shadow.jsoup.*` classes.
   - No client-classloader leakage — nothing about `net.minecraft.client.*`.
5. Tail `logs/latest.log` — confirm `ForgebookNetwork` registered channel
   `forgebook:main`, `ConfigHolder` loaded, `SystemPromptBuilder.buildAndCache`
   completed, `RateLimiterHolder.swap` seeded.

## Step 3 — Set secrets

1. Stop the server.
2. Edit `config/forgebook-server.toml`: set `ai_api_key = "sk-ant-..."`.
3. `chmod 600 config/forgebook-server.toml`.
4. Restart the server.
5. Grep `logs/latest.log` for `sk-ant-` — must return zero lines (ApiKeyScrubFilter
   is working).

## Step 4 — Client connect

1. In your disposable Minecraft 1.20.1 launcher, install Forge 47.4.18.
2. Copy the SAME `forgebook-1.0.0.jar` into the client's `mods/` folder.
3. Launch the client; connect to the dedicated server (`localhost` or the
   server's IP).
4. Op your player from the server console: `op <yourname>`.

## Step 5 — Smoke `/forgebook item`

1. In-game, give yourself a diamond pickaxe: `/give <yourname> minecraft:diamond_pickaxe`.
2. Hold the pickaxe.
3. Run `/forgebook item`.
4. **Expected:** an AI reply within 5-15 seconds explaining the item, with a
   `Source: ...` citation at the bottom.
5. **On the server:** check `logs/latest.log` for exactly one `[forgebook.audit]`
   line with `uuid=<your-uuid>`, `kind=ITEM`, `tokens=<positive>`, `latency_ms=<positive>`,
   `outcome=SUCCESS`.

## Step 6 — Smoke the chat UI

1. Open inventory. Verify the "Ask ForgeBook" button appears.
2. Click it. Verify ChatScreen opens.
3. Type "What is a diamond pickaxe?" and press Enter.
4. Verify a loading indicator, then an assistant reply bubble.
5. Close the screen (ESC). Re-open inventory, click the button — verify the
   conversation is empty (session cleared).

## Step 7 — Smoke `/forgebook disable` + `/forgebook enable`

1. `/forgebook disable` → expect "ForgeBook disabled. ..." feedback.
2. `/forgebook item` → expect "ForgeBook is temporarily disabled by an operator."
3. `/forgebook enable` → expect "ForgeBook enabled. ..."
4. `/forgebook item` again → expect normal AI reply.

## Step 8 — Smoke `/forgebook stats` and `/forgebook reload`

1. `/forgebook stats` → expect a table with at least your one request counted.
2. Edit `config/forgebook-server.toml` — change `rate_limit_per_minute` to 1.
3. `/forgebook reload` → expect "ForgeBook config + system prompt reloaded."
4. As a non-OP player (de-op yourself or ask a second account): run `/forgebook ask hi`
   twice in quick succession; the second should be RATE_LIMITED with a retry-after.

## Step 9 — Teardown

- Stop the client and server.
- Delete the disposable server folder and client instance.
- Delete the API key from `config/forgebook-server.toml` before archiving.

## Pass/Fail criteria

**PASS** if every "Expected" line matches observed behaviour, and no stack
traces or secret leakage appears in `logs/latest.log`.

**FAIL** = tag the release candidate as `-rc<N>` (not `v1.0.0`), file issues,
fix, re-run this protocol from Step 1.
```

### What gets automated in Phase 5

- **Jar existence / size check:** can add a Gradle task `verifyRelease` that runs `./gradlew build` and checks output file presence and that `jar tf` shows the relocated jsoup. This is a sub-seconds check and catches build-pipeline regressions.
- **Log-line grep for secret leakage** can be a test: spin a small test that runs the `ApiKeyScrubFilter` over a synthetic log line containing `sk-ant-xxx` and asserts the substring is redacted. This test likely already exists from Phase 1 — verify.

Everything else in the smoke protocol is **human-only** — needs a live dedicated server, API key, and visual inspection. Auto-defer per `_auto_chain_active=true`.

## File Layout Proposal (concrete list)

### Files CREATED in Phase 5

| Path | Content | Requirement |
|------|---------|-------------|
| `README.md` | §README.md outline | REL-03 |
| `docs/COMPATIBILITY.md` | §Compat Matrix Protocol outline | REL-04 |
| `docs/RELEASE-SMOKE.md` | §Prod-Jar Smoke Protocol outline | REL-05 |
| `src/main/resources/assets/forgebook/textures/gui/logo.png` | 1×1 placeholder PNG (copy of existing logo.png) | REL-01 |

### Files MODIFIED in Phase 5

| Path | Change | Requirement |
|------|--------|-------------|
| `src/main/resources/assets/forgebook/lang/en_us.json` | Add ~17 new command-feedback keys (see §i18n Literal Audit) | REL-02 |
| `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` | `Component.literal("reloaded.")` → `Component.translatable("forgebook.command.reload.success")` | REL-02 |
| `src/main/java/com/forgebook/command/AdminSubcommands.java` | Swap 4 literal strings for translation keys; adjust BiConsumer to pass key+Component | REL-02 |
| `src/main/java/com/forgebook/command/AskSubcommand.java` | Swap 4 literals (not_initialized, internal_error, overloaded, + re-wiring humanReadable → Component if Pattern 2 Option C adopted) | REL-02 |
| `src/main/java/com/forgebook/command/ItemSubcommand.java` | Swap 5 literals (no_held, unknown, not_initialized, internal_error, overloaded, + humanReadable rewiring) | REL-02 |
| `src/main/java/com/forgebook/ai/RagItemPipeline.java` | Swap 3 literals (provider_error, provider_unexpected, source_label) | REL-02 |
| `src/main/java/com/forgebook/safety/Authorizer.java` | `Denied.humanReadable` String → Component (Pattern 2 Option C); 4 call sites updated | REL-02 |
| `src/main/java/com/forgebook/ai/AiDispatcher.java` | `Error.humanReadable` String → Component; `mapError(...)` updated; 3-5 string literals swapped | REL-02 |
| `build.gradle` | Bump `version = '0.1.0'` → `'1.0.0'` | REL-05 (jar naming) |
| `src/main/resources/META-INF/mods.toml` | (optional) Add `issueTrackerURL` and `credits` fields if desired; confirm `displayURL = https://github.com/Nick-Doxa/ForgeBook` is correct; no logoFile change needed | REL-03 (polish) |
| `.github/workflows/build.yml` | (optional) Add REL-02 firewall grep per §i18n CI firewall section | REL-02 (regression net) |

### Files NOT modified

- `LICENSE` — already MIT with correct copyright year (2026). Leave alone.
- `THIRD_PARTY_NOTICES.md` — already correct (jsoup MIT). Leave alone.
- `src/main/resources/logo.png` — already a valid 1×1 placeholder. Keep.
- `src/main/resources/META-INF/mods.toml` — `logoFile = "logo.png"` correctly points to JAR root. No change needed for REL-01.
- `src/main/resources/pack.mcmeta` — no changes.
- `gradle.properties` — do NOT edit the MDK template defaults (they're unused by build.gradle anyway, which reads directly from its own DSL). Editing invites later confusion.

**Total file impact:** 4 created, ~10 modified. Significant mechanical refactor surface but zero new behavior and zero new dependencies.

## Runtime State Inventory

Phase 5 involves no renames, migrations, or external state. Every artifact is either build-output or source-code. Specifically:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — ForgeBook has no persistent storage (session-only chat, no on-disk cache per D-14). | none |
| Live service config | None — no external services configured per ForgeBook. | none |
| OS-registered state | None — no Task Scheduler / launchd / systemd entries. | none |
| Secrets/env vars | `config/forgebook-server.toml` has `ai_api_key`, `curseforge_api_key`. Phase 5 doesn't rename them — only documents `chmod 600`. No migration needed. | none |
| Build artifacts / installed packages | `build/libs/forgebook-0.1.0.jar` becomes `forgebook-1.0.0.jar` after version bump. Old jar in `build/libs/` will be stale until `./gradlew clean build`. | Delete `build/` before tagging: `./gradlew clean` or `rm -rf build/`. Document in RELEASE-SMOKE.md Step 1. |

**Verified by:** Code audit (no `JdbcTemplate` or `ChromaClient` or `MongoClient` imports in source tree); config file has no state keyed by strings we'd rename.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | Gradle build, runtime | ✓ | Java 22 installed (overshoots but Gradle toolchain pins 17 per `java.toolchain.languageVersion = JavaLanguageVersion.of(17)`) | — |
| Gradle wrapper | build | ✓ | 8.1.1 (MDK-shipped) | — |
| Minecraft 1.20.1 + Forge 47.4.18 dedicated server | REL-05 smoke | ⚠ must install to disposable folder | — | Protocol documents how to set up; user action required |
| Anthropic API key | REL-05 smoke Step 5 + 6 | ⚠ user-supplied at test time | — | Protocol assumes caller provides |
| `magick` / `convert` (ImageMagick) | Placeholder PNG generation | ✗ `convert` found in PATH but is Windows NTFS converter, not ImageMagick | — | Copy existing logo.png as placeholder (Option A in §Placeholder Logo Generation) — no image tool needed |
| `file`, `xxd` | Debug / audit | ✓ | available in git-bash | — |
| `grep`, `ripgrep` | CI audit, i18n grep | ✓ | available via Grep tool + git-bash | — |

**Missing dependencies with no fallback:** none.

**Missing dependencies with fallback:**
- ImageMagick: fallback is Option A (copy the existing 1×1 placeholder PNG). No functional difference.

**Blocking for REL-05 (human checkpoint):** dedicated-server install + API key. These are *always* human-supplied — not blocks on the automated phase, blocks on the operator running the smoke.

## Common Pitfalls

### Pitfall 1: Putting logoFile in a subfolder

**What goes wrong:** `mods.toml` sets `logoFile = "assets/forgebook/logo.png"` — the mod list UI silently shows no logo. No error, no warning — just a blank slot.

**Why it happens:** Misreading Forge docs; confusing `logoFile` (JAR root) with `textures/gui/` (asset namespace).

**How to avoid:** Keep `logoFile = "logo.png"` (bare filename). Do not move. `[CITED: forge.gemwire.uk/wiki/Mods.toml 2026-04-16]`

**Warning signs:** Built jar passes `./gradlew build` but the mod list shows no logo.

### Pitfall 2: Non-translatable Component.literal in sendFailure

**What goes wrong:** Phase 5 refactors some but not all command-feedback calls to `Component.translatable`. A future Phase 6 adds a non-English locale, and the missed literal renders as English while the rest of the UI is translated.

**Why it happens:** Mechanical grep catches the `Component.literal(string)` pattern, but `src.sendFailure(Component.literal(someComputedString))` where `someComputedString` comes from a concatenation can slip through.

**How to avoid:** Phase 5 CI grep (§i18n Literal Audit > CI firewall / regression detection) catches the textual pattern. Also audit for string concatenation inside `sendFailure` / `sendSuccess` args.

**Warning signs:** Re-run of grep after Phase 5 merges still shows hits in `command/` or `safety/`.

### Pitfall 3: Accidentally bundling an extra library

**What goes wrong:** A dependency added mid-Phase-5 (e.g., a logging helper, a markdown parser, a JSON schema lib) gets into the jar via `implementation` instead of `compileOnly`, and the published jar now contains duplicated classes that clash with other mods.

**Why it happens:** Phase 5 adds README and docs; the planner tries to automate something with a new lib; scope creep.

**How to avoid:** Phase 5 adds ZERO new runtime dependencies. Any PR that changes the `dependencies { }` block in build.gradle must be rejected unless explicitly justified. The CI `./gradlew build` step should verify the jar contents — add `jar tf build/libs/forgebook-*.jar | grep -v "^com/forgebook/\\|^META-INF/\\|^assets/forgebook/\\|^logo.png\\|jarjar"` returns zero lines (i.e., no unexpected top-level packages).

**Warning signs:** `build.gradle#dependencies` block changes during Phase 5.

### Pitfall 4: README rots faster than code

**What goes wrong:** README describes 5 config fields; Phase 2 adds a 6th (`web_search_provider`) and the README is not updated. New users hit the 6th field in their toml without knowing what it does.

**Why it happens:** README is a separate artifact with no compile-time link to config.

**How to avoid:** Add a CI grep step that extracts ForgeConfigSpec field names (via reflection at test time, or via `grep -rE 'define\("' src/main/java/com/forgebook/config/`) and cross-references against the README config table. Fail the build on drift. This is **optional polish** — worth doing if time allows; adequate for v1 to verify manually.

**Warning signs:** PR adds a config field without updating README.

### Pitfall 5: THIRD_PARTY_NOTICES miscredits jsoup

**What goes wrong:** Research / prior docs/task-prompts claim jsoup is "Apache 2.0". Someone "corrects" the NOTICES.md to match. The NOTICES now lists the wrong license, breaking the MIT license's "include the copyright notice" requirement.

**Why it happens:** Wrong prior assumption in an issue or task prompt.

**How to avoid:** Verified at https://jsoup.org/license on 2026-04-16: jsoup IS MIT-licensed. Current NOTICES.md is correct. Do NOT change to Apache 2.0. `[VERIFIED: jsoup.org/license 2026-04-16]`

**Warning signs:** PR changes jsoup license section in NOTICES.md — require citation.

### Pitfall 6: Version bump in gradle.properties ignored

**What goes wrong:** User edits `gradle.properties#mod_version=1.0.0` expecting the build to use it. `build.gradle` still reads `version = '0.1.0'` directly. Jar still named `forgebook-0.1.0.jar`.

**Why it happens:** `gradle.properties` still has the MDK template defaults, which are IGNORED by build.gradle because build.gradle doesn't read them.

**How to avoid:** Change `version = '0.1.0'` → `version = '1.0.0'` in `build.gradle` directly. Optionally clean up `gradle.properties` by removing or correcting the unused template defaults.

**Warning signs:** `./gradlew build && ls build/libs/` still shows 0.1.0 after "bumping the version".

### Pitfall 7: Compat matrix rots silently

**What goes wrong:** Phase 5 ships the matrix; six months later, Jade releases a breaking update and ForgeBook's next release inherits broken compat. No one re-ran the matrix.

**Why it happens:** The matrix is a timestamped snapshot with no automated re-run trigger.

**How to avoid:** Make the matrix file list `last_verified` dates per-row. When a row's date is older than 90 days, a Dependabot-style reminder flags it. For v1, a README note ("matrix reflects state at release; re-verify when upgrading") is adequate.

**Warning signs:** "Last verified" date > 6 months before a new release tag.

### Pitfall 8: Prod-jar smoke skipped "because it works in dev"

**What goes wrong:** Dev run (`runClient`/`runServer`) uses the exploded classpath. jsoup class resolution works because the unrelocated jsoup is on the classpath. The built jar uses the relocated jsoup. If the relocation is mis-configured, the dev run passes and the prod jar ClassCastException's on the first scrape.

**Why it happens:** `./gradlew build` compiles the relocated target; `./gradlew runClient` uses source classpath. They are DIFFERENT CLASSPATHS.

**How to avoid:** REL-05 smoke MUST run against the built jar in a disposable dedicated-server folder, not against `./gradlew runServer`. The smoke protocol (§Prod-Jar Smoke Protocol Step 2) is explicit about this.

**Warning signs:** "I ran runClient, it worked, we're good." Dangerous — did not exercise the built-jar path.

### Pitfall 9: Translation key missing from en_us.json → render shows key, not text

**What goes wrong:** Code emits `Component.translatable("forgebook.command.new_key")`; the key is not in `en_us.json`; in-game the player sees the literal `forgebook.command.new_key` in their chat.

**Why it happens:** Mechanical grep catches Component.literal → Component.translatable refactor, but forgets to add the key to en_us.json.

**How to avoid:** Two-way grep: every `forgebook.` key used in Component.translatable calls must exist in en_us.json, and every key in en_us.json must be used somewhere in source. Run both directions in CI. Fail on either drift.

**Warning signs:** In-game chat shows `forgebook.command.xyz` instead of prose.

### Pitfall 10: README uses `chmod 600` without noting Windows

**What goes wrong:** Server owner on Windows reads the README, sees `chmod 600`, tries it in PowerShell, hits a cryptic error, concludes ForgeBook doesn't support Windows.

**Why it happens:** Linux-centric docs.

**How to avoid:** README security section explicitly names both platforms (see §README.md outline — "On Windows, set the file's NTFS ACL so only the server-running account can read.").

**Warning signs:** User issue titled "chmod doesn't work on Windows".

## Code Examples

### Component.translatable server-side — already present in Phase 4

```java
// Source: src/main/java/com/forgebook/client/ui/ChatScreen.java:105 (verified 2026-04-16)
this.input = new EditBox(
    this.font, ix, iy, iw, ih,
    Component.translatable("forgebook.chat.input.placeholder"));
```

### Component.translatable with format args — pattern for rate-limit

```java
// Pattern to use in Authorizer.java refactor (Phase 5):
return new Denied(
    ErrorCode.RATE_LIMITED,
    Component.translatable("forgebook.command.denied.rate_limited",
                           l.retryAfterSeconds()));
// en_us.json entry:
//   "forgebook.command.denied.rate_limited": "Rate limit reached. Try again in %ds."
```

The `%d` placeholder resolves against the second (and further) Object arguments to `Component.translatable`. `[CITED: net.minecraft.network.chat.MutableComponent#createTranslatable — Minecraft 1.20.1 source]` `[ASSUMED]` format-string resolution rules — confirm during planning by running a test Component against a known key.

### Jar-verify during build

```gradle
// Add to build.gradle after the jar task:
tasks.register('verifyReleaseJar') {
    dependsOn 'build'
    doLast {
        def jar = file("build/libs/forgebook-${project.version}.jar")
        if (!jar.exists()) throw new GradleException("Release jar missing: ${jar}")
        if (jar.length() < 50_000)
            throw new GradleException("Release jar suspiciously small: ${jar.length()} bytes")
        // Confirm jsoup is bundled via jarJar
        def jarContents = ['jar', 'tf', jar.absolutePath].execute().text
        if (!jarContents.contains('META-INF/jarjar/')) {
            throw new GradleException("Release jar missing jarjar nested deps — jsoup not bundled?")
        }
    }
}
```

## State of the Art (for Forge 1.20.1 mod releases)

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@SidedProxy` for client/server split | `DistExecutor.safeRunWhenOn` + package-firewall | Forge 1.14+ | Already adopted in Phase 1 |
| Static-field `Logger` in each class | `LogManager.getLogger()` per-class | Unchanged 1.14–1.20.1 | Already adopted |
| `NetworkRegistry.ChannelBuilder` fluent | `NetworkRegistry.newSimpleChannel(...)` (1.20.1) | Forge 1.20.2+ flipped to ChannelBuilder — stay on the old API for 1.20.1 | Already honored Phase 1 |
| Hand-rolled jar relocation via `shadow` plugin | `jarJar` + `ShadowJar` used as a task, not plugin | 1.19+ Forge default | Already adopted Phase 1 |
| Shipping a `version.txt` | Use `build.gradle#version` property directly | unchanged | Already adopted |

**Deprecated/outdated (don't use):**
- `RenderGuiOverlayEvent` for a widget that needs to render above an open Screen — already flagged in CLAUDE.md §"What NOT to Use".
- `@Mod.EventBusSubscriber` without explicit `bus = ...` — already flagged CLAUDE.md.

No state-of-the-art drift relevant to Phase 5 — the release-polish work is pure hygiene, not architectural evolution.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The Authorizer.Denied.humanReadable → Component refactor (Pattern 2 Option C) is test-harness-compatible because `.getString()` of a Component.translatable returns the key when no language is loaded. | §Pattern 2 | Tests that assert on `d.feedback().getString()` would return the translation key in tests and translated text in-game — two different strings. If tests assert on prose, they'd need to swap to key-asserts. Mitigation: planner should verify with a trivial probe test (`@Test void translatable_getString_in_jvm_test_returns_key()`) before committing to Option C. Fall back to Option A if the probe fails. |
| A2 | `Component.translatable(key, args)` resolves `%d` / `%s` via `MessageFormat` at render time. | §Pattern 2 Code Example | If formatting rules differ (e.g., positional vs. indexed), the rate-limit message renders wrong. Mitigation: test with `Component.translatable("forgebook.command.denied.rate_limited", 42).getString()` in a unit test. |
| A3 | Current `AdminSubcommandsTest`, `AskSubcommandTest`, `ItemSubcommandTest`, `RagItemPipelineTest` test via string-equality on the sent text. | §Pattern 1 | If tests already assert on `Component` equality or key equality, the refactor diff is larger or smaller than estimated. Mitigation: planner reads the test files before writing the refactor PR. |
| A4 | Copying the existing 1×1 `logo.png` to the `textures/gui/` path produces a valid placeholder. | §Placeholder Logo Generation Option A | Forge / Minecraft resource loader might complain about a 1×1 texture referenced via ResourceLocation in a future phase. Mitigation: since no current code loads this path, any load-time failure is deferred to v2 — acceptable. |
| A5 | The prompt's claim that jsoup is Apache 2.0 is incorrect. | §THIRD_PARTY_NOTICES | Verified at jsoup.org/license on 2026-04-16: jsoup is MIT. Risk is if jsoup 1.17.2 (the pinned version) used a different license than 1.18.x. Mitigation: current NOTICES file is pinned to 1.17.2 and states MIT correctly; checked upstream. Low risk. |
| A6 | `build.gradle` directly sets `version = '0.1.0'`; `gradle.properties#mod_version` is dead template config. | §Pitfall 6 | If some Gradle plugin reads `gradle.properties`, an out-of-sync value could cause duplicate artifact publishing. Mitigation: `grep -rE 'project\.mod_version\|ext\.mod_version\|rootProject\.mod_version' build.gradle` confirms build.gradle doesn't reference the gradle.properties field. Verified by reading build.gradle — it uses plain `version = '0.1.0'` at the top level. |
| A7 | The CI firewall grep pattern `Component\.literal\("[A-Z][^"]+"\)` will not false-positive on `Component.literal("\u00a7lYou")` (a § formatting code prefix) because the regex `[A-Z]` doesn't match `\u00a7`. | §i18n CI firewall | A false positive would block a legitimate formatting-code literal. Mitigation: the ChatPanelWidget is already excluded from the `command/safety/` scope of this firewall, so no conflict. |

**Assumption count:** 7. All mitigations are cheap — either a probe test, a targeted grep, or an upstream verification. Planner should execute each mitigation in Wave 0 before committing to the refactor.

## Project Constraints (from CLAUDE.md)

| Constraint | How Phase 5 Must Honor |
|------------|------------------------|
| **Tech stack locked** (Forge 1.20.1-47.4.18, Java 17, Gradle 8.1.1 + ForgeGradle 6.0.x) | Phase 5 adds zero new dependencies and zero Gradle version bumps. |
| **Distribution: client + server both require mod** | README "Installation" section documents installing on BOTH sides. |
| **Compatibility: must not conflict with common QoL mods** | REL-04 matrix verifies this empirically. |
| **Secrets: API keys never on client; all outbound from server** | README "Security Posture" section teaches this. Log4j2 filter already scrubs. |
| **Cost: prevent single player from draining API budget** | README "Security Posture" section documents op_only + rate-limit defaults. |
| **Asset sourcing: vanilla-reused or permissively-licensed only** | Placeholder logo.png is a trivial 1×1 (no copyright concerns); designed asset is user's responsibility. |
| **Licensing: MIT default** | LICENSE is MIT, NOTICES credits jsoup MIT, package.json n/a. |
| **Forge 1.20.1-47.4.18 pinned** | README installation requires this exact Forge; `mods.toml` `versionRange="[47.4.18,)"` is compatible. |
| **Client classloader firewall: `com.forgebook.client.*` only imports `net.minecraft.client.*`** | REL-02 refactor touches server-side classes; no firewall impact. |
| **SERVER-tier secrets** | Config fields unchanged; README documents them. |
| **Off-tick HTTP** | No Phase-5 code change. |
| **SafeHttpFetcher chokepoint** | No Phase-5 code change. |
| **Agent caps + retry + circuit breaker** | No Phase-5 code change. |
| **Prompt-injection framing** | No Phase-5 code change. |
| **SimpleChannel registration via `newSimpleChannel`** | No Phase-5 code change. |
| **`IModInfo.getModURL()`** (not `getDisplayURL()`) | No Phase-5 code change. |
| **GSD workflow enforcement** | All file edits go through this phase's planner → executor chain. |

**Critical**: Phase 5 is a closeout phase — it MUST NOT regress any Phase 1-4 architectural invariants. If a Phase-5 commit touches `com.forgebook.ai.*` or `com.forgebook.safety.*` code, it should ONLY swap literal strings for Component-translated equivalents. No logic changes.

## Security Domain

Phase 5 does not add new attack surface. It does, however, *publish* the security posture to users (README) and *normalize* user-visible error prose (i18n). Relevant ASVS review:

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | no | No new auth surface in Phase 5. Existing OP gate is Phase 3. |
| V3 Session Management | no | No new session surface. |
| V4 Access Control | no | `op_only` gate + rate limiter are Phase 3; README documents them. |
| V5 Input Validation | **yes** | README must not describe any way to bypass SafeHttpFetcher (no "add your own URL allowlist" guidance — redacted in §README.md outline). |
| V6 Cryptography | no | No crypto changes. |
| V9 Data Protection | **yes** | `chmod 600 forgebook-server.toml` recommendation + ApiKeyScrubFilter documentation are the user-facing half of API-key protection. |
| V14 Config | **yes** | README config-field table must not accidentally expose secret defaults (e.g., suggesting an example API key — use only `""` placeholders). |

### Known Threat Patterns for Phase 5

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| README leaks example API key | Information Disclosure | Only show `""` placeholder in config table; never a real or fake `sk-ant-...` example. |
| Compat matrix gives malicious advice | Tampering | All compat-mod-version citations in `docs/COMPATIBILITY.md` must be verifiable via CurseForge / Modrinth — no privately-hosted mod suggestions. |
| Logo placeholder used as attack surface | Tampering | Placeholder is a 1×1 PNG — no embedded metadata, no EXIF. |
| Release-smoke protocol prescribes insecure settings | Information Disclosure | `docs/RELEASE-SMOKE.md` must instruct the user to *delete* the API key after smoking, and never commit `forgebook-server.toml` from the smoke run. |
| i18n key rendered in-game exposes internal implementation (e.g., `forgebook.internal.debug.foo`) | Information Disclosure | Every key in en_us.json must be user-appropriate prose; no debug/dev keys. |
| Documentation contradicts code regarding which config keys are SERVER vs CLIENT tier | Tampering / confused deputy | README config table must match `com.forgebook.config.ForgebookServerConfig` / `ForgebookClientConfig` definitions exactly. Pitfall 4 covers drift. |

**`security_enforcement` status:** not configured explicitly in `.planning/config.json`; treat as enabled. All threats above have standard mitigations in the outlined docs.

## Open Questions

### Q1: Should Authorizer.Denied keep `humanReadable: String` or switch to `feedback: Component`?

- What we know: All 4 Denied strings need translation; current type is `String`; consumers call `src.sendFailure(Component.literal(d.humanReadable()))`.
- What's unclear: Test harness behavior of `Component.translatable(...).getString()` in JUnit (no loaded language pack).
- Recommendation: **Probe in Wave 0** — write a 3-line test that calls `Component.translatable("forgebook.command.reload.success").getString()` and asserts it returns *something* (likely the key). If HIGH confidence, adopt Option C. If ambiguous, adopt Option A (explicit `translationKey + Object[] args` record fields).

### Q2: Does `/forgebook stats` output need i18n?

- What we know: `StatsAccumulator.render()` returns a formatted multi-line table with English labels like "Requests", "Input tokens", "Top 10".
- What's unclear: Is this considered "user-visible" per REL-02, or "administrator diagnostic" (labels acceptable in English)?
- Recommendation: **Lean admin-diagnostic.** Treat the stats table like a log format — English is fine for admin output. A future v2 can i18n if contributions request it. Planner should confirm this choice in Phase 5 discussion.

### Q3: Should the RagItemPipeline's `Source: <url>` citation have a localized prefix?

- What we know: CMD-07 test locks the literal string `"\n\nSource: " + url`. Changing it could break the test.
- What's unclear: Whether REL-02 requires this (CMD-07 is an AI-OUTPUT-PREFIX, not a command-feedback string).
- Recommendation: Two valid paths. (A) i18n the prefix to `forgebook.command.item.source_label` → `"\n\nSource: %s"`; update RagItemPipelineTest to assert on the key, not prose. (B) Treat `Source:` as a protocol literal (like `JSON` or `UTC`) that doesn't need translation. Decide in Phase 5 discussion; default to (A) for completeness.

### Q4: Tagged v1.0.0 release + GitHub Release automation in scope?

- What we know: ROADMAP Phase 5 SC-5 says "the built jar ... before first tagged release" — implying a tagged release IS in scope.
- What's unclear: Does "first tagged release" mean `git tag v1.0.0` + manual GitHub Releases upload, or automated release-on-tag CI?
- Recommendation: **`git tag v1.0.0` + manual `gh release create v1.0.0 build/libs/forgebook-1.0.0.jar`** is Phase 5 scope. CI release-on-tag automation is v2. Document in RELEASE-SMOKE.md Step 10 (new).

### Q5: Does `build.gradle` need a version bump, or does `version = '0.1.0'` already count as "1.0" enough?

- What we know: Current version is 0.1.0; ROADMAP implies a "1.0" release; `./gradlew build` produces `forgebook-0.1.0.jar`.
- What's unclear: Is 0.x (semver pre-release) acceptable for "first tagged release", or must it be 1.0.0?
- Recommendation: **Bump to 1.0.0.** A tagged v1.0.0 with a 0.1.0 jar is confusing. Bump `build.gradle#version = '1.0.0'` as part of REL-05 work. Discuss with operator in Phase 5 discuss-phase if semver fidelity is in question.

### Q6: Does THIRD_PARTY_NOTICES need a Minecraft / Forge attribution section?

- What we know: jsoup is correctly credited. Minecraft and Forge are platform dependencies, not bundled libraries.
- What's unclear: Strict best practice for mod licensing attribution.
- Recommendation: **Not required.** Mods run ON Minecraft/Forge; they don't bundle MC/Forge bytecode. A single sentence in README ("Built on Minecraft Forge; thanks to the Forge maintainers and the Parchment mapping project") is polite but not legally required. Include if time allows; optional.

## Validation Architecture

**Skipped.** `.planning/config.json` sets `workflow.nyquist_validation: false`. No Nyquist/sampling-rate test architecture required for this phase.

## Sources

### Primary (HIGH confidence)

- **[jsoup License page](https://jsoup.org/license)** — verified jsoup is MIT-licensed, 2026-04-16. Corrects the prompt's Apache 2.0 hint.
- **[Gemwire Forge Community Wiki: Mods.toml](https://forge.gemwire.uk/wiki/Mods.toml)** — verified `logoFile` lives at JAR root, no subfolders; enumerated all `mods.toml` fields.
- **Local repo source files (verified by Read tool 2026-04-16):**
  - `build.gradle` — confirmed `version = '0.1.0'`, jarJar wiring, jsoup relocation task.
  - `src/main/resources/META-INF/mods.toml` — `logoFile = "logo.png"`, `displayURL`, version range.
  - `src/main/resources/logo.png` — valid 1×1 RGBA PNG, 67 bytes (file + xxd).
  - `src/main/resources/assets/forgebook/lang/en_us.json` — 21 keys confirmed.
  - `LICENSE` — MIT.
  - `THIRD_PARTY_NOTICES.md` — credits jsoup 1.17.2 under MIT (correct).
  - All `src/main/java/com/forgebook/command/*.java` — full grep of `Component.literal` + `sendFailure` / `sendSuccess` call sites (§i18n Literal Audit is exhaustive).
  - `src/main/java/com/forgebook/safety/Authorizer.java` — 4 `humanReadable` strings audited.
- **Phase verification reports** — `.planning/phases/04-in-inventory-chat-ui/VERIFICATION.md` and `03-command-surface-safety-controls/VERIFICATION.md` confirmed existing state of all Phase 3 + Phase 4 artifacts.
- **CLAUDE.md §"What NOT to Use"** — pitfall index for Forge 1.20.1 / FG 6 best practices.

### Secondary (MEDIUM confidence)

- **WebSearch "Minecraft Forge 1.20.1 mods.toml logoFile dimensions"** (fetched 2026-04-16) — confirmed no strict dimension requirement; common sizes 128×64 to 589×94.

### Tertiary (LOW — needs validation)

- None used for load-bearing claims. The Component.translatable-in-JUnit behavior (Q1) is the only unverified technical claim; explicitly flagged as a Wave-0 probe.

## Metadata

**Confidence breakdown:**

- i18n literal audit: **HIGH** — exhaustive grep of the repo, every match classified with a target key.
- Logo slots mechanism: **HIGH** — Forge docs + Gemwire wiki verified both slots.
- THIRD_PARTY_NOTICES correctness: **HIGH** — upstream license page fetched 2026-04-16.
- README & docs structure: **MEDIUM** — outline is complete and matches community conventions, but specific prose will need copy-editing.
- Compat matrix protocol: **MEDIUM** — no industry-standard template exists; protocol is author-designed but follows standard manual-test patterns.
- Prod-jar smoke protocol: **HIGH** — steps follow the canonical "build → install → connect → command → teardown" flow every Forge mod release uses.
- Common pitfalls: **HIGH** — all ten derived from either CLAUDE.md, Phase 4 VERIFICATION.md, or empirical grep.
- Component.translatable-in-JUnit (A1, A2): **MEDIUM** — behavior assumed from Minecraft 1.20.1 sources; probe test in Wave 0.
- Version bump scope (Q5): **MEDIUM** — implies user discussion.

**Research date:** 2026-04-16
**Valid until:** 2026-05-15 (30 days — stable platform, slow-moving ecosystem; re-verify jsoup version and Forge 47.4.x latest if re-running this research later).
