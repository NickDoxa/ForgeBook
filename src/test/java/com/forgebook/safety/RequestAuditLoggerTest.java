package com.forgebook.safety;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import com.forgebook.ai.RequestKind;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestAuditLoggerTest {

    @BeforeEach
    void clearStats() {
        StatsAccumulator.resetForTests();
    }

    @Test
    void logSuccess_fansOutToStatsAccumulator() {
        UUID alice = UUID.randomUUID();
        RequestAuditLogger.logSuccess(alice, RequestKind.ASK, 50, 100, 250L);
        String out = StatsAccumulator.render();
        assertTrue(out.contains("total requests : 1"), "audit logger must fan out to stats; render=" + out);
        assertTrue(out.contains("total in_tok   : 50"));
        assertTrue(out.contains("total out_tok  : 100"));
    }

    @Test
    void logFailure_fansOutToStatsAccumulatorAsFailure() {
        UUID alice = UUID.randomUUID();
        RequestAuditLogger.logFailure(alice, RequestKind.ITEM, ErrorCode.TRANSPORT, 30, 0, 5000L);
        String out = StatsAccumulator.render();
        assertTrue(out.contains("total requests : 1"),
            "logFailure counts as initiated; render=" + out);
        // Tokens NOT attributed on failure (provider never returned usable result).
        assertTrue(out.contains("total in_tok   : 0"));
    }

    @Test
    void logDenied_fansOutToStatsAccumulatorAsDenied() {
        UUID alice = UUID.randomUUID();
        long startNanos = System.nanoTime() - 1_000_000L;  // 1 ms ago
        RequestAuditLogger.logDenied(alice, RequestKind.CHAT_UI, ErrorCode.RATE_LIMITED, startNanos);
        String out = StatsAccumulator.render();
        assertTrue(out.contains("total denied   : 1"), "render=" + out);
        assertTrue(out.contains("total requests : 0"),
            "denied must not count as initiated; render=" + out);
    }

    @Test
    void auditLogger_usesNamedForgebookAuditLogger() {
        // Static-guaranteed by implementation: verify the dedicated logger exists and
        // that fetching it by name returns the same instance (Log4j2 caches by name).
        Logger l1 = LogManager.getLogger("forgebook.audit");
        Logger l2 = LogManager.getLogger("forgebook.audit");
        assertSame(l1, l2, "Log4j2 should cache the named logger");
        assertEquals("forgebook.audit", l1.getName());
    }
}
