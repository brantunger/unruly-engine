package io.github.brantunger.unruly.api.exception;

import org.jspecify.annotations.Nullable;

/**
 * The base runtime exception for the Unruly engine.
 */
public class UnrulyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message The detail message
     */
    public UnrulyException(@Nullable String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause   The cause
     */
    public UnrulyException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
