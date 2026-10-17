package io.github.brantunger.unruly.test;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * How the contract kit's checks close what they create: an engine, a compiler or a session, however the check ends,
 * and the workers a check runs its rules on. And how they keep the thread they run on from staying interrupted once
 * the engine has put back an interrupt that made a {@code close()} fail, whether at the check's end or inside it.
 */
final class KitResources {

    private KitResources() {
    }

    /**
     * What a check does with an engine, a compiler or a session that it closes afterwards.
     *
     * @param <T> The resource's type
     */
    @FunctionalInterface
    interface ResourceCheck<T> {
        void accept(T resource) throws Exception;
    }

    /**
     * A step of a check that closes sessions or compilers, so the engine may put an interrupt back on the check's
     * thread as it runs: a {@code close()}, a reload, or a run nested in the check's run, whose sessions are closed as
     * it ends.
     *
     * @param <X> What the step throws
     */
    @FunctionalInterface
    interface ClosingStep<X extends Exception> {
        void run() throws X;
    }

    /**
     * Runs a check with an engine, a compiler or a session, and then closes it, however the check ends, as
     * try-with-resources would. A check builds its engine before it loads any rules, so a failed {@code load()} still
     * leaves the engine to be closed. What closing throws is attached to the check's own failure, unless it is that
     * failure or already attached to it (see {@link KitFailures#attach}): try-with-resources would report
     * {@code "Self-suppression not permitted"} instead. An interrupt that closing puts back, as the engine does when a
     * session's or a compiler's {@code close()} fails with an {@link InterruptedException} in its cause, is cleared, so
     * the checks that run on this thread next aren't interrupted; a thread that was interrupted before closing stays
     * interrupted (see {@link #keepingInterrupt}).
     *
     * @param resource The engine, with no rules loaded yet, or the compiler or session
     * @param check    What the check does with it
     * @param <T>      The resource's type
     * @throws Exception What the check throws, or, when the check passes, what closing the resource throws
     */
    // Any Throwable, as try-with-resources closes on any.
    static <T extends AutoCloseable> void closing(T resource, ResourceCheck<T> check) throws Exception {
        try {
            check.accept(resource);
        } catch (Throwable failure) {
            try {
                keepingInterrupt(resource::close);
            } catch (Throwable closeFailure) {
                KitFailures.attach(failure, closeFailure);
            }
            throw failure;
        }
        keepingInterrupt(resource::close);
    }

    /**
     * Runs a step that closes sessions or compilers, and clears any interrupt the engine puts back during it, unless
     * the thread was interrupted before the step: the one a {@code close()} that fails with an
     * {@link InterruptedException} in its cause leaves, or one a language's compile, {@code newSession()} or
     * {@code warmUp()} reports as a reload makes a copy of the rules. That interrupt belongs to this check's engine,
     * not to the checks that run on this thread after it. The close at a check's end ({@link #closing}) is one such
     * step (#1120); so are a reload that closes the replaced copy's sessions and compiler, and an explicit
     * {@code engine.close()}, inside a check (#1223), and a {@code load()} a check expects to fail, which closes the
     * compilers its compile created. A step that runs rules uses {@link #runKeepingInterrupt}.
     *
     * @param step The step
     * @param <X>  What the step throws
     * @throws X What the step throws
     */
    // Accepted: an interrupt from outside that arrives during the step, on a thread that wasn't interrupted before, is
    // cleared too, as is one a reload's compile or warm-up reports; the check still fails with what load() threw.
    static <X extends Exception> void keepingInterrupt(ClosingStep<X> step) throws X {
        boolean interruptedBefore = Thread.currentThread().isInterrupted();
        try {
            step.run();
        } finally {
            if (!interruptedBefore) {
                Thread.interrupted();
            }
        }
    }

    /**
     * Runs a step that runs rules and closes sessions as it does, a run nested in a check's run whose extra copy of the
     * rules the engine closes as soon as it ends, as {@link #keepingInterrupt} does, except that an interrupt that
     * stopped the run itself is kept: the engine reports the stop with an {@link InterruptedException} as the cause of
     * the run's failure, and sets the interrupt again as the run returns, so a check that is interrupted still reports
     * it (#534). Only the interrupt a session's {@code close()} put back as the run left, whether it ended or failed
     * for another reason, is cleared.
     *
     * @param step The step
     * @param <X>  What the step throws
     * @throws X What the step throws
     */
    static <X extends Exception> void runKeepingInterrupt(ClosingStep<X> step) throws X {
        try {
            keepingInterrupt(step);
        } catch (Throwable failure) {
            if (KitFailures.isInterrupt(failure)) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        }
    }

    /**
     * Stops a check's workers before its engine is closed: interrupts them, which also stops workers that a broken
     * language leaves running, and waits a while for them to end, so that none still holds a copy of the rules when
     * the engine closes it. On a thread that is already interrupted, the wait is skipped and the thread stays
     * interrupted; a copy a worker gives back later is closed by the closed engine, then or when the last worker
     * leaves.
     *
     * @param workers The workers
     */
    static void stop(ExecutorService workers) {
        workers.shutdownNow();
        // Not close(), which waits for as long as a broken language keeps a worker running. A worker still running
        // after this gives its copy back to the closed engine, which closes the copy then.
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Interrupted before or while it waits, as a JUnit timeout or an interrupted run leaves the thread. The
            // check's own failure stays the one reported, and the thread stays interrupted.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes a session or a compiler, ignoring what its {@code close()} throws that the engine only logs. A fatal
     * {@link Error} it throws or carries (see {@link KitFailures#fatalError}) is thrown on.
     *
     * <p>
     * {@code evaluateAgreesWithDetail} also wraps the end of each of its runs in one: a {@code close()} that calls
     * {@link LanguageTestContexts#endRun}, as the engine ends a run. So a value the language kept for the run with
     * {@code runScopedClosing}, whose {@code close()} throws what the engine only logs, doesn't fail the check, and
     * one that throws a fatal error fails it with that error, as the engine throws it from {@code run()}, a fatal error
     * suppressed on another value's failure too.
     * </p>
     *
     * @param resource The session or compiler, or the end of a run
     * @param <T>      Its type
     */
    record ClosedQuietly<T extends AutoCloseable>(T resource) implements AutoCloseable {

        // Anything but a fatal Error, thrown or carried, a checked exception thrown sneakily and a StackOverflowError
        // included: the engine logs any Exception or Error at WARN, and rethrows the fatal error one is or carries.
        @Override
        public void close() {
            try {
                resource.close();
            } catch (Throwable e) {
                // Logged by the engine, not thrown. Whether it throws is the session and compiler checks' question,
                // not this one's; a run's values have no check of their own.
                KitFailures.rethrowIfFatal(e);
            }
        }

        // The record's own equals, hashCode and toString, written out so that none links through ObjectMethods,
        // which can fail for good when first called deep in the stack (#996). equals compares the components last
        // first, as ObjectMethods does.
        @Override
        public final boolean equals(@Nullable Object other) {
            return this == other || other instanceof ClosedQuietly<?> that && Objects.equals(resource, that.resource);
        }

        @Override
        public final int hashCode() {
            return Objects.hashCode(resource);
        }

        @Override
        public final String toString() {
            return "ClosedQuietly[resource=" + resource + "]";
        }
    }
}
