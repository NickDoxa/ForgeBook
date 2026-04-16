package com.forgebook.ai;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple circuit breaker (AI-07). Trips after FAILURE_THRESHOLD consecutive failures,
 * stays tripped for COOL_OFF. Any success resets. Callers: ClaudeProvider wraps
 * every HTTP attempt with isOpen() -> recordSuccess/recordFailure.
 *
 * Test seam: constructor takes an injectable LongSupplier clock (defaults to
 * System::currentTimeMillis) so cool-off tests can advance time deterministically
 * without Thread.sleep.
 */
public final class CircuitBreaker {
    private static final Logger LOG = LogManager.getLogger();

    public static final int FAILURE_THRESHOLD = 5;
    public static final Duration COOL_OFF = Duration.ofMinutes(5);

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong trippedUntil = new AtomicLong(0L);
    private final LongSupplier clock;

    public CircuitBreaker() { this(System::currentTimeMillis); }

    /** Package-private test seam — production code MUST use the no-arg ctor. */
    CircuitBreaker(LongSupplier clock) { this.clock = clock; }

    public boolean isOpen() { return clock.getAsLong() < trippedUntil.get(); }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        trippedUntil.set(0L);
    }

    public void recordFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= FAILURE_THRESHOLD) {
            long until = clock.getAsLong() + COOL_OFF.toMillis();
            trippedUntil.set(until);
            LOG.warn("Circuit breaker tripped after {} consecutive failures; cooling for {} min",
                     n, COOL_OFF.toMinutes());
        }
    }

    /** For tests only: current consecutive-failure counter. */
    public int consecutiveFailures() { return consecutiveFailures.get(); }
}
