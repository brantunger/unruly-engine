package io.github.brantunger.unruly.api.language;

import org.jspecify.annotations.Nullable;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;

/**
 * The base runtime exception for the Unruly engine.
 *
 * <p>
 * It's {@link java.io.Serializable}, as every exception is. Until 3.0.0 this class and its subclasses were in the
 * package {@code io.github.brantunger.unruly.api.exception}, so a subclass that has the same name on both versions,
 * such as an application's own, would be read from a form unruly-engine 2.x serialized with no error and without
 * what the engine's classes hold, such as the rule's name. Reading one throws {@link InvalidObjectException}
 * instead, unless the subclass is {@link java.io.Externalizable}, which reads its own state.
 * </p>
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

    /**
     * Refuses a serialized form that has no data for this class, which is one unruly-engine 2.x wrote: its base
     * exception was {@code io.github.brantunger.unruly.api.exception.UnrulyException}. Java serialization calls this
     * for a class the object's hierarchy has here and the stream doesn't, and would otherwise leave every field of that
     * class and of the engine's subclasses of it at its default.
     *
     * @throws ObjectStreamException always, an {@link InvalidObjectException}
     */
    private void readObjectNoData() throws ObjectStreamException {
        throw new InvalidObjectException("The serialized form holds no " + UnrulyException.class.getName()
                + ", as one written by unruly-engine 2.x doesn't: unruly-engine 3 can't read an exception that 2.x"
                + " serialized");
    }
}
