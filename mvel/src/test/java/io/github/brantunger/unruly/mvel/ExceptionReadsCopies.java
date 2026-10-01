package io.github.brantunger.unruly.mvel;

import java.util.List;

/**
 * Gives a test in another package, such as {@code core.ExceptionReadsCopiesTest}, which also reaches
 * {@code core.Failures}, the copy of the engine's exception readers {@link ExceptionReads} keeps.
 */
public final class ExceptionReadsCopies {

    private ExceptionReadsCopies() {
    }

    /**
     * Reads an exception's message as {@link ExceptionReads#messageOf} does.
     *
     * @param e The exception
     * @return Its message, or {@code null} if it has none, or {@code (message unavailable: ...)} if it can't be read
     */
    public static String messageOf(Throwable e) {
        return ExceptionReads.messageOf(e);
    }

    /**
     * Finds the last exception in {@code e}'s cause chain as {@link ExceptionReads#rootCause} does.
     *
     * @param e The exception
     * @return The last link of its cause chain that could be read
     */
    public static Throwable rootCause(Throwable e) {
        return ExceptionReads.rootCause(e);
    }

    /**
     * Lists {@code e} and its causes as {@link ExceptionReads#causeChain} does.
     *
     * @param e The exception
     * @return {@code e} and the causes that could be read, in order
     */
    public static List<Throwable> causeChain(Throwable e) {
        return ExceptionReads.causeChain(e);
    }
}
