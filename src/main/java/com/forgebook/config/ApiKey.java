package com.forgebook.config;

/**
 * Value wrapper for API-key-shaped strings. D-13: toString() returns "<redacted>";
 * the raw value is reachable only via .raw(). The CI grep-lint in Plan 05 restricts
 * .raw() callers to com.forgebook.ai and com.forgebook.integration (both empty in
 * Phase 1 — any .raw() call in Phase 1 will fail CI by design).
 *
 * Defensive: tolerates null by coercing to empty string.
 *
 * Implemented as a final class rather than a record because records auto-generate
 * toString that includes component values — we cannot safely override that without
 * subtle failure modes (e.g., deconstruction patterns in future Java versions can
 * bypass the override). A final class with an explicit private field and explicit
 * accessor is the bulletproof shape for a value type whose entire reason-for-existing
 * is that toString must NEVER leak.
 */
public final class ApiKey {
    private final String raw;

    public ApiKey(String raw) {
        this.raw = raw == null ? "" : raw;
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "<redacted>";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApiKey other && raw.equals(other.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }
}
