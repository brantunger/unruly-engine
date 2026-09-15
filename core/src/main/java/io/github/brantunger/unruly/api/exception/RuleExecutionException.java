package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

/**
 * Exception thrown when a rule fails to execute (either condition or action).
 */
public class RuleExecutionException extends UnrulyException {
    private static final long serialVersionUID = 1L;

    /**
     * The name of the rule that failed to run, or {@code null} if the failure belongs to no named rule.
     */
    private final @Nullable String ruleName;

    /** Whether the rule's condition or its action failed, or {@code null} if the failure isn't about one of them. */
    private final @Nullable ExpressionKind expressionKind;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public RuleExecutionException(@Nullable String message) {
        this(message, null, null);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause.
     */
    public RuleExecutionException(@Nullable String message, @Nullable Throwable cause) {
        this(message, cause, null);
    }

    /**
     * Constructs a new exception with the specified detail message, cause and the name of the rule that failed.
     *
     * @param message  the detail message.
     * @param cause    the cause.
     * @param ruleName the name of the rule that failed, or {@code null} if the failure isn't about one rule or the
     *                 rule has no name.
     */
    public RuleExecutionException(@Nullable String message, @Nullable Throwable cause, @Nullable String ruleName) {
        this(message, cause, ruleName, null);
    }

    /**
     * Constructs a new exception for one rule's condition or action.
     *
     * @param message        the detail message.
     * @param cause          the cause.
     * @param ruleName       the name of the rule that failed, or {@code null} if the failure isn't about one rule or
     *                       the rule has no name.
     * @param expressionKind whether the condition or the action failed, or {@code null} if neither did
     */
    public RuleExecutionException(@Nullable String message, @Nullable Throwable cause, @Nullable String ruleName,
                                  @Nullable ExpressionKind expressionKind) {
        super(message, cause);
        this.ruleName = ruleName;
        this.expressionKind = expressionKind;
    }

    /**
     * Returns the name of the rule that failed, as {@link io.github.brantunger.unruly.api.Rule#getRuleName()} returns
     * it. Unlike the message, it isn't escaped or shortened, so it can be used to look the rule up.
     *
     * @return the rule's name, or {@code null} if the failure isn't about one rule (for example the output supplier
     *         failed)
     */
    public @Nullable String getRuleName() {
        return ruleName;
    }

    /**
     * Returns whether the rule's condition or its action failed.
     *
     * @return the kind of expression, or {@code null} if the failure isn't about a condition or an action, for example
     *         a listener or the output supplier failed
     */
    public @Nullable ExpressionKind getExpressionKind() {
        return expressionKind;
    }
}
