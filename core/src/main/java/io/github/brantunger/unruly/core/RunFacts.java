package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RunContext;

import java.util.Map;

/**
 * What every rule of one run needs: the run's fact values, the read-only views built over them, and when the run
 * must stop, and which rules it uses. It also carries the run it was started from, so the engine passes one value
 * from reading the facts to reporting the run, whether or not the run gets a copy of the rules. <b>Internal:</b> this
 * record may change in any release.
 *
 * <p>
 * The views and the evaluation context depend only on the run, not on the rule being evaluated, so the engine builds
 * them once here rather than once per condition. At a thousand rules that was two objects per condition, and the
 * engine's own allocation per run was several times what a run of the same rules costs now.
 * </p>
 *
 * @param values       The fact values the run was given, unwrapped
 * @param forListeners The view listeners are given, whose writes fail with a message about listeners
 * @param evaluation   The context every condition of the run is evaluated against, whose values the run's action
 *                     contexts share
 * @param deadline     When the run must stop, {@link Deadline#NONE} if it has none
 * @param runId        The run's number, which its Flight Recorder events carry
 * @param parent       The run this one was started from, or {@code null}
 * @param tally        What the run counts as it goes, for its Flight Recorder event
 * @param selection    Which rules the run uses, and which it skips
 */
record RunFacts(Map<String, Object> values, Map<String, Object> forListeners, EngineEvaluationContext evaluation,
                Deadline deadline, long runId, RunContext parent, RunTally tally, RuleSelection selection) {

    /**
     * Builds the views one run needs.
     *
     * @param values       The fact values the run was given, unwrapped
     * @param forListeners The view listeners are given, which the run already built to report itself with
     * @param deadline     When the run must stop, {@link Deadline#NONE} if it has none
     * @param runId        The run's number
     * @param parent       The run this one was started from, or {@code null}
     * @param tally        What the run counts as it goes
     * @param selection    Which rules the run uses
     * @return The run's facts
     */
    static RunFacts of(Map<String, Object> values, Map<String, Object> forListeners, Deadline deadline, long runId,
                       RunContext parent, RunTally tally, RuleSelection selection) {
        return new RunFacts(values, forListeners, new EngineEvaluationContext(values, deadline, new RunScope()),
                deadline, runId, parent, tally, selection);
    }
}
