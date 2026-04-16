---
status: partial
phase: 02-ai-engine-grounding
source: [02-VERIFICATION.md]
started: 2026-04-16T14:30:00Z
updated: 2026-04-16T14:30:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. Real ClaudeProvider v1/messages round-trip (SC-1, AI-01)
expected: With a valid Anthropic `ai_api_key` and `ai_model=claude-haiku-4-5` in `forgebook-server.toml`, a `/forgebook ask` (once Phase 3 exposes it) or a crafted `ChatRequestPacket` produces a non-error `FinalReply` whose text is a plausible natural-language answer; server log shows one outbound `POST https://api.anthropic.com/v1/messages` with headers `x-api-key=<redacted>`, `anthropic-version=2023-06-01`.
result: [pending]

### 2. Real CurseForge enrichment populates system prompt exactly once (SC-4, CF-01/CF-02)
expected: With `curseforge_modpack_id` + `curseforge_api_key` set on a server that has network egress, the log shows exactly one `GET https://api.curseforge.com/v1/mods/{id}` during startup, `SystemPromptCache.get()` after `ServerStartedEvent` returns a string containing the returned modpack name and a ≤500-char summary excerpt; restart without the modpack_id shows the prompt building anyway with no CF line and no error.
result: [pending]

### 3. Agent multi-step tool loop end-to-end against live Claude (SC-2, SC-5)
expected: A question whose answer requires `FetchModDocsPageTool` + `WebSearchTool` (e.g. about a synthetic mod with empty `getModURL()`) produces a `FinalReply` that cites at least one source URL; server log shows `ToolUses → tool execution → next turn → FinalReply` sequence within ≤6 iterations; a 429 from Claude triggers `RetryPolicy` backoff and eventual success or clean `RATE_LIMITED` error.
result: [pending]

### 4. Live DuckDuckGo / Brave web search adapter returns usable results
expected: With `enable_web_search = true` and `web_search_provider = DUCKDUCKGO` (default) or `BRAVE` + valid `web_search_api_key`, calling `WebSearchTool` with `{query: "create mod steam engine"}` returns a non-empty `SearchResult` list; each result URL passes `SafeHttpFetcher`'s scheme + private-IP checks; the 8000-char truncation boundary in `PromptFraming` is honored.
result: [pending]

## Summary

total: 4
passed: 0
issues: 0
pending: 4
skipped: 0
blocked: 0

## Gaps
