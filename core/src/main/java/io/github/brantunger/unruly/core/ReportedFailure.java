package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

import java.util.Arrays;


/**
 * A {@link RuleExecutionException} an engine throws from {@code run()} once it has logged it and told its listeners.
 * When a {@code run()} started from a condition, an action, the output supplier, a listener callback or a language
 * fails with one, the run, {@code load()} or {@code validate()} around it doesn't log that failure again: see
 * {@link Failures#nestedRunFailure}. A {@code RuleExecutionException} a language or a rule throws itself is never one,
 * so it is logged and escaped like any other exception.
 *
 * <p>
 * Each one records, when it's built, the innermost failure and the first {@link Error} in its cause chain, so the run
 * around it reads them from the first failure in its own chain that recorded them. Every nested run adds a link to the
 * chain, and the engine reads only {@value Failures#MAX_CAUSE_CHAIN_LENGTH} links of it (see {@link Failures#below}),
 * so reading them from the chain alone would miss a failure or an error more than that many runs down. A form
 * serialized before these were recorded reads {@link #recorded()} as {@code false}, and its chain is read as it was
 * then.
 * </p>
 *
 * <p>
 * It records too the failure a nested run or load logged that names its chain, and whether a {@code load()} logged
 * it, which may be an exception thrown as is, such as a fact a nested run rejected, rather than one of these (see
 * {@link LoggedFailures}): the thread's record of those is forgotten when its outermost run ends, and this failure
 * may be thrown on, or read, after that. When an exception with a message of its own is wrapped around that failure
 * (see {@link Failures#below}), this one, which the engine logged with that message, names its chain instead, so a
 * run around it doesn't lose the message either. A form serialized before that was recorded names its chain by the
 * innermost failure, as it did then.
 * </p>
 */
final class ReportedFailure extends RuleExecutionException {
    private static final long serialVersionUID = 1L;

    /** Whether the run stopped, because it was interrupted or passed its deadline, rather than failed. */
    private final boolean stopped;
    /**
     * Whether the run stopped because it was interrupted. {@code false} in a form serialized before this was recorded,
     * so a stop read from one is never taken for another run's.
     */
    private final boolean interrupted;
    /**
     * The deadline the run passed, which tells whether another run stopped for the same one, or {@code null} if it
     * failed or was interrupted, or once this has been deserialized: a deadline is the same only as itself.
     */
    private final transient Deadline passed;
    /** The innermost engine failure in the cause chain, or {@code null} if this is the innermost one. */
    private final ReportedFailure innermostBelow;
    /** The first {@link Error} in the cause chain, or {@code null} if there is none. */
    private final Error firstError;
    /** Whether the two above were recorded; {@code false} only in a form serialized before they were. */
    private final boolean belowRecorded;
    /**
     * The failure a nested run or load logged that names the cause chain (see {@link Failures#below}), this one if an
     * exception with a message of its own is wrapped around that failure, or {@code null} if there is none, or in a
     * form serialized before it was recorded.
     */
    private final Throwable loggedBelow;
    /** Whether a {@code load()} logged {@link #loggedBelow}, rather than a {@code run()}. */
    private final boolean loggedBelowByLoad;
    /**
     * The suppressed exceptions the engine itself added (see {@link #addSuppressedByEngine}), by identity, or
     * {@code null} if it added none, or once this has been deserialized, when every suppressed exception is read as one
     * code outside the engine added.
     */
    private transient Throwable[] addedByEngine;

    /**
     * Creates the exception for a failure that belongs to no rule.
     *
     * @param message What failed
     * @param cause   Why, or {@code null}
     */
    ReportedFailure(String message, Throwable cause) {
        this(message, cause, null, null, false, null);
    }

    private ReportedFailure(String message, Throwable cause, String ruleName, ExpressionKind kind, boolean stopped,
                            Deadline passed) {
        super(message, cause, ruleName, kind);
        this.stopped = stopped;
        this.interrupted = stopped && passed == null;
        this.passed = passed;
        Failures.Below below = Failures.below(cause);
        this.innermostBelow = below.innermost();
        this.firstError = below.error();
        this.belowRecorded = true;
        this.loggedBelow = below.news() == null ? below.logged() : this;
        this.loggedBelowByLoad = below.news() == null && below.loggedByLoad();
    }

