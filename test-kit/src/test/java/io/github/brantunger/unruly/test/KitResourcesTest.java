package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KitResources stops a check's workers")
class KitResourcesTest {

    @Test
    @DisplayName("on a thread that is already interrupted, stop() skips the wait, shuts the workers down and leaves"
            + " the thread interrupted (#803)")
    void stopOnAnInterruptedThread() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        // A worker that outlives shutdownNow()'s interrupt, so the workers have not terminated when stop() waits for
        // them, and awaitTermination() sees the interrupt on every run. A worker that ends on the interrupt could let
        // the workers terminate first, and awaitTermination() would return without looking at it.
        workers.execute(() -> {
            started.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "the worker didn't start");

            Thread.currentThread().interrupt();
            long start = System.nanoTime();
            KitResources.stop(workers);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertTrue(Thread.currentThread().isInterrupted(), "stop() cleared the thread's interrupt");
            assertTrue(took.compareTo(Duration.ofSeconds(2)) < 0,
                    "stop() waited " + took + " on an interrupted thread");
            assertTrue(workers.isShutdown(), "stop() didn't shut the workers down");
            assertFalse(workers.isTerminated(), "the worker ended before stop() waited, so the wait wasn't skipped");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
            // Released and shut down however the test ends, so no worker outlives it. Not asserted: a failure here
            // would hide the test's own.
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
