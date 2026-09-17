package io.github.brantunger.unruly.core;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An engine's permits for compiled copies of its rules: one for each copy a limited run holds. Every rule list the
 * engine loads shares them, so runs that still use a list a reload replaced count against the same limit as runs on
 * the new one, and the limit holds across reloads.
 */
final class CopyPermits {

    private final Semaphore semaphore;
    // How many permits have been given back, so a run that is waiting can tell an engine that is busy from one whose
    // copies are never coming back.
    private final AtomicLong givenBack = new AtomicLong();

    /**
     * Creates the permits for a limit.
     *
     * @param limit How many copies limited runs may hold at once, or {@link RuleSet#UNLIMITED}
     */
    CopyPermits(int limit) {
        this.semaphore = new Semaphore(limit);
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
}
