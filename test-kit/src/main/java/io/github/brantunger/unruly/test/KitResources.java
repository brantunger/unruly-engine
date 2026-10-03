package io.github.brantunger.unruly.test;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * How the contract kit's checks close what they create: an engine, a compiler or a session, however the check ends,
 * and the workers a check runs its rules on.
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
     * Runs a check with an engine, a compiler or a session, and then closes it, however the check ends, as
     * try-with-resources would. A check builds its engine before it loads any rules, so a failed {@code load()} still
     * leaves the engine to be closed. What closing throws is attached to the check's own failure, unless it is that
     * failure or already attached to it (see {@link KitFailures#attach}): try-with-resources would report
     * {@code "Self-suppression not permitted"} instead.
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
                resource.close();
            } catch (Throwable closeFailure) {
                KitFailures.attach(failure, closeFailure);
            }
            throw failure;
        }
        resource.close();
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
