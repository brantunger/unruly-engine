package io.github.brantunger.unruly.api.exception;

/**
 * Exception thrown when a rule fails to execute (either condition or action).
 */
public class RuleExecutionException extends UnrulyException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public RuleExecutionException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause.
     */
    public RuleExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
