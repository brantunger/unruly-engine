package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.EvaluationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * What a run's conditions are evaluated against. <b>Internal:</b> public only because {@link EvaluationContext} is
 * sealed to it.
 *
 * <p>
 * One context serves every condition of a run. Nothing here identifies the rule being evaluated, so there is nothing
 * to build per condition, and at a thousand rules building one per condition was a measurable share of what a run
 * allocated. {@link #isCancelled()} still answers for the moment it's called.
 * </p>
 *
 * <p>
 * It compares by identity, as {@link EngineRunContext} does, and so departs on purpose from the rule of
 * {@link Record#equals(Object)} that a copy with the same components is equal: a value {@code equals} would make the
 * contexts of two runs with equal facts equal, and a value {@code hashCode} would call each fact's, which can throw,
 * and change when a fact changes during the run.
 * </p>
 *
 * @param facts       The run's facts, read-only
 * @param runDeadline When the run must stop, which decides whether it has to
 */
public record EngineEvaluationContext(Map<String, Object> facts, Deadline runDeadline) implements EvaluationContext {

    /**
     * Wraps the facts in a read-only view, whose writes fail with a message about conditions.
     *
     * @throws NullPointerException if {@code facts} or {@code runDeadline} is {@code null}
     */
    public EngineEvaluationContext {
        facts = ReadOnlyFacts.forConditions(Objects.requireNonNull(facts, "facts must not be null"));
        Objects.requireNonNull(runDeadline, "runDeadline must not be null");
    }

    /**
     * Creates the context for a run that must stop at {@code deadline} on the system clock, as it is now: a step of
     * the system clock after that doesn't move when the context is cancelled. The test kit creates contexts with it.
     *
     * @param facts    The run's facts
     * @param deadline When the run must stop, or {@code null} if it has none
     * @throws NullPointerException if {@code facts} is {@code null}
     */
    public EngineEvaluationContext(Map<String, Object> facts, Instant deadline) {
        this(facts, Deadline.at(deadline));
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
     * Returns the time left before a context's deadline, as {@link EvaluationContext#timeLeft()} describes it.
     * <b>Internal:</b> public only so that method's default, in another package, can reach the deadline, which only
     * the engine's own context records have.
     *
     * @param context A context the engine created: this record, or an {@link EngineActionContext}
     * @return The time left
     */
    public static Duration timeLeft(EvaluationContext context) {
        Deadline deadline = context instanceof EngineEvaluationContext evaluation ? evaluation.runDeadline
                : ((EngineActionContext) context).runDeadline();
        return deadline.timeLeft();
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
     * Describes the context without its facts, so logging it can't leak a fact value.
     *
     * @return The description, such as {@code EvaluationContext(deadline=2026-09-16T12:00:00Z)}
     */
    @Override
    public String toString() {
        return "EvaluationContext(deadline=" + (runDeadline.isSet() ? runDeadline.instant() : "none") + ")";
    }
}
