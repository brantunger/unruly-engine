package io.github.brantunger.unruly.core;

import java.time.Duration;
import java.time.Instant;

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

    private Cancellation() {
    }

    /**
     * Returns when a run that starts now and may take {@code timeout} must stop.
     *
     * @param timeout How long the run may take, or {@code null} if it has no deadline
     * @return The deadline, or {@code null} if {@code timeout} is {@code null}
     */
    static Instant deadlineFrom(Duration timeout) {
        return timeout == null ? null : Instant.now().plus(timeout);
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
