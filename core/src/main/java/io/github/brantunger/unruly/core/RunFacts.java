package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RunContext;

import java.util.Map;

/**
 * What every rule of one run needs: the run's fact values, the read-only views built over them, and when the run
 * must stop, and which rules it uses. It also carries the run it was started from, and what is in progress on its
 * thread, so the engine passes one value from reading the facts to reporting the run, whether or not the run gets a
 * copy of the rules. <b>Internal:</b> this class may change in any release.
 *
 * <p>
 * The views and the evaluation context depend only on the run, not on the rule being evaluated, so the engine builds
 * them once here rather than once per condition. At a thousand rules that was two objects per condition, and the
 * engine's own allocation per run was several times what a run of the same rules costs now.
 * </p>
 *
 * <p>
 * A plain class, not a record: nothing compares or prints one, and a record's own methods would link through
 * {@code ObjectMethods}, which can fail for good when first called deep in the stack (#996).
 * </p>
 */
// Each accessor is named after the field it returns, as a record's are.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
final class RunFacts {
    private final Map<String, Object> values;
    private final Map<String, Object> forListeners;
    private final EngineEvaluationContext evaluation;
    private final Deadline deadline;
    private final long runId;
    private final RunContext parent;
    private final RunTally tally;
    private final RuleSelection selection;
    private final LoggedFailures.Runs runs;

    /**
     * @param values       The fact values the run was given, unwrapped
     * @param forListeners The view listeners are given, whose writes fail with a message about listeners
     * @param evaluation   The context every condition of the run is evaluated against, whose values the run's action
     *                     contexts share
     * @param deadline     When the run must stop, {@link Deadline#NONE} if it has none
     * @param runId        The run's number, which its Flight Recorder events carry
     * @param parent       The run this one was started from, or {@code null}
     * @param tally        What the run counts as it goes, for its Flight Recorder event
     * @param selection    Which rules the run uses, and which it skips
     * @param runs         What is in progress on the run's thread, which the run marks each of its call-outs with, so
     *                     a rule's condition or action, a listener callback or the output supplier or writer looks
     *                     nothing up to mark it (see {@link LoggedFailures#callOut()})
     */
    private RunFacts(Map<String, Object> values, Map<String, Object> forListeners, EngineEvaluationContext evaluation,
                     Deadline deadline, long runId, RunContext parent, RunTally tally, RuleSelection selection,
                     LoggedFailures.Runs runs) {
        this.values = values;
        this.forListeners = forListeners;
        this.evaluation = evaluation;
        this.deadline = deadline;
        this.runId = runId;
        this.parent = parent;
        this.tally = tally;
        this.selection = selection;
        this.runs = runs;
    }

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
     * @param runs         What {@link LoggedFailures#enter()} returned when the run started
     * @return The run's facts
     */
    static RunFacts of(Map<String, Object> values, Map<String, Object> forListeners, Deadline deadline, long runId,
                       RunContext parent, RunTally tally, RuleSelection selection, LoggedFailures.Runs runs) {
        RunState run = new RunState(deadline, new RunScope(), selection.startedAt(), selection.tags());
        return new RunFacts(values, forListeners, new EngineEvaluationContext(values, run), deadline, runId, parent,
                tally, selection, runs);
    }

    /** The fact values the run was given, unwrapped. */
    Map<String, Object> values() {
        return values;
    }

    /** The view listeners are given, whose writes fail with a message about listeners. */
    Map<String, Object> forListeners() {
        return forListeners;
    }

    /** The context every condition of the run is evaluated against, whose values the run's action contexts share. */
    EngineEvaluationContext evaluation() {
        return evaluation;
    }

    /** When the run must stop, {@link Deadline#NONE} if it has none. */
    Deadline deadline() {
        return deadline;
    }

    /** The run's number, which its Flight Recorder events carry. */
    long runId() {
        return runId;
    }

    /** The run this one was started from, or {@code null}. */
    RunContext parent() {
        return parent;
    }

    /** What the run counts as it goes, for its Flight Recorder event. */
    RunTally tally() {
        return tally;
    }

    /** Which rules the run uses, and which it skips. */
    RuleSelection selection() {
        return selection;
    }

    /** What is in progress on the run's thread, which the run marks each of its call-outs with. */
    LoggedFailures.Runs runs() {
        return runs;
    }
}
