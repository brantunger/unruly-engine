package io.github.brantunger.unruly.api.exception;

/**
 * The base runtime exception for the Unruly engine.
 */
public class UnrulyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public UnrulyException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause.
     */
    public UnrulyException(String message, Throwable cause) {
        super(message, cause);
    }
}
