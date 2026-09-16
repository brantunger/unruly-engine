package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;

/**
 * A {@link RuleExecutionException} an engine throws from {@code run()} once it has logged it and told its listeners.
 * When a {@code run()} started from a condition or action fails with one, the run around it doesn't log that failure
 * again: see {@link Failures#nestedRunFailure}. A {@code RuleExecutionException} a language or a rule throws itself
 * is never one, so it is logged and escaped like any other exception.
 */
final class ReportedFailure extends RuleExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a failure that belongs to no rule.
     *
     * @param message What failed
     * @param cause   Why, or {@code null}
     */
    ReportedFailure(String message, Throwable cause) {
        super(message, cause);
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
    }
}