    /**
     * Creates the exception for a run that stopped because it was interrupted or passed its deadline.
     *
     * @param message  Where the run stopped
     * @param cause    An {@link InterruptedException} or a {@link java.util.concurrent.TimeoutException}
     * @param deadline The deadline the run passed, or {@code null} if it was interrupted
     * @return The exception, which belongs to no rule
     */
    static ReportedFailure stop(String message, Exception cause, Deadline deadline) {
        return new ReportedFailure(message, cause, null, null, true, deadline);
    }

    /**
     * Tells whether an exception is a run that stopped, because it was interrupted or passed its deadline, rather
     * than a failure.
     *
     * @param thrown What a run or a rule threw
     * @return {@code true} for a stop reported by the engine
     */
    static boolean isStop(Throwable thrown) {
        return thrown instanceof ReportedFailure failure && failure.stopped;
    }

    /**
     * Tells whether this is a run that stopped for the same reason: interrupted, or past the same deadline.
     *
     * @param other The deadline another run passed, or {@code null} for an interrupt
     * @return {@code true} if this run stopped, and for that reason. A stop read from a serialized form never matches
     *         another run's deadline, nor, in a form from before interrupts were recorded, an interrupt.
     */
    // The very same deadline: a run started from inside another inherits it, and one of its own is another object.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    boolean isStopFor(Deadline other) {
        return stopped && (other == null ? interrupted : other == passed);
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
        this(message, cause, ruleName, kind, false, null);
    }

    /**
     * Returns the innermost failure of a {@code run()} this one's cause chain holds, however many runs deep. Only
     * {@link Failures#below} asks, and only a failure that {@link #recorded()} it.
     *
     * @return That failure, or this one if its cause chain holds none, or if it was serialized before it recorded one
     */
    ReportedFailure innermost() {
        return innermostBelow == null ? this : innermostBelow;
    }

    /**
     * Returns the first {@link Error} in this failure's cause chain, however many runs deep, as
     * {@link Failures#below} found it when this was built.
     *
     * @return The error, or {@code null} if there is none
     */
    Error error() {
        return firstError;
    }

    /**
     * Returns the failure a nested run or load logged that names this failure's cause chain, however many runs deep,
     * as {@link Failures#below} found it when this was built. Only {@link Failures#below} asks, and only a failure
     * that {@link #recorded()} it.
     *
     * @return That failure, this one if an exception with a message of its own is wrapped around it, or
     *         {@link #innermost()} if it was serialized before it recorded one or its chain holds none
     */
    Throwable logged() {
        return loggedBelow != null ? loggedBelow : innermost();
    }

    /**
     * Tells whether a {@code load()} logged the failure {@link #logged()} returns, rather than a {@code run()}.
     *
     * @return {@code true} for a failure a nested {@code load()} logged
     */
    boolean loggedByLoad() {
        return loggedBelowByLoad;
    }

    /**
     * Adds a suppressed exception the engine keeps on this failure, such as a fatal {@link Error} a listener threw
     * while it was told of it, or what an expression threw before the run stopped, and records that the engine added
     * it, so the search for a fatal error in what was caught doesn't find it there again and take it for the failure's
     * own (see {@link Failures#fatalError}). A suppressed exception code outside the engine adds later, as a
     * {@code try}-with-resources around a nested run whose {@code close()} fails does, isn't recorded, and is read.
     *
     * @param suppressed The exception to keep
     */
    void addSuppressedByEngine(Throwable suppressed) {
        // Recorded first, so no thread sees it among the suppressed exceptions before it's known for the engine's.
        synchronized (this) {
            Throwable[] added = addedByEngine == null ? new Throwable[1]
                    : Arrays.copyOf(addedByEngine, addedByEngine.length + 1);
            added[added.length - 1] = suppressed;
            addedByEngine = added;
        }
        addSuppressed(suppressed);
    }

    /**
     * Tells whether the engine added this very exception to this failure's suppressed exceptions (see
     * {@link #addSuppressedByEngine}).
     *
     * @param suppressed One of its suppressed exceptions
     * @return {@code true} if the engine added it; {@code false} if code outside the engine did
     */
    // The very same instance is what the engine added; an equal one is another.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    boolean suppressedByEngine(Throwable suppressed) {
        Throwable[] added;
        synchronized (this) {
            added = addedByEngine;
        }
        if (added != null) {
            for (Throwable t : added) {
                if (t == suppressed) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tells whether this failure recorded its innermost failure and first error when it was built, as every one does
     * unless it was serialized before they were recorded.
     *
     * @return {@code true} if {@link #innermost()} and {@link #error()} answer for its cause chain
     */
    boolean recorded() {
        return belowRecorded;
    }
}
