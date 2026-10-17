package io.github.brantunger.unruly.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * What one run's contexts share: when the run must stop, the values its languages keep, when it started and which
 * tags it was given. The run's {@link EngineEvaluationContext} and each of its {@link EngineActionContext}s hold the
 * same one, so an action context is built from the facts, the output object and this, not from four copied
 * components, and the defaults of the context interfaces reach the run through one cast (see {@link BoundToRun}).
 * Built once per run, by {@link RunFacts#of}, and once per context the test kit creates. It compares by identity, as
 * its deadline and its scope do.
 */
final class RunState {

    private final Deadline runDeadline;
    private final RunScope runScope;
    private final Instant runStartedAt;
    private final Set<String> runTags;

    /**
     * Creates the state of one run.
     *
     * @param runDeadline  When the run must stop, which decides whether it has to
     * @param runScope     The values the run's languages keep, which the run's contexts share
     * @param runStartedAt When the run started, by the engine's clock, which judged every rule's validity window
     * @param runTags      The run's tags, sorted and read-only, or none if the run uses every rule; the set the run's
     *                     {@link EngineRunContext} has, not a copy
     * @throws NullPointerException if {@code runDeadline}, {@code runScope}, {@code runStartedAt} or {@code runTags}
     *                              is {@code null}
     */
    RunState(Deadline runDeadline, RunScope runScope, Instant runStartedAt, Set<String> runTags) {
        this.runDeadline = Objects.requireNonNull(runDeadline, "runDeadline must not be null");
        this.runScope = Objects.requireNonNull(runScope, "runScope must not be null");
        this.runStartedAt = Objects.requireNonNull(runStartedAt, "runStartedAt must not be null");
        this.runTags = Objects.requireNonNull(runTags, "runTags must not be null");
    }

    /**
     * Returns the state of a run that started now, by the system clock, without tags: what a context created outside
     * a run has, as the test kit creates them.
     *
     * @param deadline When the run must stop
     * @param scope    The values the run's languages keep
     * @return The state
     * @throws NullPointerException if {@code deadline} or {@code scope} is {@code null}
     */
    static RunState startingNow(Deadline deadline, RunScope scope) {
        return new RunState(deadline, scope, Instant.now(), Set.of());
    }

    /**
     * Returns when the run must stop.
     *
     * @return The deadline, {@link Deadline#NONE} if the run has none
     */
    Deadline deadline() {
        return runDeadline;
    }

    /**
     * Returns the values the run's languages keep.
     *
     * @return The scope
     */
    RunScope scope() {
        return runScope;
    }

    /**
     * Returns when the run started, by the engine's clock.
     *
     * @return The instant
     */
    Instant startedAt() {
        return runStartedAt;
    }

    /**
     * Returns the tags the run was given.
     *
     * @return The tags, sorted and read-only, or none if the run uses every rule
     */
    Set<String> tags() {
        return runTags;
    }
}
