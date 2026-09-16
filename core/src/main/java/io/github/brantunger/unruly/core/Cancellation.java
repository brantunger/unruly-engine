package io.github.brantunger.unruly.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * Whether a run must stop: its thread has been interrupted, or it has passed the deadline its timeout gave it.
 *
 * <p>
 * One predicate answers that for the engine, which checks it between rules, and for a language, which asks its
 * context with {@link io.github.brantunger.unruly.api.language.EvaluationContext#isCancelled()} while an expression
 * runs. So a run stops for the same reasons wherever it is.
 * </p>
 */
final class Cancellation {

    // The deadline of the run this thread is in, whichever engine runs it, so a run started from inside it (an action
    // that runs another engine, say) stops no later than the run that started it. A plain ThreadLocal, like
    // RuleSet's count of runs on a thread.
    private static final ThreadLocal<Instant> RUN_DEADLINE = new ThreadLocal<>();

    private Cancellation() {
    }

    /**
     * Returns when a run that starts now on this thread and may take {@code timeout} must stop: after
     * {@code timeout}, or at the deadline of the run it was started from, whichever comes first.
     *
     * @param timeout How long the run may take, or {@code null} if it has no timeout of its own
     * @return The deadline, or {@code null} if the run has none
     */
    static Instant deadlineFrom(Duration timeout) {
        return earliest(timeout == null ? null : after(Instant.now(), timeout), RUN_DEADLINE.get());
    }

    /**
     * Returns {@code timeout} after {@code start}, or the latest instant there is when that would be later, so a
     * timeout long enough to mean "no real limit" doesn't overflow.
     *
     * @param start   When the run starts
     * @param timeout How long it may take; positive
     * @return The deadline
     */
    static Instant after(Instant start, Duration timeout) {
        return timeout.compareTo(Duration.between(start, Instant.MAX)) < 0 ? start.plus(timeout) : Instant.MAX;
    }

    /**
     * Returns the earlier of two deadlines.
     *
     * @param first  A deadline, or {@code null} for none
     * @param second Another deadline, or {@code null} for none
     * @return The earlier one, or {@code null} if neither is set
     */
    static Instant earliest(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        return second == null || first.isBefore(second) ? first : second;
    }

    /**
     * Makes {@code deadline} the one runs started on this thread from now on inherit, until {@link #leave(Instant)}.
     *
     * @param deadline The deadline of the run that is starting, or {@code null} if it has none
     * @return The deadline to put back when the run ends
     */
    static Instant enter(Instant deadline) {
        Instant outer = RUN_DEADLINE.get();
        restore(deadline);
        return outer;
    }

    /**
     * Puts back the deadline that applied before a run started, leaving no entry behind on a thread that is no longer
     * running anything with a deadline.
     *
     * @param outer What {@link #enter(Instant)} returned
     */
    static void leave(Instant outer) {
        restore(outer);
    }

    private static void restore(Instant deadline) {
        if (deadline == null) {
            RUN_DEADLINE.remove();
        } else {
            RUN_DEADLINE.set(deadline);
        }
    }

    /**
     * Returns how long is left before a deadline.
     *
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return The time left, which is zero or negative once the deadline has passed, or {@code null} if there is no
     *         deadline
     */
    static Duration timeLeft(Instant deadline) {
        return deadline == null ? null : Duration.between(Instant.now(), deadline);
    }

    /**
     * Creates the exception a run past its deadline is caused by.
     *
     * @param deadline The deadline that passed
     * @return The exception
     */
    static TimeoutException timedOut(Instant deadline) {
        return new TimeoutException("The run's deadline of " + deadline + " has passed");
    }

    /**
     * Returns whether a run with this deadline must stop.
     *
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return {@code true} if the current thread is interrupted, or the deadline has passed
     */
    static boolean isCancelled(Instant deadline) {
        // isInterrupted(), never interrupted(): the status stays set, so the caller still sees it.
        return Thread.currentThread().isInterrupted() || hasPassed(deadline);
    }

    /**
     * Returns whether a run's deadline has passed.
     *
     * @param deadline When the run must stop, or {@code null} if it has none
     * @return {@code true} if there is a deadline and it is not in the future
     */
    static boolean hasPassed(Instant deadline) {
        return deadline != null && !Instant.now().isBefore(deadline);
    }
}
