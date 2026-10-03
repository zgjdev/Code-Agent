package com.codeagent.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CancellationContextTest {

    @Test
    void childThreadKeepsRunTokenAfterParentClearsGlobalContext() throws Exception {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch parentCleared = new CountDownLatch(1);
        try {
            Future<Boolean> result = executor.submit(() -> {
                parentCleared.await();
                return CancellationContext.current() == token;
            });

            CancellationContext.clear(token);
            parentCleared.countDown();

            assertTrue(result.get());
        } finally {
            token.cancel();
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }

    @Test
    void existingWorkerSeesOnlyExplicitlyInstalledExecutionToken() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertFalse(executor.submit(CancellationContext::isCancelled).get());
            CancellationToken token = new CancellationToken();
            token.cancel();
            assertFalse(executor.submit(CancellationContext::isCancelled).get());
            assertTrue(executor.submit(() -> {
                try (CancellationContext.Scope ignored = CancellationContext.install(token)) {
                    return CancellationContext.isCancelled();
                }
            }).get());
            assertFalse(executor.submit(CancellationContext::isCancelled).get());
        } finally {
            executor.shutdownNow();
        }
    }
}
