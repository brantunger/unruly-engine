package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Exception thrown when a rule fails to compile, or when several do.
 *
 * <p>
 * {@link io.github.brantunger.unruly.api.RulesEngine#load(java.util.List)} compiles every rule before it throws,
 * so one exception reports everything that failed: {@link #failures()} has one exception for each broken rule, for
 * a language that couldn't create its compiler and for each declared fact name the languages reject, and the name,
 * expression kind and issues of this exception are those of the first. A failure that isn't a rule's has no rule
 * name.
 * </p>
 */
public class RuleCompilationException extends UnrulyException {
    private static final long serialVersionUID = 1L;

    /**
     * The name of the rule that failed to compile, or {@code null} if the failure belongs to no named rule.
     */
    private final @Nullable String ruleName;

    /** Whether the rule's condition or its action failed, or {@code null} if the failure isn't about one of them. */
    private final @Nullable ExpressionKind expressionKind;

    /** Where and what the expression language found wrong, possibly nothing. */
    // The list is from List.copyOf, which serializes, though its declared type, List, isn't Serializable.
    @SuppressWarnings("serial")
    private final List<InvalidExpressionException.Issue> reportedIssues;

    /** Each rule's failure when several rules failed, or nothing when this exception is the only failure. */
    // The list is from List.copyOf, which serializes, though its declared type, List, isn't Serializable.
    @SuppressWarnings("serial")
    private final List<RuleCompilationException> ruleFailures;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public RuleCompilationException(@Nullable String message) {
        this(message, (Throwable) null, (String) null);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause.
     */
    public RuleCompilationException(@Nullable String message, @Nullable Throwable cause) {
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
    public RuleCompilationException(@Nullable String message, @Nullable Throwable cause, @Nullable String ruleName) {
        this(message, cause, ruleName, null, List.of());
    }

    /**
     * Constructs a new exception for one rule's condition or action.
     *
     * @param message        the detail message.
     * @param cause          the cause.
     * @param ruleName       the name of the rule that failed, or {@code null} if the rule has no name.
     * @param expressionKind whether the condition or the action failed, or {@code null} if neither did
     * @param issues         where and what the expression language found wrong; copied
     * @throws NullPointerException if {@code issues} or one of its elements is {@code null}
     */
    public RuleCompilationException(@Nullable String message, @Nullable Throwable cause, @Nullable String ruleName,
                                    @Nullable ExpressionKind expressionKind,
                                    List<InvalidExpressionException.Issue> issues) {
        super(message, cause);
        this.ruleName = ruleName;
        this.expressionKind = expressionKind;
        this.reportedIssues = List.copyOf(issues);
        this.ruleFailures = List.of();
    }

    /**
     * Constructs an exception for several failures while loading a rule list. Its rule name, expression kind and
     * issues are those of the first failure, which is also its cause.
     *
     * @param message  the detail message.
     * @param failures each failure, in the order they were found; copied
     * @throws IllegalArgumentException if {@code failures} is empty
     * @throws NullPointerException     if {@code failures} or one of its elements is {@code null}
     */
    public RuleCompilationException(@Nullable String message, List<RuleCompilationException> failures) {
        this(message, List.copyOf(failures), first(failures));
    }

    private RuleCompilationException(@Nullable String message, List<RuleCompilationException> failures,
                                     RuleCompilationException first) {
        super(message, first);
        this.ruleName = first.ruleName;
        this.expressionKind = first.expressionKind;
        this.reportedIssues = first.reportedIssues;
        this.ruleFailures = failures;
    }

    private static RuleCompilationException first(List<RuleCompilationException> failures) {
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("failures must not be empty");
        }
        return failures.get(0);
    }

    /**
     * Returns the name of the rule that failed, as {@link io.github.brantunger.unruly.api.Rule#getRuleName()} returns
     * it. Unlike the message, it isn't escaped or shortened, so it can be used to look the rule up.
     *
     * @return the rule's name, or {@code null} if the failure isn't about one rule (for example an expression language
     *         failed to create its compiler, or an element of the list is {@code null})
     */
    public @Nullable String getRuleName() {
        return ruleName;
    }

    /**
     * Returns whether the rule's condition or its action failed.
     *
     * @return the kind of expression, or {@code null} if the failure isn't about a condition or an action, for example
     *         a rule written in a language that isn't registered
     */
    public @Nullable ExpressionKind getExpressionKind() {
        return expressionKind;
    }

    /**
     * Returns where and what the expression language found wrong, when it said.
     *
     * @return the issues, possibly none; unmodifiable
     */
    public List<InvalidExpressionException.Issue> issues() {
        return reportedIssues;
    }

    /**
     * Returns every failure of the load, in the order they were found: each broken rule's, in priority order; a
     * language that couldn't create its compiler, in place of the first rule that needed it; and each declared fact
     * name the languages reject, last. Only a rule's failure has a {@link #getRuleName() rule name}.
     *
     * @return one exception for each failure, or only this exception if there was one; unmodifiable
     */
    public List<RuleCompilationException> failures() {
        return ruleFailures.isEmpty() ? List.of(this) : ruleFailures;
    }
}
