package io.github.brantunger.unruly.test;

/**
 * Gives a test in another package, such as {@code core.KitOverflowCopyTest}, which also reaches {@code core.Failures},
 * the copy of the engine's overflow reader {@link KitFailures} keeps.
 */
public final class KitFailuresCopies {

    private KitFailuresCopies() {
    }

    /**
     * Tells whether a throwable is an overflow as {@link KitFailures#isOverflow} does.
     *
     * @param thrown The throwable
     * @return {@code true} if a {@link StackOverflowError} is in its cause chain with no {@link LinkageError} before it
     */
    public static boolean isOverflow(Throwable thrown) {
        return KitFailures.isOverflow(thrown);
    }
}
