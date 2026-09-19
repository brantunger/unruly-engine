package io.github.brantunger.unruly.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An engine's permits for compiled copies of its rules: one for each copy a limited run holds. Every rule list the
 * engine loads shares them, so runs that still use a list a reload replaced count against the same limit as runs on
 * the new one, and the limit holds across reloads.
 *
 * <p>
 * The engine's build slots are here too: a run on a virtual thread, which no limit covers, waits for one before it
 * makes a new copy, and holds it while that copy runs for the first time. A copy is cheap to make but slow the first
 * time it runs, because a language such as MVEL compiles each expression then; on JDK 24 and later the virtual threads
 * waiting on the classes that compiling loads give up their carriers, so without slots every virtual thread would find
 * every copy in use and make its own. A run that gives up waiting makes its copy without a slot.
 * </p>
 */
final class CopyPermits {

    private final Semaphore semaphore;
    // How many permits have been given back, so a run that is waiting can tell an engine that is busy from one whose
    // copies are never coming back.
    private final AtomicLong givenBack = new AtomicLong();
    // Fair, so a run that is waiting isn't overtaken for ever by runs arriving later.
    private final Semaphore slots;
    // How many slots have been given back, for the same reason as givenBack.
    private final AtomicLong slotsGivenBack = new AtomicLong();

    /**
     * Creates the permits for a limit, and one build slot for each processor, counted once, here.
     *
     * @param limit How many copies limited runs may hold at once, or {@link RuleSet#UNLIMITED}
     */
    CopyPermits(int limit) {
        this(limit, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates the permits for a limit, with the given number of build slots.
     *
     * @param limit How many copies limited runs may hold at once, or {@link RuleSet#UNLIMITED}
     * @param slots How many runs may hold a build slot at once, each while its new copy runs for the first time
     */
    CopyPermits(int limit, int slots) {
        this.semaphore = new Semaphore(limit);
        this.slots = new Semaphore(slots, true);
    }

    /**
     * Returns the permits to take and release.
     *
     * @return The semaphore
     */
    Semaphore available() {
        return semaphore;
    }

    /**
     * Returns how many permits have been given back so far.
     *
     * @return The count
     */
    long returned() {
        return givenBack.get();
    }

    /**
     * Gives a permit back, counted before it's released, so a run whose wait ends between the two still sees that one
     * came back.
     */
    void giveBack() {
        givenBack.incrementAndGet();
        semaphore.release();
    }

    /**
     * Waits for a build slot, for as long as slots keep coming back. A run gives up, and makes its copy without a slot,
     * when a whole {@code window} passes without one single slot coming back, which is what a run waiting for another
     * thread's run of this engine looks like, or when half the time it had left when it started waiting has passed, so
     * that its rules keep the other half. A thread whose interrupt status is already set doesn't wait: its run stops at
     * its first rule.
     *
     * @param window   How long to wait for progress, in milliseconds
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return {@code true} if a slot was taken, to give back with {@link #giveBackSlot()}; {@code false} if the run is
     *         to make its copy without one
     * @throws InterruptedException if the thread is interrupted while it waits
     */
    boolean awaitSlot(long window, Instant deadline) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        long start = System.nanoTime();
        Duration left = Cancellation.timeLeft(deadline);
        // Converted without overflowing: half of a deadline too far away to count in nanoseconds, such as the
        // Instant.MAX a huge timeout gives, is Long.MAX_VALUE, as patient as a run without one.
        long patience = left == null ? Long.MAX_VALUE : Math.max(0, TimeUnit.NANOSECONDS.convert(left.dividedBy(2)));
        long windowNanos = TimeUnit.MILLISECONDS.toNanos(window);
        long seen = slotsGivenBack.get();
        while (true) {
            long waited = System.nanoTime() - start;
            long wait = Math.min(windowNanos, patience - waited);
            if (slots.tryAcquire(Math.max(0, wait), TimeUnit.NANOSECONDS)) {
                return true;
            }
            if (wait < windowNanos) {
                return false;
            }
            long now = slotsGivenBack.get();
            if (now == seen) {
                return false;
            }
            seen = now;
        }
    }

    /**
     * Gives a build slot back, counted before it's released, so a run whose wait ends between the two still sees that
     * one came back.
     */
    void giveBackSlot() {
        slotsGivenBack.incrementAndGet();
        slots.release();
    }
}
