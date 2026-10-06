package io.github.brantunger.unruly.core;

import java.util.ArrayList;
import java.util.List;

/**
 * What one run counts as it goes, for its {@link RunEvent}: the conditions evaluated and the actions that ran to
 * completion. Kept while the run runs, so a run that fails or stops still reports how far it got. For a run that needs
 * the room for a language's first run, also the languages of the actions that ran to completion, which the run
 * records as having run once it returns (see {@link RuleSet#ran(List)}). <b>Internal:</b> this class may change in any
 * release.
 */
final class RunTally {

    private int evaluated;
    private int fired;
    private boolean stopped;
    // Not for the event: whether an interrupt stopped the run, so the engine can set the thread's interrupt status
    // again, which a listener told of the stop, or a language's close(), may have cleared.
    private boolean interrupted;
    // The languages of the actions that ran to completion, each once, once recordLanguages() has been called; null
    // otherwise, so a run that needs no room for a language's first run allocates nothing for them.
    private List<String> languagesFired;

    /** Counts a condition evaluated. */
    void countEvaluated() {
        evaluated++;
    }

    /**
     * Counts an action that ran to completion, and records its language if {@link #recordLanguages()} was called.
     *
     * @param language The name of the language of the rule whose action it was
     */
    void countFired(String language) {
        fired++;
        if (languagesFired != null && !languagesFired.contains(language)) {
            languagesFired.add(language);
        }
    }

    /** Makes {@link #countFired(String)} record the language of each action, for a run of a language's first runs. */
    void recordLanguages() {
        languagesFired = new ArrayList<>(2);
    }

    /**
     * Returns the languages of the actions that ran to completion, as {@link #countFired(String)} recorded them.
     *
     * @return The names of the languages, each once; empty unless {@link #recordLanguages()} was called
     */
    List<String> firedLanguages() {
        return languagesFired != null ? languagesFired : List.of();
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
