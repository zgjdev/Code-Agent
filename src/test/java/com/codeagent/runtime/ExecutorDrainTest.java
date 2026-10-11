package com.codeagent.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorDrainTest {
    @Test void waitsForPhysicalExitAndRestoresInterrupt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var drained = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicBoolean restored = new AtomicBoolean();
        pool.submit(() -> {
            entered.countDown();
            while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt(); ExecutorDrain.stopAndAwait(pool);
            restored.set(Thread.currentThread().isInterrupted()); drained.countDown();
        });
        waiter.start();
        try { assertFalse(drained.await(100, TimeUnit.MILLISECONDS)); }
        finally { release.countDown(); assertTrue(drained.await(2, TimeUnit.SECONDS)); waiter.join(2000); }
        assertTrue(pool.isTerminated()); assertTrue(restored.get());
    }
}
