package com.forgebook.safety;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsAccumulatorTest {

    @BeforeEach
    void clearCounters() {
        StatsAccumulator.resetForTests();
    }

    @Test
    void recordSuccess_incrementsAllCountersForPlayer() {
        UUID alice = UUID.randomUUID();
        StatsAccumulator.recordSuccess(alice, 100, 200, 500L);
        String out = StatsAccumulator.render();
        assertTrue(out.contains("total requests : 1"), "render output: " + out);
        assertTrue(out.contains("total in_tok   : 100"));
        assertTrue(out.contains("total out_tok  : 200"));
        assertTrue(out.contains("mean latency_ms: 500"));
        assertTrue(out.contains(alice.toString()));
    }

    @Test
    void recordFailure_incrementsRequestsOnly_notTokens() {
        UUID alice = UUID.randomUUID();
        StatsAccumulator.recordFailure(alice, ErrorCode.PROVIDER);
        String out = StatsAccumulator.render();
        assertTrue(out.contains("total requests : 1"));
        assertTrue(out.contains("total in_tok   : 0"));
        assertTrue(out.contains("total out_tok  : 0"));
    }

    @Test
    void recordDenied_incrementsTotalDeniedOnly_notPerPlayerRequests() {
        UUID alice = UUID.randomUUID();
        StatsAccumulator.recordDenied(alice, ErrorCode.RATE_LIMITED);
        String out = StatsAccumulator.render();
        assertTrue(out.contains("total denied   : 1"));
        assertTrue(out.contains("total requests : 0"),
            "denied requests must not count as initiated; render=" + out);
    }

    @Test
    void render_capsPerPlayerSectionToTopTenByRequestCount() {
        for (int i = 0; i < 15; i++) {
            UUID u = new UUID(0L, (long) i);
            // Player i gets (i+1) requests — ensures deterministic ordering.
            for (int j = 0; j <= i; j++) {
                StatsAccumulator.recordSuccess(u, 10, 20, 30L);
            }
        }
        String out = StatsAccumulator.render();
        long lines = out.lines().filter(l -> l.trim().startsWith("0000")).count();
        assertTrue(lines <= 10, "render must cap per-player section at 10 rows; got " + lines);
        assertTrue(lines >= 10, "expected 10 rows when >=10 players present; got " + lines);
    }

    @Test
    void separateUuids_haveIndependentCounters() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        StatsAccumulator.recordSuccess(alice, 100, 100, 100L);
        StatsAccumulator.recordSuccess(bob, 50, 50, 50L);
        String out = StatsAccumulator.render();
        assertTrue(out.contains(alice.toString()));
        assertTrue(out.contains(bob.toString()));
        assertTrue(out.contains("total requests : 2"));
    }

    @Test
    void render_containsHeaderAndAllAggregateLabels() {
        String out = StatsAccumulator.render();
        assertTrue(out.startsWith("ForgeBook stats"), "render missing header: " + out);
        assertTrue(out.contains("total requests"));
        assertTrue(out.contains("total denied"));
        assertTrue(out.contains("total in_tok"));
        assertTrue(out.contains("total out_tok"));
        assertTrue(out.contains("per-player"));
    }
}
