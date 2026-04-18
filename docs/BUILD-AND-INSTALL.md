# ForgeBook — Build & Install

How to build ForgeBook from source and install the resulting jar on a Minecraft client or dedicated server.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | Java 17 (exactly) | Temurin/Adoptium recommended. Java 21 will fail; Java 16 won't have required APIs. |
| Minecraft | 1.20.1 | No other MC version is supported. |
| Minecraft Forge | 47.4.18 or newer 47.x | Matches `loaderVersion="[47,)"` in `mods.toml`. |
| Gradle wrapper | Ships with repo | **Never** use a system Gradle — always `./gradlew` (or `gradlew.bat` on Windows). |

Both the client and the server need the mod installed. ForgeBook is not a server-only or client-only mod.

## 1. Build the release jar

From the repo root:

```bash
# Linux / macOS
./gradlew clean build

# Windows
gradlew.bat clean build
```

On success the output is at:

```
build/libs/forgebook-1.0.0.jar
```

Expected size: ~600–700 KB (includes the relocated jsoup bundle under `META-INF/jarjar/`).

### Verify the jar is complete

```bash
# Should list a jsoup-*.jar entry under META-INF/jarjar/
jar tf build/libs/forgebook-1.0.0.jar | grep -E '(jarjar|com/forgebook/shadow/jsoup)'
```

If this returns **nothing**, the jsoup bundle is missing and the mod will crash the first time it tries to scrape a mod docs page with `NoClassDefFoundError: com/forgebook/shadow/jsoup/...`. Do not ship that jar — fix the `jarJar` configuration in `build.gradle` first.

### Run the full test suite (optional but recommended)

```bash
./gradlew test
```

All tests should pass. This is the same gate CI runs.

## 2. Install on a client

1. Install Forge 1.20.1-47.4.18 (or a newer 47.x build) using the [Forge installer](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) — pick **Install client**.
2. Launch Minecraft once with the new Forge profile so the profile's `mods/` folder is created. Default location:
   - **Windows:** `%APPDATA%\.minecraft\mods\`
   - **macOS:** `~/Library/Application Support/minecraft/mods/`
   - **Linux:** `~/.minecraft/mods/`
3. Drop `forgebook-1.0.0.jar` into that `mods/` folder.
4. (Optional) Drop any other compatible mods (JEI, Jade, etc.) into the same folder.
5. Launch Minecraft with the Forge profile. ForgeBook should appear in the in-game mod list (main menu → **Mods**).

**No client-side config is required.** The client never holds an API key; all AI traffic originates from the server. The only client-tier setting is `enable_chat_interface` (default `true`), which lives at `config/forgebook-client.toml` and controls whether the inventory chat button is injected.

## 3. Install on a dedicated server

1. Install Forge 1.20.1-47.4.18+ using the [Forge installer](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) — pick **Install server** into an empty directory.
2. Run the Forge server launcher once (`run.bat` / `run.sh`) so it generates `eula.txt` and the `mods/` folder. Accept the EULA.
3. Drop `forgebook-1.0.0.jar` into the server's `mods/` folder.
4. Start the server once to generate the config file at `config/forgebook-server.toml`.
5. Stop the server, edit `config/forgebook-server.toml`:

   ```toml
   # Required for AI responses — get a key at https://console.anthropic.com
   ai_api_key = "sk-ant-..."
   ai_provider = "claude"
   ai_model = "claude-haiku-4-5"

   # Optional — enables modpack-aware answers
   # curseforge_modpack_id = 123456
   # curseforge_api_key = "$2a$10$..."

   # Safety gates (recommended defaults)
   op_only = true
   rate_limit_per_minute = 6
   enable_web_search = true
   ```

6. **Lock the config file** so only the server owner can read it (the file contains secrets):

   ```bash
   # Linux / macOS
   chmod 600 config/forgebook-server.toml

   # Windows (PowerShell)
   icacls config\forgebook-server.toml /inheritance:r /grant:r "%USERNAME%:(R,W)"
   ```

7. Restart the server. Watch the startup log for `[ForgeBook] Ready — provider=claude`. A stack trace about `ai_api_key` means step 5 wasn't saved.

### Hot-reload the config

Changes to `config/forgebook-server.toml` don't require a restart. As an OP:

```
/forgebook reload
```

This atomically swaps the `ConfigSnapshot`, rebuilds the system prompt, and resizes the per-player rate limiter.

## 4. Smoke-test the install

Join the server as an OP and run:

```
/forgebook item
```

while holding an item from any installed mod. You should see a cited answer within a few seconds. If you get `FORBIDDEN`, you aren't OP (the default is `op_only = true` — either `/op` yourself, or set `op_only = false` in the config and `/forgebook reload`).

For the full 9-step pre-release smoke protocol (including edge cases like rate limiting and kill-switch) see [`docs/RELEASE-SMOKE.md`](./RELEASE-SMOKE.md).

## 5. Redistribute the jar

`build/libs/forgebook-1.0.0.jar` is the shippable artifact. It:

- Bundles the relocated jsoup dependency — no extra downloads required.
- Carries its MIT license, `mods.toml` attribution for jsoup, and logo placeholders in-jar.
- Is reproducible: `./gradlew clean build` on a fresh checkout produces the same output bytes (Gradle dependency cache permitting).

To publish a tagged GitHub release:

```bash
git tag -a v1.0.0 -m "ForgeBook v1.0.0"
git push origin v1.0.0
gh release create v1.0.0 build/libs/forgebook-1.0.0.jar \
  --title "ForgeBook v1.0.0" \
  --notes-file docs/RELEASE-SMOKE.md
```

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `./gradlew` says "unsupported Java version" | Wrong JDK on PATH. | Install Temurin JDK 17, set `JAVA_HOME`. |
| Server log: `ai_api_key is required` | Step 5 of server install skipped or typo. | Edit `config/forgebook-server.toml`, restart or `/forgebook reload`. |
| Server log: `NoClassDefFoundError org.jsoup.Jsoup` | jsoup not bundled into the jar (regression in packaging). | Run `jar tf build/libs/forgebook-*.jar \| grep org/jsoup/Jsoup.class` — if empty, the `from({ configurations.jsoupBundled... })` merge in `build.gradle` is broken. Fix before shipping. |
| `/forgebook item` returns `DISABLED` | An OP ran `/forgebook disable`. | `/forgebook enable` (must be OP). |
| `/forgebook item` returns `RATE_LIMITED: retry in Ns` | Per-player bucket exhausted. | Wait; or raise `rate_limit_per_minute` + `/forgebook reload`. |
| Inventory button doesn't appear on the client | Client-side `enable_chat_interface = false`. | Edit `config/forgebook-client.toml`, set to `true`, relaunch. |
| Works in `runClient` but not with the built jar | You're running the dev environment, not the production jar. See the "Verify the jar is complete" section above. | |

## Compatibility

Manually verified against common QoL mods — see [`docs/COMPATIBILITY.md`](./COMPATIBILITY.md).
