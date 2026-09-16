package io.github.brantunger.unruly.core;

/**
 * How many compiled copies of the rules runs may hold at once, and which runs that applies to.
 * <b>Internal:</b> this record may change in any release.
 *
 * <p>
 * An engine built without a limit of its own limits <b>runs on virtual threads</b> to one copy for every two
 * processors, and at least one. A copy is expensive — for MVEL it recompiles every expression, and each copy
 * generates its own accessor classes — and a platform thread pool already bounds how many runs overlap, while virtual
 * threads don't: a run for each of ten thousand virtual threads made ten thousand copies. Limiting only virtual
 * threads leaves a thread pool's throughput as it was, and bounds the case that has no bound of its own.
 * </p>
 *
 * <p>
 * With more than one processor, the limit is below the number of processors, which is how many platform threads carry
 * virtual threads unless the scheduler is configured otherwise. On JDK 21 to 23 a virtual thread that waits to enter
 * a monitor, or waits while holding one, stays on its carrier. When a language's expressions contend on a monitor, as
 * MVEL's do, a limit of one copy for each processor let every carrier wait, and the runs deadlocked. A lower limit
 * makes that less likely but can't rule it out: the limit is per rule list, and a run that gives up waiting takes an
 * extra copy.
 * </p>
 *
 * @param maxCopies          The most copies runs the limit applies to may hold at once, or {@link RuleSet#UNLIMITED}
 * @param virtualThreadsOnly Whether it applies only to a run on a virtual thread
 */
public record CopyLimit(int maxCopies, boolean virtualThreadsOnly) {

    /**
     * Returns the limit of an engine that makes as many copies as its runs need, on any thread.
     *
     * @return The limit
     */
    public static CopyLimit none() {
        return new CopyLimit(RuleSet.UNLIMITED, false);
    }

    /**
     * Returns a limit that applies to every run, whatever thread it is on.
     *
     * @param maxCopies The most copies runs may hold at once
     * @return The limit
     */
    public static CopyLimit of(int maxCopies) {
        return new CopyLimit(maxCopies, false);
    }

    /**
     * Returns the default limit: one copy for every two processors, and at least one, for runs on virtual threads
     * only. The number of processors is read once, here, so an engine's limit doesn't change while it runs.
     *
     * @return The limit
     */
    public static CopyLimit forVirtualThreads() {
        return new CopyLimit(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), true);
    }

    /**
     * Returns whether this limits anything at all.
     *
     * @return {@code true} unless the engine makes as many copies as its runs need
     */
    public boolean limits() {
        return maxCopies != RuleSet.UNLIMITED;
    }

    /**
     * Returns whether a run starting now is limited.
     *
     * @return {@code true} if this limits runs, and the current thread is one it applies to
     */
    public boolean appliesToCurrentThread() {
        return limits() && (!virtualThreadsOnly || Thread.currentThread().isVirtual());
    }
}
