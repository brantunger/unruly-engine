package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The build slots a run on a virtual thread without a limit waits for before it makes a copy (#424): how long it
 * waits, and that a run waiting isn't overtaken by runs that arrive after it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("CopyPermits' build slots")
class CopyPermitsTest {

    /** A thread waiting for a slot, and what its wait returned or threw. */
    private record Waiter(Thread thread, AtomicReference<Object> outcome) {

        Object result() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(20));
            assertFalse(thread.isAlive(), "the wait never ended");
            return outcome.get();
        }
    }

    private static Waiter waitFor(CopyPermits permits, long window, Deadline deadline) {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().daemon().start(() -> {
            try {
                outcome.set(permits.awaitSlot(window, deadline));
            } catch (InterruptedException e) {
                outcome.set(e);
            }
        });
        return new Waiter(thread, outcome);
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < deadline, "the thread never started waiting");
            Thread.sleep(2);
        }
    }

    @Test
    @DisplayName("a free slot is taken at once")
    void freeSlot() throws InterruptedException {
        assertTrue(new CopyPermits(0, 1).awaitSlot(10_000, Deadline.NONE));
    }

    @Test
    @DisplayName("a run gives up when a whole window passes with no slot given back")
    void givesUpAfterAWindowWithoutProgress() throws InterruptedException {
        CopyPermits permits = new CopyPermits(0, 1);
        assertTrue(permits.awaitSlot(0, Deadline.NONE));
        long start = System.nanoTime();

        assertFalse(permits.awaitSlot(100, Deadline.NONE));
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100));
    }

    @Test
    @DisplayName("a run keeps waiting while slots come back to runs ahead of it, and takes the next one")
    void keepsWaitingWhileSlotsComeBack() throws InterruptedException {
        // The count is read when the wait starts and again when each window ends. By the end of the first window it has
        // moved, though no slot was free: one came back and a run ahead took it. So the run waits another window, and
        // the next slot, given back as that window starts, is its own. The second read is the end of the first window
        // however long that took, so no timing decides which window the slot comes back in.
        Semaphore slots = new Semaphore(0);
        AtomicLong reads = new AtomicLong();
        LongSupplier returned = () -> {
            long read = reads.incrementAndGet();
            if (read == 2) {
                slots.release();
            }
            return Math.min(1, read - 1);
        };

        assertTrue(CopyPermits.awaitSlot(slots, returned, 100, Deadline.NONE), "the run gave up after one window");

        assertEquals(2, reads.get(), "the run didn't take the slot in its second window");
    }

    @Test
    @DisplayName("a run that waits first gets the slot first, however many runs arrive after it")
    void waitingRunsAreNotOvertaken() throws InterruptedException {
        CopyPermits permits = new CopyPermits(0, 1);
        assertTrue(permits.awaitSlot(0, Deadline.NONE));
        Waiter first = waitFor(permits, 10_000, Deadline.NONE);
        awaitParked(first.thread());

        permits.giveBackSlot();
        // A run arriving now finds the slot promised to the one waiting, so it doesn't get it.
        assertFalse(permits.awaitSlot(0, Deadline.NONE), "a run arriving later took the slot");

        assertEquals(true, first.result());
    }

    @Test
    @DisplayName("with a deadline, a run waits at most half the time it has left")
    void waitsHalfTheTimeLeft() throws InterruptedException {
        // The slots note how long the run asks to wait and give it nothing, so the test reads the wait the run asked
        // for rather than timing when its thread woke. Half of a minute left is at most 30 seconds; the run has about
        // ten seconds to start waiting before it asks for less than 25. The window is far longer than either.
        AtomicLong asked = new AtomicLong(-1);
        Semaphore slots = new Semaphore(0) {
            @Override
            public boolean tryAcquire(long timeout, TimeUnit unit) {
                asked.set(unit.toMillis(timeout));
                return false;
            }
        };

        assertFalse(CopyPermits.awaitSlot(slots, () -> 0, 600_000, Deadline.from(Duration.ofSeconds(60))));
        long waited = asked.get();
        assertTrue(waited > 25_000 && waited <= 30_000, "asked to wait " + waited + " ms, not about 30,000");
    }

    @Test
    @DisplayName("a run gives up when its half of the time left is spent, however many slots come back meanwhile")
    void givesUpWhenItsPatienceIsSpentWhileSlotsChurn() throws InterruptedException {
        // No slot is ever free, and slots are given back all the while, so only the run's own patience can end this
        // wait: the count stops moving after three reads, so a run that rode the churn instead would end too, and
        // fail this test rather than hold it. The window is far longer than the patience, so the wait ends when the
        // half of the time left is spent, after about 200 ms.
        AtomicLong reads = new AtomicLong();
        LongSupplier returned = () -> Math.min(3, reads.incrementAndGet());

        assertFalse(CopyPermits.awaitSlot(new Semaphore(0), returned, 10_000, Deadline.from(Duration.ofMillis(400))));

        assertEquals(1, reads.get(), "the run read the count again instead of giving up when its patience ran out");
    }

    @Test
    @DisplayName("a slot given back inside the half of the time left is taken")
    void slotInsideTheDeadline() throws InterruptedException {
        CopyPermits permits = new CopyPermits(0, 1);
        assertTrue(permits.awaitSlot(0, Deadline.NONE));
        Waiter waiter = waitFor(permits, 10_000, Deadline.from(Duration.ofSeconds(10)));
        awaitParked(waiter.thread());

        permits.giveBackSlot();

        assertEquals(true, waiter.result());
    }

    @Test
    @DisplayName("a deadline too far away to count in nanoseconds waits as a run without one does")
    void farDeadline() throws InterruptedException {
        // The deadline a timeout long enough to mean "no real limit" gives.
        Deadline far = Deadline.from(Duration.ofSeconds(Long.MAX_VALUE));
        CopyPermits permits = new CopyPermits(0, 1);
        assertTrue(permits.awaitSlot(10_000, far), "the free slot wasn't taken");
        Waiter waiter = waitFor(permits, 10_000, far);
        awaitParked(waiter.thread());

        permits.giveBackSlot();

        assertEquals(true, waiter.result());
    }

    @Test
    @DisplayName("a run whose deadline has passed doesn't wait")
    void deadlinePassed() throws InterruptedException {
        CopyPermits permits = new CopyPermits(0, 1);
        assertTrue(permits.awaitSlot(0, Deadline.NONE));

        assertFalse(permits.awaitSlot(10_000, Deadline.at(Instant.now().minusSeconds(1))));
    }

    @Test
    @DisplayName("a thread already interrupted doesn't wait, and stays interrupted")
    void alreadyInterrupted() throws InterruptedException {
        CopyPermits permits = new CopyPermits(0, 1);
        Thread.currentThread().interrupt();
        try {
            assertFalse(permits.awaitSlot(10_000, Deadline.NONE), "took a slot, though the run is about to stop");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a run interrupted while it waits throws")
    void interruptedWhileWaiting() throws InterruptedException {
        CopyPermits permits = new CopyPermits(0, 1);
        assertTrue(permits.awaitSlot(0, Deadline.NONE));
        Waiter waiter = waitFor(permits, 10_000, Deadline.NONE);
        awaitParked(waiter.thread());

        waiter.thread().interrupt();

        assertInstanceOf(InterruptedException.class, waiter.result());
    }

    @Test
    @DisplayName("an engine has one build slot for each processor")
    void oneSlotForEachProcessor() throws InterruptedException {
        CopyPermits permits = new CopyPermits(RuleSet.UNLIMITED);
        for (int slot = 0; slot < Runtime.getRuntime().availableProcessors(); slot++) {
            assertTrue(permits.awaitSlot(0, Deadline.NONE), "slot " + slot);
        }

        assertFalse(permits.awaitSlot(0, Deadline.NONE), "more slots than processors");
    }
}
