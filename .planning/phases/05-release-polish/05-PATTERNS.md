# Phase 5: Release Polish — Pattern Map

**Mapped:** 2026-04-16
**Files analyzed:** 14 (4 created, 10 modified)
**Analogs found:** 14 / 14

Phase 5 is **closeout polish**, not new behaviour. The pattern work splits three ways:

1. **i18n refactor (REL-02)** — every new key shape is already established by Phase 4's `ChatPanelWidget` + `ErrorCard` + `en_us.json`. The analog is trivially in-tree.
2. **Packaging & assets (REL-01, REL-05)** — analog is the existing `META-INF/mods.toml`, `build.gradle`, and `src/main/resources/logo.png`. Edits are mechanical.
3. **Docs (REL-03, REL-04)** — no in-tree analog. Template ships from RESEARCH.md §README & Docs Structure / §Compat Matrix Protocol / §Prod-Jar Smoke Protocol.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` *(MOD)* | command-handler | request-response (tick-thread send) | `src/main/java/com/forgebook/client/ui/ChatScreen.java` L70,L105,L113 (translatable call shape) | role-partial (different tier, same i18n call shape) |
| `src/main/java/com/forgebook/command/AdminSubcommands.java` *(MOD)* | command-handler | request-response (sendSuccess broadcast) | `src/main/java/com/forgebook/client/ui/ChatPanelWidget.java` L215,L222 (headingKey → translatable) | role-partial |
| `src/main/java/com/forgebook/command/AskSubcommand.java` *(MOD)* | command-handler | request-response + off-tick hop | Itself (same-file sendFailure helper L185-189) — translate the string at the Component-construction site | exact-role |
| `src/main/java/com/forgebook/command/ItemSubcommand.java` *(MOD)* | command-handler | request-response + off-tick submit | Itself (same-file sendFailure helper L249-254) | exact-role |
| `src/main/java/com/forgebook/ai/RagItemPipeline.java` *(MOD)* | service (RAG pipeline) | off-tick transform (Feedback seam) | Itself — `feedbackOf(src)` lambda L278-287 constructs Components from Strings; wrap in translatable there | exact-role |
| `src/main/java/com/forgebook/safety/Authorizer.java` *(MOD)* | service (auth gate) | pure function returning sealed Result | `src/main/java/com/forgebook/ai/AiTurn.java` pattern (sealed Result with record carriers) — but the closest is Authorizer ITSELF; change `Denied` record's `String humanReadable` → `Component feedback` | exact-role |
| `src/main/java/com/forgebook/ai/AiDispatcher.java` *(MOD)* | service (dispatcher) | pure function returning sealed Result | Mirrors `Authorizer.Denied` refactor; `Error(code, humanReadable)` → `Error(code, Component feedback)` in lockstep | exact-role |
| `src/main/resources/assets/forgebook/lang/en_us.json` *(MOD)* | i18n resource | static JSON map | Itself (existing 21-key file; keeps same `forgebook.<area>.<key>` namespace) | exact |
| `src/main/resources/META-INF/mods.toml` *(MOD)* | build manifest | config TOML | Itself (already 95% complete — just verify `credits`, `issueTrackerURL`) | exact |
| `build.gradle` *(MOD)* | build script | Gradle DSL | Itself (change `version = '0.1.0'` → `'1.0.0'` at L12) | exact |
| `src/main/resources/assets/forgebook/textures/gui/logo.png` *(CREATE)* | texture resource | PNG binary | `src/main/resources/logo.png` (identical 1×1 placeholder copy) | exact |
| `README.md` *(CREATE)* | docs | markdown | RESEARCH §README & Docs Structure (L393-516) | template-only |
| `docs/COMPATIBILITY.md` *(CREATE)* | docs | markdown | RESEARCH §Compat Matrix Protocol (L528-603) | template-only |
| `docs/RELEASE-SMOKE.md` *(CREATE)* | docs | markdown | RESEARCH §Prod-Jar Smoke Protocol (L606-720) | template-only |

## Key Architectural Finding: `Authorizer.Denied` Signature Change

The Denied record's `humanReadable` field flows through two paths:

1. **Network wire** — `ChatRequestHandler` L142: `new ChatErrorPacket(pkt.requestId(), d.code(), d.humanReadable())`. The packet's `humanReadable` is a `String` on the wire (see `ChatErrorPacket.java` L23: `public record ChatErrorPacket(UUID requestId, ErrorCode code, String humanReadable)` — buf.writeUtf at L39).
2. **Command feedback** — `AskSubcommand.java` L148, `ItemSubcommand.java` L216, `RagItemPipeline.java` L169: `sendFailure(src, d.humanReadable())` which currently wraps in `Component.literal(text)`.

**Implication for Pattern 2 (humanReadable refactor):** If `Denied.humanReadable` becomes `Component feedback`, the network wire still needs a `String`. Two options:

- **Option C (RESEARCH's recommendation):** `Denied(ErrorCode code, Component feedback)`. Network wire calls `d.feedback().getString()` (returns translation key when no language loaded — **server-side default**, which is fine because the client will translate on receive via its local en_us.json). Command feedback passes `d.feedback()` directly to `src.sendFailure`.
- **Option A (fallback):** `Denied(ErrorCode code, String translationKey, Object[] args)`. Network wire encodes `translationKey + args`; client side already translates via `ErrorCard.bodyKey(code)` so the wire payload can be ignored on the client (per Phase 4 UI-08: client renders `Component.translatable(ErrorCard.bodyKey(ec.code()))`, L215-222 of ChatPanelWidget — the `humanReadable` wire field is already effectively unused on the client-rendered error card).

**Recommendation from pattern analysis: Option A is actually safer.** Reason: `ChatPanelWidget.java` L222 already reads `ec.humanReadable()` as a fallback ONLY when the key-based translation is empty, and Phase 4 made `ErrorCard` key-based. So the wire `humanReadable` field is **already a dead fallback** on the client. Safest refactor: keep the wire string as it is (it's only used server-side for audit logging and as a client-side fallback that never fires), and swap the COMMAND-feedback path to use `Component.translatable(...)` directly. This splits the Denied type concern cleanly.

**Concrete minimal refactor for Authorizer:** Add a second record field `Component feedback` alongside the existing `String humanReadable`. Both constructed from the same key; `humanReadable` is the resolved default-locale English fallback (for audit + wire), `feedback` is the `Component.translatable(key, args)`. Consumers pick the right one. No wire-format breakage.

## Pattern Assignments

### `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` (MOD, i18n refactor)

**Analog:** `src/main/java/com/forgebook/client/ui/ChatScreen.java` L70, L105

**Current literal to replace** (L72):
```java
ctx.getSource().sendSuccess(
    () -> Component.literal("ForgeBook config + system prompt reloaded."), true);
