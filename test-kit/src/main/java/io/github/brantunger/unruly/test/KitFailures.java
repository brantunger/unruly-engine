package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * How the contract kit's checks report what a language threw: which of it the engine rethrows, how it's described in
 * a check's failure message, and how what closing a resource threw is attached to a check's failure.
 */
final class KitFailures {

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
     * Whether the engine rethrows what a language's {@code close()} threw, rather than logging it: a
     * {@link VirtualMachineError} other than a {@link StackOverflowError}, as the engine decides.
     */
    static boolean isFatal(Throwable thrown) {
        return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError);
    }

    /**
     * Throws what a language threw on, unchanged, when it's an error the engine rethrows too (see {@link #isFatal}),
     * so that no check counts it as the language's answer.
     *
     * @param thrown What the language threw
     */
    static void rethrowIfFatal(Throwable thrown) {
        if (isFatal(thrown)) {
            throw (VirtualMachineError) thrown;
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
