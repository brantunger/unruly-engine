package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.time.Instant;
import java.util.Objects;

/**
 * A {@link RuleExecutionException} an engine throws from {@code run()} once it has logged it and told its listeners.
 * When a {@code run()} started from a condition or action fails with one, the run around it doesn't log that failure
 * again: see {@link Failures#nestedRunFailure}. A {@code RuleExecutionException} a language or a rule throws itself
 * is never one, so it is logged and escaped like any other exception.
 */
final class ReportedFailure extends RuleExecutionException {
    private static final long serialVersionUID = 1L;

    /** Whether the run stopped, because it was interrupted or passed its deadline, rather than failed. */
    private final boolean stopped;
    /** The deadline the run passed, or {@code null} if it failed or was interrupted. */
    private final Instant deadline;

    /**
     * Creates the exception for a failure that belongs to no rule.
     *
     * @param message What failed
     * @param cause   Why, or {@code null}
     */
    ReportedFailure(String message, Throwable cause) {
        this(message, cause, false, null);
    }

    private ReportedFailure(String message, Throwable cause, boolean stopped, Instant deadline) {
        super(message, cause);
        this.stopped = stopped;
        this.deadline = deadline;
    }

    /**
     * Creates the exception for a run that stopped because it was interrupted or passed its deadline.
     *
     * @param message  Where the run stopped
     * @param cause    An {@link InterruptedException} or a {@link java.util.concurrent.TimeoutException}
     * @param deadline The deadline the run passed, or {@code null} if it was interrupted
     * @return The exception, which belongs to no rule
     */
    static ReportedFailure stop(String message, Exception cause, Instant deadline) {
        return new ReportedFailure(message, cause, true, deadline);
    }

    /**
     * Tells whether this is a run that stopped for the same reason: interrupted, or past the same deadline.
     *
     * @param passed The deadline another run passed, or {@code null} for an interrupt
     * @return {@code true} if this run stopped, and for that reason
     */
    boolean isStopFor(Instant passed) {
        return stopped && Objects.equals(deadline, passed);
    }

    /**
     * Creates the exception for one rule's condition or action.
     *
     * @param message  What failed
     * @param cause    Why
     * @param ruleName The rule's name
     * @param kind     Whether its condition or its action failed
     */
    ReportedFailure(String message, Throwable cause, String ruleName, ExpressionKind kind) {
        super(message, cause, ruleName, kind);
        this.stopped = false;
        this.deadline = null;
    }
}
