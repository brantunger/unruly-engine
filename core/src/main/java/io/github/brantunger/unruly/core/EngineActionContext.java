package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.EvaluationContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * What one action runs against. <b>Internal:</b> public only because {@link ActionContext} is sealed to it.
 *
 * <p>
 * It compares by identity, as {@link EngineEvaluationContext} does, and so departs on purpose from the rule of
 * {@link Record#equals(Object)} that a copy with the same components is equal: each action gets a context of its own,
 * and neither the facts nor the output object is compared or hashed.
 * </p>
 *
 * @param facts       The run's facts, read-only
 * @param output      The output object the action changes
 * @param runDeadline When the run must stop, which decides whether it has to
 * @param runScope    The values the run's languages keep, shared with the run's evaluation context
 */
public record EngineActionContext(Map<String, Object> facts, Object output, Deadline runDeadline, RunScope runScope)
        implements ActionContext {

    /**
     * Wraps the facts in a read-only view, whose writes fail with a message about actions.
     *
     * @throws NullPointerException if {@code facts}, {@code output}, {@code runDeadline} or {@code runScope} is
     *                              {@code null}
     */
    public EngineActionContext {
        facts = ReadOnlyFacts.forActions(Objects.requireNonNull(facts, "facts must not be null"));
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(runDeadline, "runDeadline must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
    }

    /**
     * Creates the context for a run of its own, whose values no other context shares.
     *
     * @param facts       The run's facts
     * @param output      The output object the action changes
     * @param runDeadline When the run must stop
     * @throws NullPointerException if {@code facts}, {@code output} or {@code runDeadline} is {@code null}
     */
    public EngineActionContext(Map<String, Object> facts, Object output, Deadline runDeadline) {
        this(facts, output, runDeadline, new RunScope());
    }

    /**
     * Creates the context for a run that must stop at {@code deadline} on the system clock, as it is now: a step of
     * the system clock after that doesn't move when the context is cancelled. The test kit creates contexts with it.
     * The context's run is its own, so no other context shares its values. It rejects a fact name a run rejects
     * whatever its languages: {@code null} or blank.
     *
     * @param facts    The run's facts
     * @param output   The output object the action changes
     * @param deadline When the run must stop, or {@code null} if it has none
     * @throws NullPointerException     if {@code facts} or {@code output} is {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, which a run rejects with the same
     *                                  message
     */
    public EngineActionContext(Map<String, Object> facts, Object output, Instant deadline) {
        this(FactNames.requireRunNames(Objects.requireNonNull(facts, "facts must not be null")), output,
                Deadline.at(deadline));
    }

    /**
     * Creates the context for an action of the run another context belongs to: it has that run's facts and deadline,
     * and shares its {@link EvaluationContext#runScoped} values. The test kit creates contexts with it.
     *
     * @param sameRun A context of the run, which the engine or the test kit created: every context is one, as the
     *                context interfaces are sealed
     * @param output  The output object the action changes
     * @throws NullPointerException if {@code sameRun} or {@code output} is {@code null}
     */
    public EngineActionContext(EvaluationContext sameRun, Object output) {
        this(Objects.requireNonNull(sameRun, "sameRun must not be null").facts(), output,
                EngineEvaluationContext.runDeadlineOf(sameRun), EngineEvaluationContext.runScopeOf(sameRun));
    }

    @Override
    public boolean isCancelled() {
        return Cancellation.isCancelled(runDeadline);
    }

    @Override
    public Instant deadline() {
        return runDeadline.instant();
    }

    /**
     * Tells whether {@code other} is this context: a context equals only itself, not even a copy with the same
     * components.
     *
     * @param other The other object
     * @return {@code true} if it is this context
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /**
     * Describes the context without its facts or its output object, so logging it can't leak their values.
     *
     * @return The description, such as {@code ActionContext(output=java.util.HashMap, deadline=none)}
     */
    @Override
    public String toString() {
        return "ActionContext(output=" + output.getClass().getName() + ", deadline="
                + (runDeadline.isSet() ? runDeadline.instant() : "none") + ")";
    }
}
