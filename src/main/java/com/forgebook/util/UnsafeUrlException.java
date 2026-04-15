package com.forgebook.util;

/**
 * Thrown by {@link SafeHttpFetcher} whenever a URL fails a security gate.
 *
 * The {@link Reason} enum is part of the public API: unit tests assert on it
 * (one test per value, per CONTEXT.md D-24) and callers may dispatch on it to
 * surface user-facing error codes via ChatErrorPacket.
 */
public final class UnsafeUrlException extends Exception {
    public enum Reason {
        /** URL scheme is not https. */
        SCHEME,
        /** Host resolved (directly or via redirect) to a CIDR-blocked address. */
        PRIVATE_IP,
        /** Redirect chain exceeded SafeHttpFetcher.MAX_REDIRECTS (3). */
        REDIRECT_LIMIT,
        /** Response body exceeded SafeHttpFetcher.SIZE_CAP (1 MiB) during streaming read. */
        SIZE_CAP,
        /** Response Content-Type was not in the allowlist. */
        CONTENT_TYPE,
        /** Connect or read exceeded SafeHttpFetcher.TIMEOUT_MS (15 s). */
        TIMEOUT
    }

    private final Reason reason;

    public UnsafeUrlException(Reason reason) {
        super("Unsafe URL: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
