package io.github.brantunger.unruly.core;

/**
 * What one run counts as it goes, for its {@link RunEvent}: the conditions evaluated and the actions that ran to
 * completion. Kept while the run runs, so a run that fails or stops still reports how far it got. <b>Internal:</b>
 * this class may change in any release.
 */
final class RunTally {

    private int evaluated;
    private int fired;
    private boolean stopped;
    // Not for the event: whether an interrupt stopped the run, so the engine can set the thread's interrupt status
    // again, which a listener told of the stop, or a language's close(), may have cleared.
    private boolean interrupted;

    /** Counts a condition evaluated. */
    void countEvaluated() {
        evaluated++;
    }

    /** Counts an action that ran to completion. */
    void countFired() {
        fired++;
    }

    /**
     * Records that the run stopped, when a fatal error rethrown in its place would otherwise hide that: listeners
     * were told the run stopped, so its event says so too.
     */
    void markStopped() {
        stopped = true;
    }

    /**
     * Tells whether the run was recorded as stopped by {@link #markStopped()}.
     *
     * @return {@code true} if it stopped
     */
    boolean hasStopped() {
        return stopped;
    }

    /** Records that an interrupt stopped the run, whether or not the stop reached the listeners. */
    void markInterrupted() {
        interrupted = true;
    }

    /**
     * Tells whether an interrupt stopped the run, as {@link #markInterrupted()} recorded.
     *
     * @return {@code true} if it did
     */
    boolean wasInterrupted() {
        return interrupted;
    }

    /**
     * Returns how many conditions the run evaluated.
     *
     * @return The count so far
     */
    int evaluatedCount() {
        return evaluated;
    }

    /**
     * Returns how many actions the run ran to completion.
     *
     * @return The count so far
     */
    int firedCount() {
        return fired;
    }
}
