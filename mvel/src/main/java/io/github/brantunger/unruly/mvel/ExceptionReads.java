package io.github.brantunger.unruly.mvel;

import org.mvel2.MVEL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reads the message, causes and stack trace of an exception the module didn't create, such as one a class's static
 * initializer or the application's class loader threw, without letting what those accessors throw escape. None of
 * them is final, and a failure to read one says nothing about the failure being reported, which is still reported as
 * it would be.
 * {@code core.Failures} keeps the engine's copy of these readers: the mvel package may not use that one.
 */
final class ExceptionReads {

    /** The package of MVEL's classes, taken from MVEL itself so that a relocated copy is matched too. */
    static final String MVEL_PACKAGE = MVEL.class.getPackageName() + ".";

    // As core.Failures.MAX_CAUSE_CHAIN_LENGTH. CalledCodeFailures.MAX_DEPTH bounds a different walk.
    private static final int MAX_CAUSE_CHAIN_LENGTH = 100;

    private ExceptionReads() {
    }

    /**
     * Reads an exception's message. Whatever {@code getMessage()} throws, a fatal {@link Error} too, only makes the
     * message unavailable.
     *
     * @param e The exception
     * @return Its message, or {@code null} if it has none, or {@code (message unavailable: ...)}, naming the class of
     *         what {@code getMessage()} threw, if it can't be read
     */
    static String messageOf(Throwable e) {
        return read(e::getMessage, thrown -> "(message unavailable: " + thrown.getClass().getName() + ")");
    }

    /**
     * Finds the last exception in {@code e}'s cause chain (see {@link #causeChain}).
     *
     * @param e The exception
     * @return The last link of its cause chain that could be read, which is {@code e} if it has no cause
     */
    static Throwable rootCause(Throwable e) {
        List<Throwable> chain = causeChain(e);
        return chain.get(chain.size() - 1);
    }

    /**
     * Lists {@code e} and its causes, stopping if the chain loops back on itself, at a link whose {@code getCause()}
     * throws, or after {@value #MAX_CAUSE_CHAIN_LENGTH} links: a {@code getCause()} of its own that returns a new
     * exception every time would otherwise make a chain that never ends.
     *
     * @param e The exception
     * @return {@code e} and the causes that could be read, in order
     */
    static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && chain.size() < MAX_CAUSE_CHAIN_LENGTH && seen.add(t); t = causeOf(t)) {
            chain.add(t);
        }
        return chain;
    }

    /**
     * Reads an exception's cause as {@link #messageOf} reads its message.
     *
     * @param e The exception
     * @return Its cause, or {@code null} if it has none or {@code getCause()} throws
     */
    private static Throwable causeOf(Throwable e) {
        return read(e::getCause, thrown -> null);
    }

    /**
     * Reads an exception's stack trace as {@link #messageOf} reads its message.
     *
     * @param e The exception
     * @return Its stack trace, which is empty if {@code getStackTrace()} throws or returns {@code null}
     */
    static StackTraceElement[] stackTraceOf(Throwable e) {
        StackTraceElement[] frames = read(e::getStackTrace, thrown -> null);
        return frames == null ? new StackTraceElement[0] : frames;
    }

    /**
     * Tells whether an exception was created in a class of a package, going by the top frame of its stack trace.
     *
     * @param e             The exception
     * @param packagePrefix The package's name, ending in a dot
     * @return {@code false} if the top frame is in another package, or the stack trace is empty or can't be read
     */
    static boolean thrownFrom(Throwable e, String packagePrefix) {
        StackTraceElement[] frames = stackTraceOf(e);
        return frames.length > 0 && frames[0].getClassName().startsWith(packagePrefix);
    }

    /**
     * Calls one of an exception's accessors without letting anything it throws escape, a fatal {@link Error} too.
     *
     * @param accessor The accessor
     * @param ifThrown What to return instead, from what the accessor threw
     * @param <T>      What the accessor returns
     * @return What the accessor returned, or what {@code ifThrown} makes of what it threw
     */
    private static <T> T read(Supplier<T> accessor, Function<Throwable, T> ifThrown) {
        try {
            return accessor.get();
        } catch (Throwable thrown) {
            return ifThrown.apply(thrown);
        }
    }
}
