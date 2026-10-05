package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.EvaluationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

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
 * @param runScope    The values the run's languages keep, which the run's action contexts share
 */
public record EngineEvaluationContext(Map<String, Object> facts, Deadline runDeadline, RunScope runScope)
        implements EvaluationContext {

    /**
     * Wraps the facts in a read-only view, whose writes fail with a message about conditions.
     *
     * @throws NullPointerException if {@code facts}, {@code runDeadline} or {@code runScope} is {@code null}
     */
    public EngineEvaluationContext {
        facts = ReadOnlyFacts.forConditions(Objects.requireNonNull(facts, "facts must not be null"));
        Objects.requireNonNull(runDeadline, "runDeadline must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
    }

    /**
     * Creates the context for a run of its own, whose values no other context shares.
     *
     * @param facts       The run's facts
     * @param runDeadline When the run must stop
     * @throws NullPointerException if {@code facts} or {@code runDeadline} is {@code null}
     */
    public EngineEvaluationContext(Map<String, Object> facts, Deadline runDeadline) {
        this(facts, runDeadline, new RunScope());
    }

    /**
     * Creates the context for a run that must stop at {@code deadline} on the system clock, as it is now: a step of
     * the system clock after that doesn't move when the context is cancelled. The test kit creates contexts with it.
     * The context's run is its own, so no other context shares its values. It rejects a fact name a run rejects
     * whatever its languages: {@code null} or blank.
     *
     * @param facts    The run's facts
     * @param deadline When the run must stop, or {@code null} if it has none
     * @throws NullPointerException     if {@code facts} is {@code null}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank, which a run rejects with the same
     *                                  message
     */
    public EngineEvaluationContext(Map<String, Object> facts, Instant deadline) {
        this(FactNames.requireRunNames(Objects.requireNonNull(facts, "facts must not be null")), Deadline.at(deadline));
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
        return runDeadlineOf(context).timeLeft();
    }

    /**
     * Returns the value a context's run keeps under a key, as {@link EvaluationContext#runScoped} describes it.
     * <b>Internal:</b> public only so that method's default, in another package, can reach the run's values, which
     * only the engine's own context records have.
     *
     * @param context A context the engine created: this record, or an {@link EngineActionContext}
     * @param key     The key
     * @param init    Makes the value the first time the run asks for it
     * @param <T>     The value's type
     * @return The value
     * @throws NullPointerException  if {@code key}, {@code init} or what {@code init} returns is {@code null}
     * @throws IllegalStateException if the init of {@code key} is running, or {@code key} is kept with
     *                               {@link EvaluationContext#runScopedClosing}
     */
    public static <T> T runScoped(EvaluationContext context, Object key, Supplier<? extends T> init) {
        return runScopeOf(context).get(key, init);
    }

    /**
     * Returns the value a context's run keeps under a key, and closes when the run ends, as
     * {@link EvaluationContext#runScopedClosing} describes it. <b>Internal:</b> public only so that method's default,
     * in another package, can reach the run's values, which only the engine's own context records have.
     *
     * @param context A context the engine created: this record, or an {@link EngineActionContext}
     * @param key     The key
     * @param init    Makes the value the first time the run asks for it
     * @param <T>     The value's type
     * @return The value
     * @throws NullPointerException  if {@code key}, {@code init} or what {@code init} returns is {@code null}
     * @throws IllegalStateException if the init of {@code key} is running, {@code key} is kept with
     *                               {@link EvaluationContext#runScoped}, or the run has ended
     */
    public static <T extends AutoCloseable> T runScopedClosing(EvaluationContext context, Object key,
                                                               Supplier<? extends T> init) {
        return runScopeOf(context).getClosing(key, init);
    }

    /**
     * Ends the run of a context the test kit created, as a run ends: closes the values kept with
     * {@link EvaluationContext#runScopedClosing}, in the reverse of the order they were made, each whatever the others
     * throw, and fails any later request for one. Unlike a run, it throws what a {@code close()} threw: the first, with
     * the others suppressed on it, or what releasing the run's lock threw (see {@link RunScope#end()}), with what each
     * {@code close()} threw suppressed on it. Calling it again does nothing. Called from a {@code runScopedClosing}
     * init, it closes the run's values made so far, and that init's own value is closed and refused too (see
     * {@link RunScope#getClosing}). <b>Internal:</b> public only for the test kit.
     *
     * @param context A context the engine created: this record, or an {@link EngineActionContext}
     * @throws NullPointerException if {@code context} is {@code null}
     * @throws Exception            what releasing the run's lock threw, or else the first thing a value's
     *                              {@code close()} threw, as it is, with what the others threw suppressed on it: each
     *                              once, and none it already carries or that carries it (see {@link Failures#keepAlso})
     */
    // Any Throwable: each value is closed whatever the one before threw, and what the first threw is thrown as it is.
    public static void endRun(EvaluationContext context) throws Exception {
        Objects.requireNonNull(context, "context must not be null");
        RunScope scope = runScopeOf(context);
        List<AutoCloseable> values = scope.end();
        // What releasing the scope's lock threw once it had handed the values over, which are closed all the same.
        Throwable first = scope.endFailure();
        for (int i = values.size() - 1; i >= 0; i--) {
            try {
                values.get(i).close();
            } catch (Throwable e) {
                if (first == null) {
                    first = e;
                } else {
                    // Not the same one twice, nor one that would make a loop of causes and suppressed exceptions.
                    Failures.keepAlso(first, e);
                }
            }
        }
        Failures.<RuntimeException>rethrowUnchecked(first);
    }

    /**
     * Returns when a context's run must stop.
     *
     * @param context A context the engine created: this record, or an {@link EngineActionContext}
     * @return The run's deadline
     */
    static Deadline runDeadlineOf(EvaluationContext context) {
        return context instanceof EngineEvaluationContext evaluation ? evaluation.runDeadline
                : ((EngineActionContext) context).runDeadline();
    }

    /**
     * Returns the values a context's run keeps.
     *
     * @param context A context the engine created: this record, or an {@link EngineActionContext}
     * @return The run's values
     */
    static RunScope runScopeOf(EvaluationContext context) {
        return context instanceof EngineEvaluationContext evaluation ? evaluation.runScope
                : ((EngineActionContext) context).runScope();
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
