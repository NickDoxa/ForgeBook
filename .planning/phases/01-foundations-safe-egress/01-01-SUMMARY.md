---
phase: 01-foundations-safe-egress
plan: 01
subsystem: foundations
tags: [scaffolding, gradle, forge-mdk, jsoup-relocation, licensing]

dependency_graph:
  requires: []
  provides:
    - "Forge 1.20.1-47.4.18 MDK layout with Gradle 8.1.1 wrapper"
    - "ForgeGradle 6 + Parchment 2023.09.03-1.20.1 build pipeline"
    - "Bytecode-relocated jsoup 1.17.2 nested via jarJar (com.forgebook.shadow.jsoup)"
    - "ForgeBookMod @Mod entry with dual ForgeConfigSpec registration stubs"
    - "com.forgebook.client firewall boundary (DistExecutor.safeRunWhenOn gate)"
    - "MIT LICENSE + THIRD_PARTY_NOTICES.md (jsoup attribution)"
    - "mods.toml / pack.mcmeta / logo.png placeholder"
    - ".gitignore with secrets-hygiene rules (CFG-06)"
  affects:
    - "Every Phase-1 plan compiles against this scaffolding"
    - "Plan 02 creates ForgebookServerConfig.SPEC + ForgebookClientConfig.SPEC to satisfy ForgeBookMod's referenced symbols"
    - "Plan 05 adds CI grep-lint against net.minecraft.client imports outside com.forgebook.client"

tech-stack:
  added:
    - "Minecraft Forge 1.20.1-47.4.18"
    - "ForgeGradle 6 (plugin range [6.0,6.2))"
    - "Parchment mappings 2023.09.03-1.20.1"
    - "jsoup 1.17.2 (compileOnly + jarJar nested, relocated)"
    - "com.github.johnrengelman.shadow 8.1.1 (declared apply=false; used as ShadowJar task class only)"
    - "JUnit Jupiter 5.10.2 + Mockito 5.11.0 (testImplementation)"
  patterns:
    - "D-10 client firewall: only com.forgebook.client may import net.minecraft.client.*"
    - "D-07 jarJar nesting over shadow-plugin application"
    - "D-08 ShadowJar-as-class for bytecode relocation (no plugin apply at root)"
    - "Dual-tier ForgeConfigSpec registration (SERVER + CLIENT) in @Mod constructor"

key-files:
  created:
    - path: "build.gradle"
      purpose: "FG6 + Parchment + jarJar-nested-relocated-jsoup build DSL"
    - path: "gradle.properties"
      purpose: "MDK defaults + jsoupVersion=1.17.2 pin"
    - path: "settings.gradle"
      purpose: "MDK-shipped settings (pluginManagement for ForgeGradle / Parchment / shadow)"
    - path: "gradlew / gradlew.bat / gradle/wrapper/*"
      purpose: "Gradle 8.1.1 wrapper (re-pinned from MDK-shipped 8.8)"
    - path: "src/main/java/com/forgebook/ForgeBookMod.java"
      purpose: "@Mod entry; dual ForgeConfigSpec registration; DistExecutor client gate"
    - path: "src/main/java/com/forgebook/client/ClientSetup.java"
      purpose: "Client-only init target; sole package allowed to import net.minecraft.client.*"
    - path: "src/main/resources/META-INF/mods.toml"
      purpose: "Mod manifest: modId=forgebook, MIT, forge [47.4.18,), minecraft [1.20.1,1.20.2), side=BOTH"
    - path: "src/main/resources/pack.mcmeta"
      purpose: "Resource pack descriptor (pack_format=15)"
    - path: "src/main/resources/logo.png"
      purpose: "1x1 transparent PNG placeholder (Phase 5 polish replaces)"
    - path: "LICENSE"
      purpose: "MIT license text (Copyright (c) 2026 Nick Doxa)"
    - path: "THIRD_PARTY_NOTICES.md"
      purpose: "jsoup 1.17.2 attribution"
    - path: ".gitignore"
      purpose: "Secrets hygiene: deny run/ build/ .gradle/ *.toml.bak and stray forgebook-server.toml outside config/"
    - path: ".gitattributes"
      purpose: "MDK-shipped line-ending normalization"
  modified: []

decisions:
  - "D-04: Re-pinned gradle-wrapper from MDK-shipped 8.8 down to 8.1.1 per CLAUDE.md Version Compatibility (FG6 friction with Gradle 8.4+)."
  - "D-08 planner discretion: chose ShadowJar-task-as-class for bytecode relocation (NOT Jar+eachFile path-rename) because jsoup's internal class-to-class references require bytecode rewrite."
  - "Rephrased a Javadoc line in ForgeBookMod.java to avoid a self-trip of the Plan-05 grep-lint regex (the doc comment mentioned the forbidden import-prefix literal; switched to prose 'Minecraft client package')."

metrics:
  duration: "~5 minutes (scaffolding-only; no gradle resolution executed)"
  completed_date: "2026-04-15"
  commits: 4
  files_created: 13
  files_modified: 0
  tasks_completed: 4
  tasks_checkpointed: 1