```

**Replace with:**
```java
ctx.getSource().sendSuccess(
    () -> Component.translatable("forgebook.command.reload.success"), true);
```

**Analog pattern shape** (ChatScreen.java L70):
```java
super(Component.translatable("forgebook.chat.title"));
```

**en_us.json add:**
```json
"forgebook.command.reload.success": "ForgeBook config + system prompt reloaded."
```

---

### `src/main/java/com/forgebook/command/AdminSubcommands.java` (MOD, BiConsumer signature swap)

**Analog:** Itself — public wrapper L51-55 already has the seam for the swap; we just change WHAT the BiConsumer receives.

**Current flow (L51-55, L79-89):**
```java
public static int executeDisable(CommandContext<CommandSourceStack> ctx) {
    return executeDisableInternal(
        ctx.getSource().getTextName(),
        (text, broadcast) -> ctx.getSource().sendSuccess(
            () -> Component.literal(text), broadcast));   // <-- literal() here
}

static int executeDisableInternal(String textName, BiConsumer<String, Boolean> send) {
    boolean wasEnabled = !KillSwitch.isDisabled();
    KillSwitch.setDisabled(true);
    String msg = wasEnabled
        ? "ForgeBook disabled. New requests will return DISABLED."
        : "ForgeBook is already disabled.";
    send.accept(msg, true);
```

**Refactor per RESEARCH Pattern 1 Option A** (minimal — keep `BiConsumer<String, Boolean>` signature, change meaning of the String from "prose" to "translation key"):

```java
// Public wrapper translates at the edge:
public static int executeDisable(CommandContext<CommandSourceStack> ctx) {
    return executeDisableInternal(
        ctx.getSource().getTextName(),
        (key, broadcast) -> ctx.getSource().sendSuccess(
            () -> Component.translatable(key), broadcast));  // literal → translatable
}

// Core unchanged semantics, strings now carry keys:
static int executeDisableInternal(String textName, BiConsumer<String, Boolean> send) {
    boolean wasEnabled = !KillSwitch.isDisabled();
    KillSwitch.setDisabled(true);
    String key = wasEnabled
        ? "forgebook.command.disable.success"
        : "forgebook.command.disable.already";
    send.accept(key, true);
```

**Special case — executeStats (L110-114)** emits `StatsAccumulator.render()` which is **structured data, not prose** — RESEARCH i18n audit table explicitly excludes it. Keep as-is:
```java
static int executeStatsInternal(BiConsumer<String, Boolean> send) {
    String rendered = StatsAccumulator.render();
    send.accept(rendered, false);  // STILL literal — render() emits tabular text
    return Command.SINGLE_SUCCESS;
}
```

This means the production wrapper for `executeStats` MUST stay on `Component.literal`, while `executeDisable`/`executeEnable` wrappers use `Component.translatable`. Two different BiConsumer bindings. Document with a comment.

**en_us.json adds (4 keys):**
```json
"forgebook.command.disable.success": "ForgeBook disabled. New requests will return DISABLED.",
"forgebook.command.disable.already": "ForgeBook is already disabled.",
"forgebook.command.enable.success":  "ForgeBook enabled. New requests will be processed.",
"forgebook.command.enable.already":  "ForgeBook is already enabled."
```

---

### `src/main/java/com/forgebook/command/AskSubcommand.java` (MOD, i18n + Denied rewiring)

**Analog:** Itself — the `sendFailure(CommandSourceStack, String)` helper L185-189 is the single chokepoint where `Component.literal(text)` is invoked.

**Current chokepoint (L185-189):**
```java
private static void sendFailure(CommandSourceStack src, String text) {
    if (src != null) {
        src.sendFailure(Component.literal(text));
    }
    failureSinkForTests.accept(text);
}
```

**Three literal call sites passing English prose (L140, L166, L173):**
```java
sendFailure(src, "ForgeBook not initialized — check server logs.");   // L140 → key
sendFailure(src, "Internal error.");                                   // L166 → key
sendFailure(src, "Server is busy. Try again.");                        // L173 → key
```

**One site already receiving a key-candidate string (L148 — depends on Denied refactor):**
```java
sendFailure(src, d.humanReadable());                                   // L148 — depends on Denied
```

**Refactor shape (two helper overloads):**
```java
// Keep the String-taking helper for raw prose emitted inline (Internal error., etc.)
// BUT switch the production body of those prose call sites to pass KEYS instead,
// and change the helper to wrap in Component.translatable.
//
// Tradeoff: test assertions currently do `assertEquals("Server is busy. Try again.", failureSink.last);`
// (see AskSubcommandTest L249). Those assertions flip to key-based:
//   assertEquals("forgebook.command.overloaded", failureSink.last);

private static void sendFailureKey(CommandSourceStack src, String key, Object... args) {
    if (src != null) {
        src.sendFailure(Component.translatable(key, args));
    }
    failureSinkForTests.accept(key);  // tests now assert on key, not prose
}
```

**Call-site refactor table:**
| Line | Before | After |
|------|--------|-------|
| 140 | `sendFailure(src, "ForgeBook not initialized — check server logs.");` | `sendFailureKey(src, "forgebook.command.not_initialized");` |
| 148 | `sendFailure(src, d.humanReadable());` | depends on Denied refactor — if Option A (split fields), becomes `sendFailureComponent(src, d.feedback());` or similar |
| 166 | `tickThreadHop.accept(() -> sendFailure(src, "Internal error."));` | `tickThreadHop.accept(() -> sendFailureKey(src, "forgebook.command.internal_error"));` |
| 173 | `sendFailure(src, "Server is busy. Try again.");` | `sendFailureKey(src, "forgebook.command.overloaded");` |

**Same for sendSuccess (L178-183)** — the AI reply path at L159 (`sendSuccess(src, r.text())`) is **legitimate raw prose from the AI**, NOT a translation key. Keep that branch on `Component.literal(text)` — document why with an inline comment.

---

### `src/main/java/com/forgebook/command/ItemSubcommand.java` (MOD, same shape as Ask)

**Analog:** AskSubcommand (sibling file) + self (L249-254).

**Call-site refactor table:**
| Line | Before | After |
|------|--------|-------|
| 191 | `sendFailure(src, "Hold an item in your main hand, or use /forgebook item <item>.");` | `sendFailureKey(src, "forgebook.command.item.no_held");` |
| 197 | `sendFailure(src, "Could not identify item.");` | `sendFailureKey(src, "forgebook.command.item.unknown");` |
| 208 | `sendFailure(src, "ForgeBook not initialized — check server logs.");` | `sendFailureKey(src, "forgebook.command.not_initialized");` *(shared key with Ask)* |
| 216 | `sendFailure(src, d.humanReadable());` | depends on Denied refactor |
| 232 | `sendFailure(src, "Internal error.");` | `sendFailureKey(src, "forgebook.command.internal_error");` *(shared key)* |
| 239 | `sendFailure(src, "Server is busy. Try again.");` | `sendFailureKey(src, "forgebook.command.overloaded");` *(shared key)* |

**en_us.json adds (from Ask+Item combined — 5 unique keys):**
```json
"forgebook.command.not_initialized": "ForgeBook not initialized — check server logs.",
"forgebook.command.internal_error":  "Internal error.",
"forgebook.command.overloaded":      "Server is busy. Try again.",
"forgebook.command.item.no_held":    "Hold an item in your main hand, or use /forgebook item <item>.",
"forgebook.command.item.unknown":    "Could not identify item."
```

---

### `src/main/java/com/forgebook/ai/RagItemPipeline.java` (MOD, Feedback seam i18n)

**Analog:** Itself — `feedbackOf(src)` lambda L278-287 is the SINGLE chokepoint where `Component.literal(text)` is invoked on the RAG-pipeline side.

**Current chokepoint (L278-287):**
```java
private static Feedback feedbackOf(CommandSourceStack src) {
    return new Feedback() {
        @Override public void sendSuccess(String text) {
            src.sendSuccess(() -> Component.literal(text), false);
        }
        @Override public void sendFailure(String text) {
            src.sendFailure(Component.literal(text));
        }
    };
}
```

**Refactor: widen the `Feedback` interface to accept keys or components, OR flip every literal inside `runInternal` to pass translation keys.**

**Recommended: extend `Feedback` with key-based methods**, keep string-based ones for the AI reply (which is raw prose):
```java
interface Feedback {
    void sendSuccess(String text);          // keep — AI reply is prose
    void sendFailure(String text);          // keep — used by Denied.humanReadable in Option A
    default void sendFailureKey(String key, Object... args) {
        // default impl: concat key for tests
        sendFailure(key);
    }
}

// Production override constructs a translatable component:
private static Feedback feedbackOf(CommandSourceStack src) {
    return new Feedback() {
        @Override public void sendSuccess(String text) {
            src.sendSuccess(() -> Component.literal(text), false);  // AI reply — literal OK
        }
        @Override public void sendFailure(String text) {
            src.sendFailure(Component.literal(text));
        }
        @Override public void sendFailureKey(String key, Object... args) {
            src.sendFailure(Component.translatable(key, args));
        }
    };
}
```

**Call-site refactor table inside `runInternal`:**
| Line | Before | After |
|------|--------|-------|
| 157 | `feedback.sendFailure("ForgeBook not initialized — check server logs.");` | `feedback.sendFailureKey("forgebook.command.not_initialized");` |
| 169 | `feedback.sendFailure(d.humanReadable());` | depends on Denied refactor (see architectural finding above) |
| 178-180 | `feedback.sendFailure("No documentation URL is registered for mod '" + modId + "'. Try /forgebook ask <question>...");` | `feedback.sendFailureKey("forgebook.command.item.no_docs_url", modId);` — NEW KEY |
| 197 | `feedback.sendFailure("Could not fetch mod documentation. Try again later.");` | `feedback.sendFailureKey("forgebook.command.item.fetch_failed");` — NEW KEY (missed in RESEARCH audit) |
| 225 | `feedback.sendFailure("AI provider returned an error.");` | `feedback.sendFailureKey("forgebook.command.provider_error");` |
| 241 | `String reply = fr.text() + "\n\nSource: " + url;` | `String reply = fr.text() + "\n\n" + Component.translatable("forgebook.command.item.source_label", url.toString()).getString();` *(parameterized "%s")* |
| 248 | `feedback.sendFailure(mapped.humanReadable());` | depends on AiDispatcher.Error refactor |
| 256 | `feedback.sendFailure("Unexpected provider response.");` | `feedback.sendFailureKey("forgebook.command.provider_unexpected");` |

**en_us.json adds (7 keys):**
```json
"forgebook.command.item.no_docs_url":    "No documentation URL is registered for mod '%s'. Try /forgebook ask <question> for a web-search-backed answer.",
"forgebook.command.item.fetch_failed":   "Could not fetch mod documentation. Try again later.",
"forgebook.command.item.source_label":   "Source: %s",
"forgebook.command.provider_error":      "AI provider returned an error.",
"forgebook.command.provider_unexpected": "Unexpected provider response."
```

**Note for planner:** RESEARCH audit missed L178-180 (no_docs_url) and L197 (fetch_failed). Surface as an "audit delta" in the plan — two additional keys beyond RESEARCH's estimate.

---

### `src/main/java/com/forgebook/safety/Authorizer.java` (MOD, Denied record refactor)

**Analog:** Itself (sole Denied construction site L76-105).

**Key constraint:** `ChatErrorPacket.humanReadable` is WIRE PAYLOAD (`String`, `buf.writeUtf(p.humanReadable, 512)`). Cannot simply drop the String — it ships client-ward.

**Recommended refactor (Option A per architectural finding above — split fields, no wire break):**
```java
public record Denied(ErrorCode code, String humanReadable, Component feedback) implements Result {}
```

Builder helpers:
```java
private static Denied denied(ErrorCode code, String key) {
    // Resolve default-locale fallback for wire + audit
    String fallback = com.forgebook.safety.I18nFallback.get(key);  // new tiny util; hardcoded map of the 4 keys
    return new Denied(code, fallback, Component.translatable(key));
}

private static Denied deniedWithArg(ErrorCode code, String key, Object arg) {
    String fallback = com.forgebook.safety.I18nFallback.get(key, arg);
    return new Denied(code, fallback, Component.translatable(key, arg));
}
```

**Call-site refactor (4 sites L78-103):**
| Line | Before | After |
|------|--------|-------|
| 79-80 | `return new Denied(ErrorCode.DISABLED, "ForgeBook is temporarily disabled by an operator.");` | `return denied(ErrorCode.DISABLED, "forgebook.command.denied.disabled");` |
| 85-86 | `return new Denied(ErrorCode.FORBIDDEN, "Only players may invoke ForgeBook.");` | `return denied(ErrorCode.FORBIDDEN, "forgebook.command.denied.not_player");` |
| 91-92 | `return new Denied(ErrorCode.FORBIDDEN, "ForgeBook is OP-only on this server.");` | `return denied(ErrorCode.FORBIDDEN, "forgebook.command.denied.forbidden");` |
| 99-100 | `return new Denied(ErrorCode.RATE_LIMITED, "Rate limit reached. Try again in " + l.retryAfterSeconds() + "s.");` | `return deniedWithArg(ErrorCode.RATE_LIMITED, "forgebook.command.denied.rate_limited", l.retryAfterSeconds());` |

**Alternative simpler refactor (avoid new I18nFallback class):** hardcode the English fallback inline per call:
```java
return new Denied(ErrorCode.DISABLED,
    "ForgeBook is temporarily disabled by an operator.",
    Component.translatable("forgebook.command.denied.disabled"));
```
Verbose but self-contained. Planner picks.

**en_us.json adds (4 keys):**
```json
"forgebook.command.denied.disabled":     "ForgeBook is temporarily disabled by an operator.",
"forgebook.command.denied.not_player":   "Only players may invoke ForgeBook.",
"forgebook.command.denied.forbidden":    "ForgeBook is OP-only on this server.",
"forgebook.command.denied.rate_limited": "Rate limit reached. Try again in %ds."
```

**Consumer updates required (Authorizer downstream):**
- `AskSubcommand.java` L148: `sendFailure(src, d.humanReadable())` → `src.sendFailure(d.feedback())` (pass Component directly)
- `ItemSubcommand.java` L216: same shape
- `RagItemPipeline.java` L169: `feedback.sendFailure(d.humanReadable())` → add `feedback.sendFailureComponent(d.feedback())` to the Feedback interface, OR pass key via existing sendFailureKey
- `ChatRequestHandler.java` L142: `new ChatErrorPacket(pkt.requestId(), d.code(), d.humanReadable())` — KEEP AS-IS (wire uses the String fallback)

---

### `src/main/java/com/forgebook/ai/AiDispatcher.java` (MOD, Error record refactor + 7 literal sites)

**Analog:** Mirrors Authorizer.Denied refactor. Same argument applies — `Error.humanReadable` ships through `ChatErrorPacket.humanReadable` at wire level (ChatRequestHandler.java L164). Keep String field, add Component field.

**Recommended refactor:**
```java
public record Error(ErrorCode code, String humanReadable, Component feedback) implements Result {}
```

**Seven literal sites to refactor** (L94, L135, L165, L180-194 — switch expression in mapError):

| Location | Before | Key |
|----------|--------|-----|
| L94 | `new Error(ErrorCode.PROVIDER, "ForgeBook not initialized — check server logs.")` | `forgebook.command.not_initialized` *(shared)* |
| L135 | `new Error(ErrorCode.PROVIDER, "Unexpected internal error.")` | `forgebook.command.provider.unexpected_internal` *(NEW)* |
| L165 | `new Error(ErrorCode.PROVIDER, "AI agent did not produce a final reply.")` | `forgebook.command.provider.no_final_reply` *(NEW)* |
| L180-181 | `new Error(ErrorCode.TRANSPORT, "Transient network issue. Try again.")` | `forgebook.command.provider.transport` *(NEW)* |
| L182-183 | `new Error(ErrorCode.PROVIDER, "AI provider returned an error.")` | `forgebook.command.provider_error` *(shared with RagItemPipeline)* |
| L184-185 | `new Error(ErrorCode.OVERLOADED, "Server is busy. Try again.")` | `forgebook.command.overloaded` *(shared)* |
| L186-187 | `new Error(ErrorCode.RATE_LIMITED, "You're sending requests too fast.")` | `forgebook.command.provider.rate_limited` *(NEW — distinct from Authorizer's rate_limited since no retry-after)* |
| L188-189 | `new Error(ErrorCode.PROVIDER, "This AI provider is not implemented in v1.")` | `forgebook.command.provider.not_implemented` *(NEW)* |
| L190-191 | `new Error(ErrorCode.PROVIDER, "AI provider is temporarily unavailable.")` | `forgebook.command.provider.circuit_open` *(NEW)* |
| L192-194 | `new Error(ErrorCode.PROVIDER, "AI agent could not complete ... " + AgentLoop.MAX_ITERATIONS + " iterations.")` | `forgebook.command.provider.iteration_cap` with `%d` arg |

**en_us.json adds (7 new NEW keys, plus reuse of 3 shared):**
```json
"forgebook.command.provider.unexpected_internal": "Unexpected internal error.",
"forgebook.command.provider.no_final_reply":      "AI agent did not produce a final reply.",
"forgebook.command.provider.transport":           "Transient network issue. Try again.",
"forgebook.command.provider.rate_limited":        "You're sending requests too fast.",
"forgebook.command.provider.not_implemented":     "This AI provider is not implemented in v1.",
"forgebook.command.provider.circuit_open":        "AI provider is temporarily unavailable.",
"forgebook.command.provider.iteration_cap":       "AI agent could not complete the task within %d iterations."
```

---

### `src/main/resources/assets/forgebook/lang/en_us.json` (MOD, key additions)

**Analog:** Itself (existing 21-key file).

**Naming convention** (from Phase 4 existing keys):
- `forgebook.chat.<widget>.<property>` — client chat UI
- `forgebook.error.<code>.<field>` — client error cards (heading/body)
- **NEW for Phase 5:** `forgebook.command.<subcommand>.<outcome>` — server command feedback
- **NEW for Phase 5:** `forgebook.command.denied.<reason>` — Authorizer denials
- **NEW for Phase 5:** `forgebook.command.provider.<kind>` — AiDispatcher errors
- **NEW for Phase 5 (shared across surfaces):** `forgebook.command.<outcome>` (e.g. `not_initialized`, `internal_error`, `overloaded`)

**Key count accounting:**
- Phase 4: 21 keys (locked)
- Phase 5 adds (per-file breakdown above, deduplicated):
  - ForgebookReloadCommand: 1 (`reload.success`)
  - AdminSubcommands: 4 (`disable.success`, `disable.already`, `enable.success`, `enable.already`)
  - AskSubcommand: 3 shared (`not_initialized`, `internal_error`, `overloaded`)
  - ItemSubcommand: 2 unique + reuses Ask's 3 (`item.no_held`, `item.unknown`)
  - RagItemPipeline: 5 (`item.no_docs_url`, `item.fetch_failed`, `item.source_label`, `provider_error`, `provider_unexpected`)
  - Authorizer: 4 (`denied.disabled`, `denied.not_player`, `denied.forbidden`, `denied.rate_limited`)
  - AiDispatcher: 7 new (`provider.unexpected_internal`, `provider.no_final_reply`, `provider.transport`, `provider.rate_limited`, `provider.not_implemented`, `provider.circuit_open`, `provider.iteration_cap`)
- **Phase 5 total new keys: 26** (RESEARCH estimated ~17; delta is +9 from RagItemPipeline's missed L178/L197 + AiDispatcher's full 7-site audit)
- **Grand total after Phase 5: 47 keys**

**Format:** Lexicographic order preserved within each `forgebook.<area>.*` block. JSON properly escaped (`\u2026` for ellipsis when needed; existing file uses it at `forgebook.chat.input.placeholder`).

---

### `src/main/resources/META-INF/mods.toml` (MOD, field polish)

**Analog:** Itself (current content on next line).

**Current state (already mostly complete):**
- L3: `license="MIT"` ✓
- L6: `modId="forgebook"` ✓
- L8: `displayName="ForgeBook"` ✓
- L9: `displayURL="https://github.com/Nick-Doxa/ForgeBook"` ✓
- L10: `logoFile="logo.png"` ✓ (JAR root — correct per CLAUDE.md anti-pattern)
- L11: `credits=""` ← **EMPTY; REL-03 asks this be finalized**
- L12: `authors="Nick Doxa"` ✓
- L13-16: description present ✓

**Changes required:**
| Field | Current | After |
|-------|---------|-------|
| `credits` (L11) | `""` | `"jsoup by Jonathan Hedley (MIT) — bundled as com.forgebook.shadow.jsoup"` |
| (NEW) `issueTrackerURL` | — | `issueTrackerURL="https://github.com/Nick-Doxa/ForgeBook/issues"` (place after `displayURL`) |

**Do NOT touch:** `modId`, `displayURL`, `logoFile`, `authors`, `description`, `dependencies.forgebook` blocks, `license`.

---

### `build.gradle` (MOD, one-line version bump)

**Analog:** Itself (L12).

**Change:**
```groovy
// Before (L12):
version = '0.1.0'
// After:
version = '1.0.0'
```

Nothing else changes. `jar` manifest (L74-84) uses `project.version` automatically, so `Implementation-Version` rolls over with it.

---

### `src/main/resources/assets/forgebook/textures/gui/logo.png` (CREATE)

**Analog:** `src/main/resources/logo.png` (existing 1×1 RGBA PNG, 67 bytes — per RESEARCH §Placeholder Logo Generation).

**Action:** Byte-identical copy. On Windows bash:
```bash
cp src/main/resources/logo.png src/main/resources/assets/forgebook/textures/gui/logo.png
```

No code reads this path yet — it's a forward-looking slot per RESEARCH §Logo Slot Mechanism. Forge does not auto-load `assets/<modid>/textures/gui/logo.png`; future `ChatPanelWidget` code may reference it via `new ResourceLocation("forgebook", "textures/gui/logo.png")`.

**No Java source changes required in Phase 5** for this file — purely filesystem creation.

---

### `README.md` (CREATE)

**Analog:** None in-tree — the repo has no README. Template comes entirely from RESEARCH §README & Docs Structure (05-RESEARCH.md L393-516).

**Key sections (verbatim from RESEARCH):**
1. One-line pitch + paragraph
2. Features bullet list (5 bullets, already drafted in RESEARCH)
3. Requirements (MC 1.20.1, Forge 47.4.18, Java 17, Anthropic API key, optional CurseForge key, both sides required)
4. Installation (Server + Client sub-sections; `chmod 600` step)
5. Configuration (two tables: server + client, lifted from CLAUDE.md §Tech Stack config tiers)
6. Security Posture (SSRF, log redaction, package firewall, rate limit — prose in RESEARCH)
7. Commands (table)
8. Compatibility → link to `docs/COMPATIBILITY.md`
9. Customizing the Logo (both slots, per RESEARCH §Placeholder Logo Generation)
10. Credits → link to `THIRD_PARTY_NOTICES.md`
11. License → link to `LICENSE`

**Cross-reference to CLAUDE.md for config field list** (server config table); the research already synthesised this at RESEARCH L440-461.

---

### `docs/COMPATIBILITY.md` (CREATE)

**Analog:** None in-tree. Template from RESEARCH §Compat Matrix Protocol (L548-591).

**Shape:** Table with 8 rows (one per compat target mod), columns `{mod, version, GUI scale 1, GUI scale 2, notes}`. Followed by "Testing Protocol" prose (9-step checklist per mod). Followed by "Re-run triggers" list.

**Human-checkpoint note:** 7 of 8 rows will have "[ ] pending" — Claude cannot visually verify overlap with compat mods loaded. Ideally fill the JEI row as a worked example.

---

### `docs/RELEASE-SMOKE.md` (CREATE)

**Analog:** None in-tree. Template from RESEARCH §Prod-Jar Smoke Protocol (L614-713).

**Shape:** 9-step protocol with PASS/FAIL criteria. Bash snippets for the automated parts (Step 1 jar-existence + `jar tf` grep). Steps 2-9 are human-only.

## Shared Patterns

### Component.translatable call shape

**Source:** `src/main/java/com/forgebook/client/ui/ChatPanelWidget.java` L215, L264; `ChatScreen.java` L70

**Apply to:** All 5 modified command/service files (ForgebookReloadCommand, AdminSubcommands, AskSubcommand, ItemSubcommand, RagItemPipeline, Authorizer, AiDispatcher).

**Excerpt — simple (no args):**
```java
Component heading = Component.translatable(ErrorCard.headingKey(ec.code()));
```

**Excerpt — with format arg (will be NEW in Phase 5 — first use in the codebase):**
```java
// Phase 5 introduces the parameterized call shape for retry-after:
Component feedback = Component.translatable(
    "forgebook.command.denied.rate_limited",
    l.retryAfterSeconds());   // %d positional arg
```

Standard Minecraft behaviour: `Component.translatable(key, args...)` with `%s` / `%d` / `%f` in the translation value.

---

### Package-private test seam (for i18n refactor regression safety)

**Source:** `src/main/java/com/forgebook/ai/RagItemPipeline.java` L295-313 (Feedback interface + FetchFn + AuthFn)

**Apply to:** Command files whose tests currently assert on prose (AskSubcommandTest, ItemSubcommandTest, AdminSubcommandsTest, RagItemPipelineTest).

**Excerpt — test-sink pattern (reused for key-based assertion):**
```java
// Production side:
private static void sendFailureKey(CommandSourceStack src, String key, Object... args) {
    if (src != null) {
        src.sendFailure(Component.translatable(key, args));
    }
    failureSinkForTests.accept(key);   // tests now receive the KEY, not prose
}

// Tests flip:
// OLD: assertEquals("Server is busy. Try again.", failureSink.last);
// NEW: assertEquals("forgebook.command.overloaded", failureSink.last);
```

Confirmed test-file patterns (`AskSubcommandTest.java` L214, L249) assert on prose — these will all need mechanical flip to key assertion.

---

### Sealed Result + record refactor (humanReadable → (humanReadable, feedback))

**Source:** `src/main/java/com/forgebook/safety/Authorizer.java` L47-54 + `src/main/java/com/forgebook/ai/AiDispatcher.java` L50-57

**Apply to:** Both Authorizer.Denied AND AiDispatcher.Error (mirror refactor).

**Current shape:**
```java
public sealed interface Result permits Allowed, Denied {}
public record Allowed() implements Result {}
public record Denied(ErrorCode code, String humanReadable) implements Result {}
```

**Phase 5 shape (Option A — keep wire String, add Component):**
```java
public record Denied(ErrorCode code, String humanReadable, Component feedback) implements Result {}
```

**Rationale for keeping humanReadable:**
- `ChatErrorPacket` wire format ships `String humanReadable` via `buf.writeUtf(..., 512)` — breaking this breaks the Phase 2 protocol version.
- `ChatPanelWidget` L215-222 renders errors from `ErrorCard.bodyKey(code)` (client-side translatable lookup) — the wire `humanReadable` is effectively an audit/fallback field on the client. Don't churn it.
- Server-side audit (`RequestAuditLogger.logDenied`) does NOT use `humanReadable` (logs code + UUID + latency) — confirmed via grep.

---

### i18n key namespace convention

**Source:** `src/main/resources/assets/forgebook/lang/en_us.json` (existing 21 keys)

**Apply to:** All Phase 5 additions.

**Pattern:**
```
forgebook.<area>.<subarea?>.<leaf>

Examples (existing):
  forgebook.chat.button.tooltip
  forgebook.chat.input.placeholder
  forgebook.error.transport.heading
  forgebook.error.rate_limited.body

Examples (Phase 5 new):
  forgebook.command.reload.success
  forgebook.command.disable.already
  forgebook.command.denied.rate_limited
  forgebook.command.provider.circuit_open
  forgebook.command.item.no_docs_url
```

Dot-separated, snake_case leaves, stable semantic area prefix.

---

### Version stamping (single source of truth)

**Source:** `build.gradle` L12 + `META-INF/mods.toml` L7 (`version="${file.jarVersion}"`)

**Apply to:** Only `build.gradle` needs the manual bump. `mods.toml` substitutes `${file.jarVersion}` from `build.gradle#version` automatically (ForgeGradle standard).

**Critical anti-pattern** (from CLAUDE.md stack notes): `gradle.properties` has stale MDK template placeholders (`mod_version=1.0.0`, `mod_id=examplemod`). These are **unread** by our `build.gradle`. Do NOT edit `gradle.properties` in Phase 5 — it's dead code. Leave it.

## No Analog Found

The three doc deliverables have no in-tree analog:

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `README.md` | docs | markdown | First README in repo (verified via `ls` — no file matches `README*` at root) |
| `docs/COMPATIBILITY.md` | docs | markdown | No `docs/` directory exists; no compat matrix precedent in codebase |
| `docs/RELEASE-SMOKE.md` | docs | markdown | No release-smoke doc exists; protocol is net-new |

Planner should use **RESEARCH.md directly** for these three files — templates are already fully fleshed out in RESEARCH §README & Docs Structure, §Compat Matrix Protocol, §Prod-Jar Smoke Protocol.

## Metadata

**Analog search scope:** `src/main/java/com/forgebook/**`, `src/main/resources/**`, repo root markdown files, `.planning/phases/04-ui-chat/` (Phase 4 precedent for i18n)
**Files scanned:** ~220 Java files in source tree (read 7 directly for excerpt extraction); 1 en_us.json; 1 mods.toml; 1 build.gradle; 1 LICENSE; 1 THIRD_PARTY_NOTICES.md
**Pattern extraction date:** 2026-04-16
**Key discoveries beyond RESEARCH audit:**
- Option A (split Denied into `humanReadable + feedback` fields) is safer than Option C (Component-only) because `ChatErrorPacket` wire format requires a String
- RagItemPipeline L178-180 and L197 carry 2 additional English literals missed in RESEARCH audit (no_docs_url, fetch_failed) — +2 keys
- AiDispatcher has 9 literal sites (1 in dispatch body + 7 in mapError + 1 in error at L165) — RESEARCH estimated 3-5, actual is 9; +4-6 keys vs estimate
- **Phase 5 total new key count: 26** (vs RESEARCH's 17 estimate; grand total after Phase 5 = 47 keys, not 38)
- The `executeStats` BiConsumer binding must stay on `Component.literal` because StatsAccumulator.render() emits structured tabular text, not prose — document as inline comment
- No existing `Component.translatable(key, args)` parameterized call site exists in the codebase; Phase 5's rate_limited/iteration_cap/no_docs_url/source_label/item-source are the **first four** uses of the args-variant
- `mods.toml` has an empty `credits=""` field (L11) that REL-03 requires to be populated — not called out in RESEARCH
