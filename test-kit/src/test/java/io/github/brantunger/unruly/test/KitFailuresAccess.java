package io.github.brantunger.unruly.test;

import org.jspecify.annotations.Nullable;

/**
 * Gives a test in another package, such as {@code core.KitEngineAddedTest}, which also reaches what only the engine
 * makes, the kit's search for a fatal error or an interrupt in what a language or a run threw.
 */
public final class KitFailuresAccess {

    private KitFailuresAccess() {
    }

    /**
     * Finds the fatal error as {@link KitFailures#fatalError} does.
     *
     * @param thrown What was thrown
     * @return The fatal error found, or {@code null}
     */
    public static @Nullable Error fatalError(Throwable thrown) {
        return KitFailures.fatalError(thrown);
    }

    /**
     * Tells whether the kit rethrows what was thrown as {@link KitFailures#isFatal} does.
     *
     * @param thrown What was thrown
     * @return {@code true} if a fatal error is found in it
     */
    public static boolean isFatal(Throwable thrown) {
        return KitFailures.isFatal(thrown);
    }

    /**
     * Tells whether a run was interrupted as {@link KitFailures#isInterrupt} does.
     *
     * @param thrown What the run threw
     * @return {@code true} if an {@link InterruptedException} is found in it
     */
    public static boolean isInterrupt(Throwable thrown) {
        return KitFailures.isInterrupt(thrown);
    }
}