---

# Phase 01 Plan 01: Bootstrap Forge MDK + Build Pipeline Summary

Bootstraps a greenfield Forge 1.20.1-47.4.18 mod project from the MDK with Gradle 8.1.1, Parchment 2023.09.03-1.20.1 mappings, bytecode-relocated jsoup 1.17.2 nested via `jarJar`, and the `com.forgebook` package layout with the D-10 client-classloader firewall in place; the `@Mod` entry wires dual `ForgeConfigSpec` registration stubs that Plan 02 completes.

## What Shipped

### Task 1: MDK bootstrap (commit a3cac62)
- Downloaded `forge-1.20.1-47.4.18-mdk.zip` from `maven.minecraftforge.net` and extracted into the worktree root.
- **Deviation (Rule 3):** MDK-shipped `gradle-wrapper.properties` pointed to Gradle 8.8. Per CLAUDE.md "What NOT to Use" (Gradle 8.4+ incompatible with ForgeGradle 6) and D-04, re-pinned to `gradle-8.1.1-bin.zip`.
- Removed `src/main/java/com/example/` sample sources and MDK-only docs (`README.txt`, `CREDITS.txt`, `LICENSE.txt`, `changelog.txt`).
- Created `com/forgebook/{client,config,network,util}` package directories (reserved packages `ai`, `command`, `integration` deliberately NOT created — reserved for later phases per D-05).

### Task 2: build.gradle + gradle.properties (commit 57176c5)
- Overwrote MDK `build.gradle` with the D-02/D-03/D-07/D-08/D-09 stack: FG6 plugin range `[6.0,6.2)`, Parchment `2023.09.03-1.20.1`, shadow `8.1.1` declared `apply false` (used only as the ShadowJar task class for bytecode relocation).
- Registered the `relocateJsoup` task (ShadowJar subclass) that rewrites `org.jsoup` → `com.forgebook.shadow.jsoup` and outputs a nested jar consumed by `jarJar files(tasks.relocateJsoup)`; added `tasks.named('jarJar') { dependsOn 'relocateJsoup' }` for ordering.
- Configured `runClient`, `runServer`, and `gameTestServer` with `forge.enabledGameTestNamespaces = 'forgebook'` (Pitfall 8 scoping).
- Appended `jsoupVersion=1.17.2` to MDK `gradle.properties` (kept MDK defaults intact).

### Task 3: @Mod entry + ClientSetup (commit 10287f4)
- `ForgeBookMod.java`: `@Mod(MODID="forgebook")`, dual `ModLoadingContext.get().registerConfig(...)` calls targeting `ForgebookServerConfig.SPEC` + `ForgebookClientConfig.SPEC` (Plan 02 will create those SPECs), mod-bus `commonSetup` listener, Forge-bus self-register, and the single D-10 gate `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)`.
- `ClientSetup.java` under `com.forgebook.client`: `public static void init()` no-op stub with log line. This is the ONLY package permitted to import `net.minecraft.client.*` going forward.
- **Deviation (Rule 2):** Rephrased one Javadoc line in `ForgeBookMod.java` that literally contained the string `import net.minecraft.client.*` (describing what the firewall forbids). Since Plan 05 adds a grep-lint for that exact regex, the doc comment would self-trigger the lint. Replaced with "reference the Minecraft client package" to preserve the intent without tripping the future CI check.

### Task 4: Manifest + resources + licensing (commit 78a04e2)
- `mods.toml`: `modId="forgebook"`, `license="MIT"`, forge `versionRange="[47.4.18,)"`, minecraft `[1.20.1,1.20.2)`, both `side="BOTH"`.
- `pack.mcmeta`: `pack_format=15` (flat object form, `description` as string per plan-provided shape).
- `logo.png`: 1×1 transparent RGBA PNG, 67 bytes (valid PNG magic + IHDR + IDAT + IEND).
- `LICENSE`: full MIT text, "Copyright (c) 2026 Nick Doxa".
- `THIRD_PARTY_NOTICES.md`: jsoup 1.17.2 MIT attribution, noting bundling as `com.forgebook.shadow.jsoup`.
- `.gitignore`: MDK defaults merged with CFG-06 rules (`run/`, `build/`, `.gradle/`, `*.toml.bak`, `forgebook-server.toml`, and the negation `!config/forgebook-server.toml` for fixtures).

### Task 5: Checkpoint — runClient/runServer human-verify (auto-approved)

## Checkpoints auto-approved

