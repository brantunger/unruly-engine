package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

/**
 * Exception thrown when a rule fails to compile.
 */
public class RuleCompilationException extends UnrulyException {
    private static final long serialVersionUID = 1L;

    /**
     * The name of the rule that failed to compile, or {@code null} if the failure belongs to no named rule.
     */
    private final @Nullable String ruleName;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public RuleCompilationException(@Nullable String message) {
        this(message, null, null);
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
        super(message, cause);
        this.ruleName = ruleName;
    }

    /**
     * Returns the name of the rule that failed, as {@link io.github.brantunger.unruly.api.Rule#getRuleName()} returns
     * it. Unlike the message, it isn't escaped or shortened, so it can be used to look the rule up.
     *
     * @return the rule's name, or {@code null} if the failure isn't about one rule (for example an expression language
     *         failed to create its compiler, or an element of the list is {@code null}) or the rule has no name
     */
    public @Nullable String getRuleName() {
        return ruleName;
    }
}
