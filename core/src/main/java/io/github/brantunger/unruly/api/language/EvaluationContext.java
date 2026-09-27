package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
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
     * Returns when the run must stop, from the timeout its engine or its {@code run} call was given: when the run
     * started, on the system clock, plus its timeout. So it compares with {@link Instant#now()}, and shows the
     * deadline a person or a log expects.
     *
     * <p>
     * It is for showing. What stops the run is measured with a monotonic clock from when the run started, so a step
     * of the system clock while the run goes on moves {@link Instant#now()}, but not when the run stops. To time a
     * language's own work, use {@link #timeLeft()}, which is exact; {@link #isCancelled()} says whether the run must
     * stop.
     * </p>
     *
     * @return The deadline, or {@code null} if the run has none
     */
    @Nullable Instant deadline();

    /**
     * Returns how long the run has left before it must stop, measured as the engine measures it. A language that gives
     * a call of its own a timeout, such as a script it runs on another thread, gives it this much time; working it out
     * from {@link #deadline()} and {@link Instant#now()} instead is off by as much as the system clock has been
     * stepped since the run started.
     *
     * <p>
     * A run without a deadline has {@code Duration.ofNanos(Long.MAX_VALUE)}, about 292 years, and a run whose deadline
     * is further away than that has a little less: so the time left always converts with {@link Duration#toNanos()}
     * or {@link Duration#toMillis()} without overflowing. The time left says nothing about an interrupt:
     * {@link #isCancelled()} answers for both.
     * </p>
     *
     * @return The time left: positive while the deadline is ahead, {@link Duration#ZERO} once it has passed, and at
     *         most {@code Duration.ofNanos(Long.MAX_VALUE)}
     */
    default Duration timeLeft() {
        return io.github.brantunger.unruly.core.EngineEvaluationContext.timeLeft(this);
    }
}
