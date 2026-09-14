package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

/**
 * Exception thrown when a rule fails to compile.
 */
public class RuleCompilationException extends UnrulyException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public RuleCompilationException(@Nullable String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause.
     */
    public RuleCompilationException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
