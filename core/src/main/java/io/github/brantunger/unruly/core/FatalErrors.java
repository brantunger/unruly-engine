package io.github.brantunger.unruly.core;

import org.jspecify.annotations.Nullable;

/**
 * The engine's search for a fatal {@link Error}, or an interrupt, in what was caught, for {@code api.language} to
 * publish: {@link FailurePolicy#fatalError} and {@link FailurePolicy#hasInterrupt}, the walk that
 * {@link FailurePolicy#keepInterruptStatus} makes, which read
 * {@link ReportedFailure} to skip a suppressed exception the engine itself added, so they can't leave this package.
 * <b>Internal:</b> this class may change in any release, and isn't part of the API. It's public only so that
 * {@code api.language.Throwables}, in another package, can call it.
 */
public final class FatalErrors {

    private FatalErrors() {
    }

    /**
     * Finds the fatal error in what was caught, as {@link FailurePolicy#fatalError} finds it.
     *
     * @param thrown What was caught, or {@code null}
     * @return The fatal error, or {@code null} if there is none among the exceptions read
     */
    public static @Nullable Error in(@Nullable Throwable thrown) {
        return FailurePolicy.fatalError(thrown);
    }

    /**
     * Tells whether what was caught was caused by an interrupt, or carries one, as {@link FailurePolicy#hasInterrupt}
     * tells it.
     *
     * @param thrown What was caught, or {@code null}
     * @return {@code true} if an {@link InterruptedException} is among the exceptions read
     */
    public static boolean isInterrupt(@Nullable Throwable thrown) {
        return FailurePolicy.hasInterrupt(thrown);
    }
}
