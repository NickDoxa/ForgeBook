package com.forgebook.ai;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry policy (AI-06). Pure compute — no state. All constants exposed on DEFAULT.
 * Retry set from RESEARCH §1.5 + §7.3: 429, 500, 502, 503, 504, 529, IOException.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, double jitter) {

    // One retry is enough for transient 5xx / timeouts — the second try succeeds
    // or it doesn't, there's no systemic benefit to three attempts from a user's
    // perspective. Lowering from 3 → 1 caps worst-case latency of a failing call
    // from ~4 minutes (3 × 60s timeout + backoff) to ~65s (1 × 30s + backoff).
    // maxDelay raised 5s → 10s (1.0.4) so short retry-after hints (e.g. 7-8s
    // after a brief 429 burst) are respected verbatim instead of being clamped
    // down to 5s and immediately retried into another 429. ClaudeProvider
    // separately fast-fails 429s whose retry-after exceeds 10s — the
    // interactive budget — so this cap is the true upper bound.
    public static final RetryPolicy DEFAULT = new RetryPolicy(
        /* maxAttempts */ 1,
        /* baseDelay   */ Duration.ofSeconds(1),
        /* maxDelay    */ Duration.ofSeconds(10),
        /* jitter      */ 0.25
    );

    /** True iff the status/IO condition is retryable per AI-06 and RESEARCH §1.5. */
    public static boolean shouldRetry(int status, boolean ioException) {
        if (ioException) return true;
        return status == 429 || status == 500 || status == 502
            || status == 503 || status == 504 || status == 529;
    }

    /**
     * Delay before retry attempt `attempt` (0-indexed). When retryAfter is present
     * (e.g. from the 429 `retry-after` header), returns min(retryAfter, maxDelay)
     * directly. Otherwise: baseDelay * 2^attempt, capped at maxDelay, with +/-jitter.
     */
    public Duration delay(int attempt, Optional<Duration> retryAfter) {
        if (retryAfter.isPresent()) {
            long clamped = Math.min(retryAfter.get().toMillis(), maxDelay.toMillis());
            return Duration.ofMillis(clamped);
        }
        long base = baseDelay.toMillis() * (1L << attempt);
        long capped = Math.min(base, maxDelay.toMillis());
        double factor = 1.0 + jitter * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        long withJitter = (long) (capped * factor);
        return Duration.ofMillis(Math.max(0, withJitter));
    }
}
