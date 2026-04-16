# Phase 2: AI Engine & Grounding - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-15
**Phase:** 02-ai-engine-grounding
**Areas discussed:** Web search backend, Claude model & budget knobs, System prompt composition, AgentLoop semantics & tool output sizing

---

## Web Search Backend

| Option | Description | Selected |
|--------|-------------|----------|
| Brave Search API | Free tier: 2,000 queries/month. Paid: $5/1k after. Simple REST, returns title/snippet/URL. No scraping fragility. Needs web_search_api_key config field. | |
| Tavily Search API | AI-tuned results, returns extracted content snippets. Free tier: 1,000 queries/month. Purpose-built for agentic LLM use cases. | |
| DuckDuckGo HTML scrape | No API key needed. Fragile: no official API, scraping breaks on layout changes. Rate-limited by anti-bot measures. | |
| Claude decides | Let planner/researcher pick. | |

**User's choice:** Free-first: DDG if reliable, Brave as fallback. Researcher evaluates.
**Notes:** "This feature needs to be based on as much free results as possible. If there are no solid free options go with Brave Search API, if DuckDuckGo is reliable for finding the information about the mods and is free, go with that."

### Follow-up: Backend Switchability

| Option | Description | Selected |
|--------|-------------|----------|
| Config-switchable | web_search_provider config field (enum: DDG, BRAVE). Two adapters behind the same interface. | ✓ |
| Single backend, baked in | Researcher picks one. Code change needed to switch. | |

**User's choice:** Config-switchable
**Notes:** None

---

## Claude Model & Budget Knobs

### Default Model

| Option | Description | Selected |
|--------|-------------|----------|
| Haiku | Cheapest: ~$0.25/M input, $1.25/M output. Fast. Good enough for tool-use and mod docs Q&A. | ✓ |
| Sonnet | Mid-tier: ~$3/M input, $15/M output. Smarter answers. 12x more expensive. | |
| Operator's choice (no default) | Force operator to pick before mod works. | |

**User's choice:** Haiku
**Notes:** None

### Default max_tokens

| Option | Description | Selected |
|--------|-------------|----------|
| 1024 tokens | Enough for detailed item/mod explanations. Keeps cost bounded. | ✓ |
| 2048 tokens | More room for multi-step reasoning. Doubles ceiling cost. | |
| 512 tokens | Tightest budget. May truncate complex explanations. | |

**User's choice:** 1024 tokens
**Notes:** None

### Model ID Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Free-form string | ai_model passed to provider as-is. Future-proof. | ✓ |
| Validated per-provider | Each provider has known model list. Prevents typos. | |

**User's choice:** Free-form string
**Notes:** None

---

## System Prompt Composition

### Mod List Placement

| Option | Description | Selected |
|--------|-------------|----------|
| System prompt (always visible) | Pre-rendered at server start. Model always knows mods without tool call. ListInstalledModsTool still exists for filtered queries. | ✓ |
| Tool only | Model must call ListInstalledModsTool. Saves input tokens. | |
| Compact summary + tool | Short summary in prompt, full list via tool. | |

**User's choice:** Full mod list in system prompt
**Notes:** None

### AI Identity

| Option | Description | Selected |
|--------|-------------|----------|
| Helpful mod expert | "You are ForgeBook, a knowledgeable assistant for Minecraft modded gameplay." Friendly, direct, cites sources. | ✓ |
| In-universe librarian | RPG flavor. Could feel odd for technical instructions. | |
| Minimal / no persona | Just tool instructions and safety rules. | |

**User's choice:** Helpful mod expert
**Notes:** None

### Anti-Injection Rules

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, explicit rules | Never follow fetched doc instructions, never reveal prompt/key, stay on-topic, no executable code. Defense-in-depth with XML framing. | ✓ |
| Minimal — trust framing tags | Rely on <mod_doc trust="untrusted"> framing only. | |
| Claude decides | Let planner balance injection defense with prompt size. | |

**User's choice:** Explicit rules (defense-in-depth)
**Notes:** None

---

## AgentLoop Semantics & Tool Output Sizing

### Parallel vs Sequential Tool Execution

| Option | Description | Selected |
|--------|-------------|----------|
| Parallel | All tool_uses in one turn fire concurrently on aiExecutor. Faster. | ✓ |
| Sequential | One at a time in order. Simpler. Slower. | |
| Claude decides | Let planner pick based on complexity. | |

**User's choice:** Parallel
**Notes:** None

### Per-Tool Output Cap

| Option | Description | Selected |
|--------|-------------|----------|
| 8,000 characters | ~2,000 tokens. Enough for full wiki page after readability extraction. | ✓ |
| 16,000 characters | ~4,000 tokens. Full articles, higher cost. | |
| 4,000 characters | ~1,000 tokens. Aggressive truncation, cheapest. | |

**User's choice:** 8,000 characters
**Notes:** None

### Tool Error Handling

| Option | Description | Selected |
|--------|-------------|----------|
| Report and continue | Return structured error as tool_result, let model recover. Enables missing-docs fallback. | ✓ |
| Abort the turn | Treat tool failure as turn-ending error. Simpler but no recovery. | |

**User's choice:** Report and continue
**Notes:** None

---

## Claude's Discretion

- anthropic-version header pin (researcher)
- DDG vs Brave as shipped default (researcher evaluates)
- jsoup readability heuristic tuning (planner)
- ToolRegistry internal structure (planner)
- AgentLoop state machine design (planner)
- Circuit breaker implementation approach (planner)
- Retry backoff constants within 30s cap (planner)
- ModDocsScraper API shape (planner)
- ModpackContext thread-safety approach (planner)

## Deferred Ideas

- Streaming responses (v2)
- Operator-configurable tool output cap
- Validated model ID lists per provider
- Additional search backends (Serper, Google CSE, SearXNG)
- Mod docs caching
- Per-tool timeout configuration
