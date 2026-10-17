package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    @DisplayName("onItsOwnThread() on a thread that is interrupted while it waits for the run throws the"
            + " InterruptedException and leaves the thread interrupted")
    void onItsOwnThreadInterruptedWhileItWaits() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            Thread.currentThread().interrupt();

            assertThrows(InterruptedException.class, () -> KitResources.onItsOwnThread(() -> {
                release.await();
                return null;
            }, Duration.ofSeconds(30)));

            assertTrue(Thread.currentThread().isInterrupted(), "onItsOwnThread() cleared the thread's interrupt");
        } finally {
            // Cleared for the tests that run on this thread next, and the run released in case it still waits.
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    @DisplayName("onItsOwnThread() fails the check when the run is still going at the bound, naming the bound, and"
            + " stops the run's thread")
    void onItsOwnThreadFailsAtTheBound() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        try {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> KitResources.onItsOwnThread(() -> {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            // Stopping the run's thread interrupts the run.
                            stopped.countDown();
                            throw e;
                        }
                        return null;
                    }, Duration.ofMillis(50)));

            assertEquals("the run didn't return within 50 ms", failure.getMessage());
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertFalse(Thread.currentThread().isInterrupted(), "onItsOwnThread() left the thread interrupted");
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "onItsOwnThread() didn't stop the run's thread");
        } finally {
            release.countDown();
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

    @Test
    @DisplayName("keepingInterrupt() clears an interrupt the step puts back, as the engine does when a reload or a"
            + " close() inside a check closes a session whose close() fails with an InterruptedException in its cause,"
            + " whether the step ends or fails, and throws the step's failure (#1223)")
    void keepingInterruptClearsTheInterruptTheStepSets() {
        try {
            assertFalse(Thread.currentThread().isInterrupted(), "the thread was interrupted before the test");
            IllegalStateException stepFailure = new IllegalStateException("the step failed");

            KitResources.keepingInterrupt(() -> Thread.currentThread().interrupt());
            boolean afterEnded = Thread.currentThread().isInterrupted();
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> KitResources.keepingInterrupt(() -> {
                        Thread.currentThread().interrupt();
                        throw stepFailure;
                    }));

            assertFalse(afterEnded, "keepingInterrupt() left the thread interrupted after a step that ended");
            assertSame(stepFailure, thrown);
            assertFalse(Thread.currentThread().isInterrupted(),
                    "keepingInterrupt() left the thread interrupted after a step that failed");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("on a thread that is already interrupted, keepingInterrupt() leaves the thread interrupted, though the"
            + " step interrupts it again (#1223)")
    void keepingInterruptOnAnInterruptedThread() {
        try {
            Thread.currentThread().interrupt();

            KitResources.keepingInterrupt(() -> Thread.currentThread().interrupt());

            assertTrue(Thread.currentThread().isInterrupted(), "keepingInterrupt() cleared the thread's interrupt");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("runKeepingInterrupt() clears an interrupt a run that ends puts back, as the engine does when the run"
            + " closes a nested run's session whose close() fails with an InterruptedException in its cause, and one"
            + " a run that fails for another reason puts back (#1223)")
    void runKeepingInterruptClearsTheInterruptAnEndedRunSets() {
        try {
            assertFalse(Thread.currentThread().isInterrupted(), "the thread was interrupted before the test");
            IllegalStateException runFailure = new IllegalStateException("rule 'r' failed");

            KitResources.runKeepingInterrupt(() -> Thread.currentThread().interrupt());
            boolean afterEnded = Thread.currentThread().isInterrupted();
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> KitResources.runKeepingInterrupt(() -> {
                        Thread.currentThread().interrupt();
                        throw runFailure;
                    }));

            assertFalse(afterEnded, "runKeepingInterrupt() left the thread interrupted after a run that ended");
            assertSame(runFailure, thrown);
            assertFalse(Thread.currentThread().isInterrupted(),
                    "runKeepingInterrupt() left the thread interrupted after a run that failed");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("runKeepingInterrupt() keeps the interrupt of a run that was stopped by it, which fails with an"
            + " InterruptedException as the cause of its failure, as the engine's run() reports a stop, on a thread"
            + " that wasn't interrupted before and on one that was (#1223, #534)")
    void runKeepingInterruptKeepsTheInterruptOfAStoppedRun() {
        try {
            assertFalse(Thread.currentThread().isInterrupted(), "the thread was interrupted before the test");
            // The engine's stop: its own exception, caused by an InterruptedException, with the interrupt set again.
            IllegalStateException stop = new IllegalStateException("run() was interrupted during rule 'r'",
                    new InterruptedException());
            KitResources.ClosingStep<RuntimeException> stoppedRun = () -> {
                Thread.currentThread().interrupt();
                throw stop;
            };

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> KitResources.runKeepingInterrupt(stoppedRun));
            boolean keptWhenClearBefore = Thread.interrupted();
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> KitResources.runKeepingInterrupt(stoppedRun));

            assertSame(stop, thrown);
            assertTrue(keptWhenClearBefore, "runKeepingInterrupt() cleared the interrupt that stopped the run");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "runKeepingInterrupt() cleared the interrupt of a thread that was interrupted before the run");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("runKeepingInterrupt() keeps the interrupt of a run that was stopped by it when ending the run threw"
            + " too, so the run fails with what the ending threw and the stop suppressed on it, as the engine's run()"
            + " does (#1223)")
    void runKeepingInterruptKeepsTheInterruptOfAStoppedRunWhoseEndingFailed() {
        try {
            assertFalse(Thread.currentThread().isInterrupted(), "the thread was interrupted before the test");
            IllegalStateException ending = new IllegalStateException("ending the run failed");
            ending.addSuppressed(new IllegalStateException("run() was interrupted during rule 'r'",
                    new InterruptedException()));

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> KitResources.runKeepingInterrupt(() -> {
                        Thread.currentThread().interrupt();
                        throw ending;
                    }));

            assertSame(ending, thrown);
            assertTrue(Thread.currentThread().isInterrupted(),
                    "runKeepingInterrupt() cleared the interrupt that stopped the run");
        } finally {
            // Cleared for the tests that run on this thread next.
            Thread.interrupted();
        }
    }
}
