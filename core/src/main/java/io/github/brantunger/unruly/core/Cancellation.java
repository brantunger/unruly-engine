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
 *
 * <p>
 * Whether a deadline has passed is decided by the {@link Deadline} alone, on {@link System#nanoTime()}; its
 * {@link Instant} is only shown, in {@link io.github.brantunger.unruly.api.language.EvaluationContext#deadline()} and
 * in messages.
 * </p>
 */
final class Cancellation {

    // The deadline of the run this thread is in, whichever engine runs it, so a run started from inside it (an action
    // that runs another engine, say) stops no later than the run that started it. A plain ThreadLocal, like
    // RuleSet's count of runs on a thread.
    private static final ThreadLocal<Deadline> RUN_DEADLINE = new ThreadLocal<>();

    /** Why a run must stop, as {@link #reason(Deadline)} finds it. */
    enum Reason {
        /** Neither: the run may go on. */
        NONE,
        /** The run's thread has been interrupted. */
        INTERRUPTED,
        /** The run has passed its deadline, and its thread hasn't been interrupted. */
        TIMED_OUT
    }

    private Cancellation() {
    }

    /**
     * Returns when a run that starts now on this thread and may take {@code timeout} must stop: after
     * {@code timeout}, or at the deadline of the run it was started from, whichever comes first.
     *
     * @param timeout How long the run may take, or {@code null} if it has no timeout of its own
     * @return The deadline, which is {@link Deadline#NONE} if the run has none, and the very one of the run it was
     *         started from when that comes first
     */
    static Deadline deadlineFrom(Duration timeout) {
        Deadline outer = RUN_DEADLINE.get();
        if (outer == null) {
            return timeout == null ? Deadline.NONE : Deadline.from(timeout);
        }
        // A run whose own timeout ends no sooner than its outer run's deadline builds no deadline of its own.
        if (timeout == null || outer.nanosLeft() <= Deadline.nanosOf(timeout)) {
            return outer;
        }
        return Deadline.earliest(Deadline.from(timeout), outer);
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
     * Makes {@code deadline} the one runs started on this thread from now on inherit, until {@link #leave(Deadline)}.
     *
     * @param deadline The deadline of the run that is starting, {@link Deadline#NONE} if it has none
     * @return The deadline to put back when the run ends
     */
    static Deadline enter(Deadline deadline) {
        Deadline outer = RUN_DEADLINE.get();
        restore(deadline);
        return outer;
    }

    /**
     * Puts back the deadline that applied before a run started, leaving no entry behind on a thread that is no longer
     * running anything with a deadline.
     *
     * @param outer What {@link #enter(Deadline)} returned
     */
    static void leave(Deadline outer) {
        restore(outer);
    }

    private static void restore(Deadline deadline) {
        if (deadline == null || !deadline.isSet()) {
            RUN_DEADLINE.remove();
        } else {
            RUN_DEADLINE.set(deadline);
        }
    }

    /**
     * Creates the exception a run past its deadline is caused by.
     *
     * @param deadline The deadline that passed
     * @return The exception
     */
    static TimeoutException timedOut(Deadline deadline) {
        return new TimeoutException("The run's deadline of " + deadline.instant() + " has passed");
    }

    /**
     * Returns whether a run with this deadline must stop.
     *
     * @param deadline When the run must stop
     * @return {@code true} if the current thread is interrupted, or the deadline has passed
     */
    static boolean isCancelled(Deadline deadline) {
        // isInterrupted(), never interrupted(): the status stays set, so the caller still sees it.
        return Thread.currentThread().isInterrupted() || deadline.hasPassed();
    }

    /**
     * Returns why a run with this deadline must stop: an interrupt first, and its deadline only when its thread hasn't
     * been interrupted. It allocates nothing, so a caller that checks it often builds its message only when the run
     * stops.
     *
     * @param deadline When the run must stop, {@link Deadline#NONE} if it has none
     * @return {@link Reason#INTERRUPTED}, {@link Reason#TIMED_OUT}, or {@link Reason#NONE} if the run may go on
     */
    static Reason reason(Deadline deadline) {
        // isInterrupted(), never interrupted(): the status stays set, so the caller still sees it.
        if (Thread.currentThread().isInterrupted()) {
            return Reason.INTERRUPTED;
        }
        return deadline.hasPassed() ? Reason.TIMED_OUT : Reason.NONE;
    }
}
