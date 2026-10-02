package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * How the contract kit's checks report what a language threw: which of it the engine rethrows, how it's described in
 * a check's failure message, and how what closing a resource threw is attached to a check's failure.
 */
final class KitFailures {

    /** How many links of an exception's cause chain the engine reads first, when it looks for a fatal error. */
    static final int MAX_CAUSE_CHAIN_LENGTH = 100;

    /**
     * How many different exceptions, through causes and suppressed exceptions, the engine reads to find a fatal error
     * (see {@link #fatalError}); one past that many isn't found.
     */
    static final int MAX_EXCEPTIONS_READ = 10_000;

    private KitFailures() {
    }

    /**
     * Attaches what closing an engine, a compiler or a session threw to a check's failure, unless it is that failure
     * or already attached to it. A language that throws one cached {@link Error} from {@code close()}, or from a
     * condition and a {@code close()}, fails the check with it and then the close with it again, and
     * {@link Throwable#addSuppressed} would throw {@code "Self-suppression not permitted"} for the first. The engine
     * also rethrows a fatal error from close() itself, and {@code closing()} has attached what {@code engine.close()}
     * threw to the failure already, so one exception a language throws from every close, cached, is attached once.
     *
     * @param failure      The check's failure
     * @param closeFailure What closing threw
     */
    // By identity: the same instance is what's attached.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    static void attach(Throwable failure, Throwable closeFailure) {
        if (closeFailure != failure
                && Arrays.stream(failure.getSuppressed()).noneMatch(known -> known == closeFailure)) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Attaches what closing a language's compilers or sessions threw to a check's failure, each once, as
     * {@link #attach} does.
     *
     * @param failure       The check's failure
     * @param closeFailures What closing threw
     */
    static void suppressAll(Throwable failure, List<Throwable> closeFailures) {
        for (Throwable closeFailure : closeFailures) {
            attach(failure, closeFailure);
        }
    }

    /**
     * Whether the engine rethrows what a language threw, rather than counting it as the language's failure: whether
     * {@link #fatalError} finds a fatal error in it.
     */
    static boolean isFatal(Throwable thrown) {
        return fatalError(thrown) != null;
    }

    /**
     * Throws the fatal error what a language threw is or carries (see {@link #fatalError}), unchanged, as the engine
     * throws the one it finds, so that no check counts it as the language's answer.
     *
     * @param thrown What the language threw
     */
    static void rethrowIfFatal(Throwable thrown) {
        Error fatal = fatalError(thrown);
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Finds the fatal error in what a language threw, as the engine finds the one it rethrows: a
     * {@link VirtualMachineError} other than a {@link StackOverflowError} that is the throwable itself or one of its
     * causes, or failing that one suppressed on any of them, or among the causes and suppressed exceptions of those, at
     * any depth, as a {@code try}-with-resources whose {@code close()} hits an {@link OutOfMemoryError} while its body
     * throws leaves it. The kit keeps a copy of the engine's search, reading in the engine's order: the first
     * {@value #MAX_CAUSE_CHAIN_LENGTH} links of the cause chain, then the direct suppressed exceptions of each link,
     * from the top, before what any of them leads to, depth first. A {@code getCause()} that throws ends that chain.
     * Each exception is read once, and at most {@value #MAX_EXCEPTIONS_READ} are, so a fatal error past that many is
     * missed, as the engine misses it. Unlike the engine, it reads every suppressed exception, those a failure the
     * engine reported marks as its own ({@code ReportedFailure.addSuppressedByEngine}) too: the kit can't tell them
     * apart.
     *
     * @param thrown What the language threw
     * @return The first fatal error found, or {@code null} if there is none among the exceptions read
     */
    static @Nullable Error fatalError(Throwable thrown) {
        return new FatalSearch().find(thrown);
    }

    /** Whether one throwable, not what it carries, is an error the engine rethrows. */
    private static boolean isFatalItself(Throwable thrown) {
        return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError);
    }

    /** Reads an exception's cause, or {@code null} when {@code getCause()} throws, as the engine reads it. */
    private static @Nullable Throwable causeOf(Throwable thrown) {
        try {
            return thrown.getCause();
        } catch (Throwable unreadable) {
            // What a cause that can't be read is to the engine: none.
            return null;
        }
    }

    /**
     * One search of {@link #fatalError}: what it read, by identity, and the suppressed exceptions it read and has
     * still to go into, a list for each exception it went into, the next on top.
     */
    private static final class FatalSearch {
        private final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Deque<Iterator<Throwable>> deeper = new ArrayDeque<>();
        private @Nullable Error found;
        private boolean stopped;

        @Nullable Error find(Throwable thrown) {
            List<Throwable> chain = new ArrayList<>();
            for (Throwable link = thrown; link != null && chain.size() < MAX_CAUSE_CHAIN_LENGTH && read(link);
                    link = causeOf(link)) {
                chain.add(link);
            }
            deeper.push(readSuppressed(chain).iterator());
            while (!stopped && !deeper.isEmpty()) {
                Iterator<Throwable> next = deeper.peek();
                if (next.hasNext()) {
                    List<Throwable> into = goInto(next.next());
                    if (!into.isEmpty()) {
                        deeper.push(into.iterator());
                    }
                } else {
                    deeper.pop();
                }
            }
            return found;
        }

        /** Counts and checks an exception; {@code true} if it was read for the first time, and isn't fatal. */
        private boolean read(Throwable thrown) {
            if (seen.size() >= MAX_EXCEPTIONS_READ) {
                stopped = true;
                return false;
            }
            if (!seen.add(thrown)) {
                return false;
            }
            if (isFatalItself(thrown)) {
                found = (Error) thrown;
                stopped = true;
                return false;
            }
            return true;
        }

        /**
         * Reads the causes of an exception read already, then the direct suppressed exceptions of it and of each. One
         * with neither returns no list to go into.
         */
        private List<Throwable> goInto(Throwable thrown) {
            Throwable cause = causeOf(thrown);
            if (cause == null && thrown.getSuppressed().length == 0) {
                return List.of();
            }
            List<Throwable> chain = new ArrayList<>();
            chain.add(thrown);
            for (Throwable link = cause; link != null && read(link); link = causeOf(link)) {
                chain.add(link);
            }
            return readSuppressed(chain);
        }

        /** Reads the direct suppressed exceptions of each link, in order, returning those read for the first time. */
        private List<Throwable> readSuppressed(List<Throwable> chain) {
            List<Throwable> read = new ArrayList<>();
            for (int i = 0; i < chain.size() && !stopped; i++) {
                Throwable[] suppressed = chain.get(i).getSuppressed();
                for (int j = 0; j < suppressed.length && !stopped; j++) {
                    if (read(suppressed[j])) {
                        read.add(suppressed[j]);
                    }
                }
            }
            return read;
        }
    }

    /**
     * Returns an exception's message, or, when {@code getMessage()} throws, what {@link #describe} prints for it, so a
     * check's failure message can't throw.
     *
     * @param thrown The exception
     * @return Its message, or its class name followed by {@code (message unavailable: ...)}
     */
    static String message(Throwable thrown) {
        try {
            return String.valueOf(thrown.getMessage());
        } catch (Throwable unreadable) {
            // What describe() prints for an exception whose message can't be read.
            return describe(thrown);
        }
    }

    /**
     * Describes an object of the language's, such as what it threw, for a check's failure message: its
     * {@code toString()}, or, when that throws, its class name and a note that its text is unavailable, as the engine
     * describes an exception whose {@code getMessage()} throws. Whatever {@code toString()} throws, a fatal
     * {@link Error} too, only makes the text unavailable, so the check fails with its own message rather than with
     * that.
     *
     * @param object The object
     * @return Its {@code toString()}, or its class name followed by {@code (message unavailable: ...)}, naming the
     *         class of what {@code toString()} threw
     */
    static String describe(@Nullable Object object) {
        try {
            return String.valueOf(object);
        } catch (Throwable thrown) {
            // Only the class of what was thrown: its own message could be what throws.
            return Objects.requireNonNull(object).getClass().getName() + " (message unavailable: "
                    + thrown.getClass().getName() + ")";
        }
    }

    /**
     * Describes a failed run of {@code sessionClosedWhileAnotherRuns} by what happened in it: the nested run's
     * session is blamed only when the nested run started and ended as it should.
     *
     * @param failure       What the run threw
     * @param nested        Whether the run nested in it started
     * @param nestedFailure What the nested run threw, attached to the check's failure, or {@code null}
     * @return The check's failure, with the engine's message: the exception's class is the engine's internal one
     */
    static AssertionFailedError outerRunFailed(RuleExecutionException failure, boolean nested,
                                               @Nullable RuntimeException nestedFailure) {
        if (!nested) {
            return new AssertionFailedError("the run failed before a run could be nested in it: "
                    + failure.getMessage(), failure);
        }
        if (nestedFailure != null) {
            AssertionFailedError both = new AssertionFailedError("the run failed, and so did the run nested in it,"
                    + " which is attached: " + failure.getMessage(), failure);
            both.addSuppressed(nestedFailure);
            return both;
        }
        return new AssertionFailedError("the run failed after a run nested in it ended and its session was closed, so"
                + " a session's close(), or the nested run's session, broke what its compiler's other sessions share: "
                + failure.getMessage(), failure);
    }

    /**
     * Describes a failed run of one of the nested-run checks, whose condition or action had started a run inside
     * itself: a language that keeps a run's state per thread may find it replaced, or removed, by the nested run.
     *
     * <p>
     * A nested run that failed other than as the check made it fail is a second defect, and the failure says both
     * runs failed. One that failed as the check made it is what the check is about: the failure says the failed run
     * may have left the state behind, as a nested run that ended does.
     * </p>
     *
     * @param failure               What the run threw
     * @param where                 Where the nested run started, {@code "condition"} or {@code "action"}
     * @param nestedFailure         What the run started inside its condition or action threw, attached to the
     *                              check's failure, or {@code null}
     * @param nestedFailedAsPlanned Whether the nested run failed as the check made it fail
     * @return The check's failure, with the engine's message: the exception's class is the engine's internal one
     */
    static AssertionFailedError runAroundNestedFailed(RuleExecutionException failure, String where,
                                                      @Nullable RuntimeException nestedFailure,
                                                      boolean nestedFailedAsPlanned) {
        if (nestedFailure != null && !nestedFailedAsPlanned) {
            AssertionFailedError both = new AssertionFailedError("the run failed after a run started inside its "
                    + where + " failed too, which is attached: " + failure.getMessage(), failure);
            both.addSuppressed(nestedFailure);
            return both;
        }
        String nested = nestedFailure != null
                ? " failed, as the check meant it to, which is attached, so the failed run"
                : " ended, so the nested run";
        AssertionFailedError around = new AssertionFailedError("the run failed after a run started inside its " + where
                + nested + " may have replaced or removed state the " + where + " kept for its own run: "
                + failure.getMessage(), failure);
        if (nestedFailure != null) {
            around.addSuppressed(nestedFailure);
        }
        return around;
    }
}
