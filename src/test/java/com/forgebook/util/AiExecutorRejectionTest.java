package com.forgebook.util;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * D-21: verify that submitting more tasks than capacity (4 running + 64 queued)
 * triggers RejectedExecutionException, which Plan 03's ChatRequestHandler
 * translates to ChatErrorPacket(OVERLOADED).
 */
class AiExecutorRejectionTest {

    @AfterEach void teardown() throws Exception {
        // Force shutdown via reflection on the static INSTANCE field to isolate tests.
        Field f = AiExecutor.class.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) f.get(null);
        if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
            f.set(null, null);
        }
    }

    @Test void get_beforeStart_throws() {
        assertThrows(IllegalStateException.class, AiExecutor::get);
    }

    @Test void start_isIdempotent() throws Exception {
        AiExecutor.start();
        ExecutorService first = AiExecutor.get();
        AiExecutor.start(); // second call is no-op
        ExecutorService second = AiExecutor.get();
        assertSame(first, second);
    }

    @Test void threadNamePattern_isForgebookAiN() throws Exception {
        AiExecutor.start();
        CountDownLatch gotName = new CountDownLatch(1);
        String[] name = new String[1];
        AiExecutor.get().submit(() -> {
            name[0] = Thread.currentThread().getName();
            gotName.countDown();
        });
        assertTrue(gotName.await(2, TimeUnit.SECONDS));
        assertTrue(name[0].matches("forgebook-ai-\\d+"), "unexpected: " + name[0]);
    }

    @Test void rejection_onQueueOverflow_throwsRejectedExecutionException() throws Exception {
        AiExecutor.start();
        ExecutorService pool = AiExecutor.get();

        // Block all 4 workers on a latch that we never release until test end.
        // Swallow IE without re-interrupting — re-interrupting causes the next queued
        // task's await() to throw immediately, cascading a drain across the queue.
        CountDownLatch block = new CountDownLatch(1);
        // workersReady lets us confirm each of the 4 worker threads is actively
        // running its task (and therefore holding a worker slot rather than
        // sitting in the queue) before we start the 64-slot queue fill. Without
        // this barrier, under CI CPU pressure the submission loop can outrun
        // worker-thread startup, and the first few "worker tasks" can land in
        // the queue instead — making the queue fill faster than 64 submits and
        // triggering REE on line 70 instead of line 75. See CI run 24550535593.
        CountDownLatch workersReady = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                workersReady.countDown();
                try { block.await(); } catch (InterruptedException ignored) { }
            });
        }
        assertTrue(workersReady.await(5, TimeUnit.SECONDS),
            "4 worker threads did not pick up their tasks within 5s");

        // All 4 workers are now confirmed blocked on `block`. Queue is empty.
        // Fill the 64-slot queue.
        for (int i = 0; i < 64; i++) {
            pool.submit(() -> { try { block.await(); } catch (InterruptedException ignored) { } });
        }
        // 69th submission → AbortPolicy throws.
        RejectedExecutionException ree = assertThrows(
            RejectedExecutionException.class,
            () -> pool.submit(() -> {}));
        assertNotNull(ree);

        block.countDown();  // release all blocked workers so shutdown is clean
    }

    @Test void shutdown_viaOnServerStopping_drainsAndNullsInstance() throws Exception {
        AiExecutor.start();
        ExecutorService before = AiExecutor.get();
        assertNotNull(before);

        // Invoke onServerStopping with a null event — method never dereferences the event param.
        // (It's accepted as a parameter to match the Forge event listener signature.)
        Method m = AiExecutor.class.getMethod("onServerStopping",
            net.minecraftforge.event.server.ServerStoppingEvent.class);
        m.invoke(null, new Object[]{null});

        assertThrows(IllegalStateException.class, AiExecutor::get);
    }
}
