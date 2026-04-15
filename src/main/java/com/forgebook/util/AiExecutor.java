package com.forgebook.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * D-20: bounded off-tick executor for all HTTP / provider / scraping work.
 *
 * Fixed 4 core + 4 max threads, bounded ArrayBlockingQueue(64), AbortPolicy
 * (throws RejectedExecutionException on queue overflow — callers translate to
 * ChatErrorPacket(OVERLOADED) per D-20).
 *
 * daemon=false (D-20, Pitfall 7): non-daemon threads block JVM shutdown,
 * giving in-flight HTTP a bounded window to drain. Paired with explicit
 * awaitTermination(5s) on ServerStoppingEvent.
 */
public final class AiExecutor {

    private static final Logger LOG = LogManager.getLogger();
    private static volatile ThreadPoolExecutor INSTANCE;

    private AiExecutor() {}

    public static ExecutorService get() {
        ThreadPoolExecutor e = INSTANCE;
        if (e == null) {
            throw new IllegalStateException(
                "aiExecutor not started — ServerStartingEvent hasn't fired?");
        }
        return e;
    }

    /** Starts the executor. Idempotent — second call is a no-op. */
    public static synchronized void start() {
        if (INSTANCE != null) return;

        ThreadFactory tf = new ThreadFactory() {
            private int i = 0;
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "forgebook-ai-" + (++i));
                t.setDaemon(false);  // D-20
                return t;
            }
        };

        INSTANCE = new ThreadPoolExecutor(
            4, 4,                                   // fixed 4 — D-20
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),           // bounded — D-20
            tf,
            new ThreadPoolExecutor.AbortPolicy()    // throws RejectedExecutionException
        );
        LOG.info("aiExecutor started (4 threads, queue capacity 64)");
    }

    public static void onServerStopping(ServerStoppingEvent e) {
        ThreadPoolExecutor pool = INSTANCE;
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("aiExecutor did not drain in 5s; forcing shutdownNow()");
                pool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        } finally {
            INSTANCE = null;
            LOG.info("aiExecutor stopped");
        }
    }
}
