package com.forgebook.tool;

/**
 * Structured tool-execution failure (D-12).
 *
 * Reason values:
 *   UNKNOWN_TOOL     — model asked for a tool not in ToolRegistry.
 *   INVALID_INPUT    — input JSON fails schema validation (e.g. required field missing).
 *   NO_DOCS_URL      — FetchModDocsPageTool invoked with empty URL (TOOL-07 fallback trigger).
 *   FETCH_FAILED     — SafeHttpFetcher rejected URL (wraps UnsafeUrlException).
 *   UPSTREAM_TIMEOUT — IOException / network timeout talking to upstream.
 *
 * AgentLoop catches this and emits tool_result(is_error=true, content={"error":"<REASON>","detail":"..."}).
 */
public final class ToolException extends Exception {
    public enum Reason { UNKNOWN_TOOL, INVALID_INPUT, NO_DOCS_URL, FETCH_FAILED, UPSTREAM_TIMEOUT }

    private final Reason reason;

    public ToolException(Reason reason, String detail) {
        super("Tool failed (" + reason.name() + "): " + detail);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
