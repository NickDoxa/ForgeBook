# ForgeBook

> A Minecraft Forge 1.20.1 mod that answers "what does this item do?" with an AI
> agent grounded in the mod's own documentation — without alt-tabbing to a wiki.

ForgeBook bridges AI with the in-game experience so players can understand the mods
they're using. It ships two player-facing surfaces: a chat UI docked inside the
inventory screen that lets players ask questions of an AI agent with internet and
mod-list awareness, and a `/forgebook item` command that answers "what can I do
with this?" for any mod-added item — grounded in that mod's documentation URL (and,
when configured, the modpack's CurseForge metadata). A player holding an unfamiliar
item from an unfamiliar mod gets a grounded, trustworthy answer without alt-tabbing
to a wiki.

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
- An Anthropic API key (AI provider) — obtain at [console.anthropic.com](https://console.anthropic.com)
- (Optional) A CurseForge API key for modpack context — obtain at [console.curseforge.com](https://console.curseforge.com)
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

   On Windows, set the file's NTFS ACL so only the server-running account can
   read it.
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
| `ai_api_key` | string | `""` | Your Anthropic API key. Secret — see "Security Posture" below. |
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
  The client never sees, holds, or transmits API keys.
- The client-side code is firewalled from key material by package discipline
  (`com.forgebook.client.*` cannot import `com.forgebook.ai.*`,
  `com.forgebook.safety.*`, or `com.forgebook.config.ApiKey`) — enforced at CI time
  via the package-firewall test.
- `SafeHttpFetcher` (server-side) refuses to fetch `http://` URLs, private-IP URLs,
  loopback URLs, and responses larger than 1 MB — so a compromised mod author
  publishing a malicious `displayURL` cannot trick the server into hitting internal
  infrastructure (SSRF guard).
- The `forgebook-server.toml` file contains plaintext API keys. **Restrict file
  permissions to 600 (owner-only)** via `chmod 600 config/forgebook-server.toml`
  as shown in Installation step 4. On Windows, set the file's NTFS ACL so only the
  server-running account can read it.
- Log output is redacted by the Log4j2 `ApiKeyScrubFilter`, which scrubs any
  `Authorization`, `x-api-key`, `sk-ant-`, and `sk-proj-` substrings from
  messages before they hit disk.
- Rate-limit defaults (`op_only=true`, 5 req/min per player) are designed to
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

ForgeBook ships with two placeholder logo slots. To brand your install:

1. **Mods-list logo** (shown in Minecraft's Mods menu): replace
   `src/main/resources/logo.png`. Recommended size: 128×128 to 256×256 PNG,
   square or slightly wider. Must be at JAR root — do not move to a subfolder.

2. **In-chat logo** (shown in ForgeBook's chat panel header — reserved for v2):
   replace `src/main/resources/assets/forgebook/textures/gui/logo.png`.
   Recommended size: 64×64 PNG. Transparent background recommended.

Re-build with `./gradlew build`. Both placeholders load cleanly out of the box
as 1×1 transparent PNGs, so the mod will function without replacement.

## Credits

Third-party components are credited in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## License

MIT — see [`LICENSE`](LICENSE).
