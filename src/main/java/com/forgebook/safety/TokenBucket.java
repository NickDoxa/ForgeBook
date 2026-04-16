package com.forgebook.safety;

/**
 * Per-UUID token bucket (SAFE-02). Package-private — only RateLimiter
 * constructs and queries buckets; callers use RateLimiter.tryAcquire(uuid).
 *
 * Algorithm (RESEARCH §Pattern 4):
 *   1. On tryAcquire, compute elapsed time since lastRefillNanos.
 *   2. Add elapsed * refillPerSec tokens, clamped at capacity.
 *   3. If tokens >= 1.0, consume one token and return Allowed.
 *   4. Else compute seconds-to-next-token = (1 - tokens) / refillPerSec
 *      and return Limited(ceil(seconds), minimum 1L).
 *
 * Synchronized on `this`: per-bucket contention is trivially low (one
 * player at most a few requests/sec). CAS would add complexity without
 * measurable gain.
 *
 * Counts INITIATED requests (per SAFE-02 spec — "not just successful").
 * A request that later fails with TRANSPORT still consumed a token.
 */
final class TokenBucket {

    private double tokens;
    private long lastRefillNanos;

    TokenBucket(int initialTokens) {
        this.tokens = initialTokens;
        this.lastRefillNanos = System.nanoTime();
    }

    synchronized RateLimiter.Outcome tryAcquire(int capacity, double refillPerSec) {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
        lastRefillNanos = now;

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return new RateLimiter.Allowed();
        }
        // Compute seconds until one more token is refilled, clamped to >= 1s
        // (SAFE-03: always tell the caller a useful retry-after number).
        double secondsToOne = (1.0 - tokens) / refillPerSec;
        return new RateLimiter.Limited(Math.max(1L, (long) Math.ceil(secondsToOne)));
    }
}
