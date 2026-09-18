package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@code run()} when a run fails: because a rule's condition or action failed, or because something outside
 * any rule did.
 *
 * <p>
 * {@link #getRuleName()} and {@link #getExpressionKind()} name the rule and the expression when a condition or action
 * threw, a condition evaluated to something other than a {@link Boolean}, an action returned {@code null}, or a
 * property an action returned couldn't be set on the output. A fatal {@link Error} a listener's
 * {@code beforeEvaluate} or {@code beforeExecute} callback threw names the rule but no expression. Both are
 * {@code null} for a failure that belongs to no rule:
 * </p>
 *
 * <ul>
 *   <li>the run was interrupted, or passed its deadline, while it waited for a compiled copy of the rules;</li>
 *   <li>the run was stopped between rules, or when a condition or action returned or threw once the run had to
 *   stop, because its thread was interrupted or it passed its deadline; the cause is then an
 *   {@link InterruptedException} or a {@link java.util.concurrent.TimeoutException};</li>
 *   <li>more than one rule matched on a unique-match engine, which the message names;</li>
 *   <li>the output supplier threw or returned {@code null};</li>
 *   <li>a language threw or returned {@code null} when it created a session for the run;</li>
 *   <li>a fatal {@link Error} left the run from the output supplier, from a language's check of a fact name, or
 *   from a listener's {@code beforeRun}, {@code afterEvaluate} or {@code afterExecute} callback: then {@code run()}
 *   rethrows the error itself, and this exception, which carries it, goes to
 *   {@link io.github.brantunger.unruly.api.RuleListener#onRunError}. One from {@code afterRun} is rethrown with no
 *   exception and no callback.</li>
 * </ul>
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
     * @param message The detail message
     */
    public RuleExecutionException(@Nullable String message) {
        this(message, null, null);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause   The cause
     */
    public RuleExecutionException(@Nullable String message, @Nullable Throwable cause) {
        this(message, cause, null);
    }

    /**
     * Constructs a new exception with the specified detail message, cause and the name of the rule that failed.
     *
     * @param message  The detail message
     * @param cause    The cause
     * @param ruleName The name of the rule that failed, or {@code null} if the failure isn't about one rule or the
     *                 rule has no name
     */
    public RuleExecutionException(@Nullable String message, @Nullable Throwable cause, @Nullable String ruleName) {
        this(message, cause, ruleName, null);
    }

    /**
     * Constructs a new exception for one rule's condition or action.
     *
     * @param message        The detail message
     * @param cause          The cause
     * @param ruleName       The name of the rule that failed, or {@code null} if the failure isn't about one rule or
     *                       the rule has no name
     * @param expressionKind Whether the condition or the action failed, or {@code null} if neither did
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
     * @return The rule's name, or {@code null} if the failure isn't about one rule (for example the output supplier
     *         failed, or the run was stopped)
     */
    public @Nullable String getRuleName() {
        return ruleName;
    }

    /**
     * Returns whether the rule's condition or its action failed.
     *
     * @return The kind of expression, or {@code null} if the failure isn't about a condition or an action, for example
     *         a listener or the output supplier failed
     */
    public @Nullable ExpressionKind getExpressionKind() {
        return expressionKind;
    }
}
