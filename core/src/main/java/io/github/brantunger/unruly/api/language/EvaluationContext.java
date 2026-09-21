package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * What a condition is evaluated against.
 *
 * <p>
 * <b>Implemented by the engine</b>, which passes it to a language. It's sealed, so a language can't implement it; a
 * language's unit tests create one with {@code io.github.brantunger.unruly.test.LanguageTestContexts}, from the
 * {@code unruly-engine-test} artifact. Because only the engine implements it, a later release can add methods to it
 * without breaking languages.
 * </p>
 */
public sealed interface EvaluationContext
        permits ActionContext, io.github.brantunger.unruly.core.EngineEvaluationContext {

    /**
     * Returns the values of the run's facts by name.
     *
     * @return A read-only map, whose values can be {@code null}; writing to it throws
     *         {@link UnsupportedOperationException}
     */
    Map<String, @Nullable Object> facts();

    /**
     * Returns whether the run must stop, because its thread has been interrupted or it has passed its deadline.
     *
     * <p>
     * The engine checks this itself before each condition and each action, and again when each one returns, so a
     * language that evaluates an expression and returns needn't. A language whose expressions can stop part-way polls
     * it, or maps it to its own cancellation, so a long-running expression stops too. Returning any value is enough:
     * the engine stops the run as soon as the expression returns, whatever it returned. Throwing an exception once the
     * run is cancelled stops the run the same way; an {@link Error}, or an exception with one anywhere in its cause
     * chain, such as one wrapping what a fact's Java code threw, is still that rule's failure. A language that
     * can't stop inside an expression runs it to its end, and the run stops when it returns.
     * </p>
     *
     * @return {@code true} if the run must stop
     */
    boolean isCancelled();

    /**
     * Returns when the run must stop, from the timeout its engine or its {@code run} call was given.
     *
     * @return The deadline, or {@code null} if the run has none. {@link #isCancelled()} already accounts for it; a
     *         language can use it to give a call of its own a timeout.
     */
    @Nullable Instant deadline();
}