- **Task 5 (human-verify)**: Auto-approved per `--auto` chain mode. The plan explicitly states this checkpoint "will only pass once Plan 02 lands" because `ForgeBookMod` references `ForgebookServerConfig.SPEC` / `ForgebookClientConfig.SPEC` which Plan 02 creates. Running `./gradlew build` now would fail compilation on those unresolved symbols — expected and documented in the plan. The checkpoint is deferred to when Plan 02's SPECs are in place; the user (or a later wave executor) should re-run `./gradlew build && ./gradlew runClient && ./gradlew runServer` at that point.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Re-pinned Gradle wrapper from 8.8 to 8.1.1**
- **Found during:** Task 1
- **Issue:** MDK zip ships `distributionUrl=.../gradle-8.8-bin.zip`, but CLAUDE.md's "Version Compatibility" table and D-04 both mandate Gradle 8.1.1 (ForgeGradle 6 has known config-cache and reobf friction with Gradle 8.4+). Task 1's own `verify` block also greps for `8.1.1`.
- **Fix:** Edited `gradle/wrapper/gradle-wrapper.properties` to `distributionUrl=https\://services.gradle.org/distributions/gradle-8.1.1-bin.zip`.
- **Files modified:** `gradle/wrapper/gradle-wrapper.properties`
- **Commit:** a3cac62

**2. [Rule 2 - CI safety] Rephrased Javadoc to avoid self-tripping the future grep-lint**
- **Found during:** Task 3 verification
- **Issue:** The `@Mod` class Javadoc the plan provided contains the literal string `import net.minecraft.client.*.` describing what the D-10 firewall forbids. Task 3's automated verify uses `! grep -q "import net.minecraft.client\."` to enforce the firewall — the doc comment hit the grep as a false positive. Plan 05 will add an identical CI grep-lint to the GitHub Actions workflow; leaving the doc comment in would break CI.
- **Fix:** Changed "MUST NOT import net.minecraft.client.*" in the Javadoc to "MUST NOT reference the Minecraft client package." The firewall intent is preserved; the file no longer contains the forbidden import-prefix literal.
- **Files modified:** `src/main/java/com/forgebook/ForgeBookMod.java`
- **Commit:** 10287f4

## Auth Gates

None — Task 1's MDK download over HTTPS succeeded without credentials (Forge maven is public).

## Known Stubs

- **`ForgeBookMod.java` references `com.forgebook.config.ForgebookServerConfig.SPEC` and `ForgebookClientConfig.SPEC`** — these symbols do NOT exist yet. This is by design: Plan 02 creates both `ForgebookServerConfig` and `ForgebookClientConfig`. Until Plan 02 lands, `./gradlew build` will fail with "cannot find symbol" on those two references. This is documented in the plan at Task 5's `<how-to-verify>` ("Plan 02 will complete the config side and this checkpoint will only pass once Plan 02 lands").
- **`src/main/java/com/forgebook/{network,util}`** — empty directories; not yet tracked by git. Plan 03 populates `network/` (SimpleChannel + packets); later plans populate `util/`.
- **`src/main/resources/logo.png`** — 1×1 transparent placeholder. Phase 5 polish replaces with a real icon.

## Threat Flags

None — the plan's `<threat_model>` already accounts for every surface introduced (jsoup relocation, dev-time secrets hygiene, client-dist firewall, MDK sample-code leakage, and license provenance). No new trust-boundary crossings were introduced that were not already in the threat register.

## Self-Check: PASSED

Verified file presence:
- FOUND: `build.gradle`
- FOUND: `gradle.properties`
- FOUND: `settings.gradle`
- FOUND: `gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar` / `gradle/wrapper/gradle-wrapper.properties`
- FOUND: `src/main/java/com/forgebook/ForgeBookMod.java`
- FOUND: `src/main/java/com/forgebook/client/ClientSetup.java`
- FOUND: `src/main/resources/META-INF/mods.toml`
- FOUND: `src/main/resources/pack.mcmeta`
- FOUND: `src/main/resources/logo.png` (67-byte valid PNG, 1×1 RGBA)
- FOUND: `LICENSE`
- FOUND: `THIRD_PARTY_NOTICES.md`
- FOUND: `.gitignore`

Verified commits in `git log`:
- FOUND: a3cac62 chore(01-01): bootstrap Forge 1.20.1-47.4.18 MDK
- FOUND: 57176c5 feat(01-01): configure FG6 + Parchment + jarJar-nested-relocated-jsoup
- FOUND: 10287f4 feat(01-01): @Mod entry ForgeBookMod + client-firewall ClientSetup
- FOUND: 78a04e2 feat(01-01): mod manifest, resource pack, license, gitignore, attribution

Verified acceptance-criteria greps (Task 1–4 `<verify>` blocks) all pass on working tree.

## Requirements Completed

- SCAF-01: Forge 1.20.1-47.4.18 MDK layout on Java 17 — DONE
- SCAF-03: mods.toml with modId, license, displayURL, logoFile, dependencies — DONE
- SCAF-04: `DistExecutor.safeRunWhenOn` client-entry gate — DONE
- SCAF-05: `com.forgebook` package root with client/config/network/util scaffolding — DONE
- SCAF-06: `./gradlew build / runClient / runServer` — STRUCTURALLY READY; pass verification deferred to post-Plan-02 (Task 5 checkpoint)
- SCAF-08: LICENSE (MIT) + THIRD_PARTY_NOTICES.md — DONE
