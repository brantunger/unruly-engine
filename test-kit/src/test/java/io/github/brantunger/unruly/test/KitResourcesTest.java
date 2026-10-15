package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KitResources closes what a check creates and stops its workers")
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

    @Test
    @DisplayName("closing() clears an interrupt that closing the resource puts back, as the engine does when a"
            + " session's close() fails with an InterruptedException in its cause, after a check that passes (#1120)")
    void closingClearsTheInterruptCloseSets() throws Exception {
        try {
            assertFalse(Thread.currentThread().isInterrupted(), "the thread was interrupted before the test");

            KitResources.closing((AutoCloseable) () -> Thread.currentThread().interrupt(), resource -> {
            });

            assertFalse(Thread.currentThread().isInterrupted(), "closing() left the thread interrupted");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("closing() clears an interrupt that closing the resource puts back after a check that fails, and"
            + " throws the check's failure (#1120)")
    void closingClearsTheInterruptCloseSetsAfterAFailedCheck() {
        try {
            assertFalse(Thread.currentThread().isInterrupted(), "the thread was interrupted before the test");
            IllegalStateException checkFailure = new IllegalStateException("the check failed");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> KitResources.closing((AutoCloseable) () -> Thread.currentThread().interrupt(), resource -> {
                        throw checkFailure;
                    }));

            assertSame(checkFailure, thrown);
            assertFalse(Thread.currentThread().isInterrupted(), "closing() left the thread interrupted");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("on a thread that is already interrupted, closing() leaves the thread interrupted, though closing the"
            + " resource interrupts it again (#1120)")
    void closingOnAnInterruptedThread() throws Exception {
        try {
            Thread.currentThread().interrupt();

            KitResources.closing((AutoCloseable) () -> Thread.currentThread().interrupt(), resource -> {
            });

            assertTrue(Thread.currentThread().isInterrupted(), "closing() cleared the thread's interrupt");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }
}
