package io.github.brantunger.unruly.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reads what was thrown, without letting what its accessors throw escape, and tells a fatal {@link Error} and a stack
 * overflow from the rest: the message, the cause and the cause chain of an exception the engine didn't create, whose
 * {@code getMessage()} and {@code getCause()} aren't final and can throw. Each accessor is called in a {@code try} of
 * its own rather than through a lambda or method reference, so the JVM's first failure links no call site, which could
 * overflow deep in a stack (#1093), and {@link #isFatal} and {@link #isOverflow} allocate nothing, so the engine's
 * last-resort code, which runs when handling a failure has itself failed, can ask them. {@code api.language.Throwables}
 * publishes them for expression languages and the test kit, which read what they catch the way the engine does rather
 * than keeping a copy that could drift. <b>Internal:</b> this class may change in any release, and isn't part of the
 * API: its package isn't exported, and the {@code api} and {@code core} packages share it.
 */
public final class Causes {

    /** How many links of an exception's cause chain the engine reads; see {@link #causeChain}. */
    public static final int MAX_CAUSE_CHAIN_LENGTH = 100;

    private Causes() {
    }

    /**
     * Tells whether {@code thrown} is an {@link Error} the engine must not absorb, because the JVM itself is failing
     * and no message about a rule would help: a {@link VirtualMachineError} other than {@link StackOverflowError},
     * such as {@link OutOfMemoryError}, {@link InternalError} or {@link UnknownError}, but not an
     * {@link InternalError} that is an overflow (see {@link #isOverflow}).
     *
     * <p>
     * Every other error comes from the code being run, so it's handled like an exception and reported with the name of
     * the rule it came from:
     * </p>
     * <ul>
     *     <li>{@link StackOverflowError} (runaway recursion), which is itself a {@code VirtualMachineError} and so is
     *     excluded here on purpose;</li>
     *     <li>an {@link InternalError} with a {@link StackOverflowError} in its cause chain and no
     *     {@link LinkageError} before it, as the JDK wraps an overflow it hits defining a hidden class deep in a stack
     *     (#1141): the code being run ran out of stack, which more room fixes;</li>
     *     <li>{@link AssertionError} ({@code assert} in a rule, a listener or a language);</li>
     *     <li>every {@link LinkageError} — {@link NoClassDefFoundError}, {@link IllegalAccessError},
     *     {@link IncompatibleClassChangeError}, {@link ExceptionInInitializerError}, {@link VerifyError} and the rest —
     *     which means a class a rule uses is missing or can't be read: a configuration problem in one rule, not a
     *     failing JVM;</li>
     *     <li>any other {@link Error}, such as {@link java.io.IOError} or
     *     {@link java.util.ServiceConfigurationError}.</li>
     * </ul>
     *
     * <p>
     * It allocates nothing, and is the one check the engine's last-resort code makes, where handling a failure has
     * itself failed, as it can deep in a stack or with no memory left: ending a run (see {@code core.Failures}'
     * {@code Teardown}), closing a run's values and refusing one whose init ended the run ask it for what they caught,
     * with instanceof checks and assignments around it. Only an {@link InternalError} has its chain read, as
     * {@link #isOverflow} reads it, so the call takes one frame more than the checks themselves would (#1164).
     * </p>
     *
     * @param thrown One throwable from a cause chain, or {@code null}, which isn't fatal
     * @return {@code true} if {@code thrown} must be rethrown unchanged
     */
    public static boolean isFatal(Throwable thrown) {
        // StackOverflowError is a VirtualMachineError, so excluding it needs its own check. Only an InternalError has
        // its chain read, with nothing allocated for it.
        return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError)
                && !(thrown instanceof InternalError && isOverflow(thrown));
    }

    /**
     * Tells whether {@code e} is a {@link StackOverflowError}, or what was thrown in place of one: walking its cause
     * chain (see {@link #causeChain}) from the top, a {@link StackOverflowError} comes before any {@link LinkageError}.
     * A {@link NoClassDefFoundError} or an {@link ExceptionInInitializerError} above the overflow says a class was left
     * unusable, by an overflow in its static initializer, maybe in an earlier load, and no change to the rule or the
     * stack's depth helps, so that isn't an overflow, as JDK 25 and 26 make one with the overflow as its cause (#1116).
     * An {@link InternalError} around one, as the JDK wraps an overflow it hits defining a hidden class, is (#1141).
     * The chain is read link by link, with nothing allocated, so the engine's last-resort checks, which run when
     * handling a failure has itself failed, as allocating can, can ask it too: up to {@value #MAX_CAUSE_CHAIN_LENGTH}
     * links, which may repeat in a chain that loops back on itself, so it answers as {@link #causeChain}'s links read
     * in order would.
     *
     * @param e The exception, or {@code null}, which isn't an overflow
     * @return {@code true} if a {@link StackOverflowError} is in its cause chain with no {@link LinkageError} before it
     */
    public static boolean isOverflow(Throwable e) {
        Throwable link = e;
        for (int read = 0; link != null && read < MAX_CAUSE_CHAIN_LENGTH; read++) {
            if (link instanceof LinkageError) {
                return false;
            }
            if (link instanceof StackOverflowError) {
                return true;
            }
            link = causeOf(link);
        }
        return false;
    }

    /**
     * Lists {@code e} and its causes, stopping if the chain loops back on itself, at a link whose {@code getCause()}
     * throws (see {@link #causeOf}), or after {@value #MAX_CAUSE_CHAIN_LENGTH} links: a {@code getCause()} of its own
     * that returns a new exception every time would otherwise make a chain that never ends.
     *
     * @param e The exception, or {@code null}, whose chain is empty
     * @return A new list of {@code e} and the causes that could be read, in order
     */
    public static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && chain.size() < MAX_CAUSE_CHAIN_LENGTH && seen.add(t); t = causeOf(t)) {
            chain.add(t);
        }
        return chain;
    }

    /**
     * Finds the last exception in {@code e}'s cause chain (see {@link #causeChain}).
     *
     * @param e The exception
     * @return The last link of its cause chain that could be read, which is {@code e} if it has no cause
     */
    public static Throwable rootCause(Throwable e) {
        List<Throwable> chain = causeChain(e);
        return chain.get(chain.size() - 1);
    }

    /**
     * Reads an exception's cause as {@link #messageOf} reads its message: {@code getCause()} isn't final either.
     *
     * @param e The exception
     * @return Its cause, or {@code null} if it has none or {@code getCause()} throws
     */
    public static Throwable causeOf(Throwable e) {
        try {
            return e.getCause();
        } catch (Throwable thrown) {
            return null;
        }
    }

    /**
     * Reads an exception's message without letting a failure to read it escape. The engine reads the message of what
     * rules, listeners and languages throw to describe it, and a {@code getMessage()} of their own can throw, such as
     * one built from a field that is {@code null}; a failure the engine was handling would then end as that one.
     * Whatever {@code getMessage()} throws, a fatal {@link Error} too, only makes the message unavailable: what an
     * accessor of an exception the engine didn't create throws says nothing about the failure the engine is handling,
     * which is still handled as it would be, its own fatal errors included. {@link #causeOf} and the other accessors
     * of {@code core.Failures} read the same way, each in a {@code try} of its own rather than through a lambda or
     * method reference, so the JVM's first failure links no call site, which could overflow deep in a stack (#1093).
     *
     * @param e The exception
     * @return Its message, or {@code null} if it has none, or {@code (message unavailable: ...)}, naming the class of
     *         what {@code getMessage()} threw, if it can't be read
     */
    public static String messageOf(Throwable e) {
        try {
            return e.getMessage();
        } catch (Throwable thrown) {
            return unavailable("", thrown);
        }
    }

    /**
     * Makes the note that an exception's text can't be read, naming only the class of what reading it threw: that
     * one's own message could be what throws.
     *
     * @param prefix What goes before the note
     * @param thrown What reading the text threw
     * @return {@code prefix} and the note
     */
    public static String unavailable(String prefix, Throwable thrown) {
        return prefix + "(message unavailable: " + thrown.getClass().getName() + ")";
    }
}
