package io.github.brantunger.unruly.mvel;

/**
 * Gives a test in another package, such as {@code core.TruncateCopiesTest}, which also reaches {@code core.Failures},
 * the copies of the engine's text helpers and limit {@link FactNames} keeps.
 */
public final class FactNamesCopies {

    /** The longest message about an expression MVEL rejected, as {@link FactNames#MAX_DESCRIPTION_LENGTH} is. */
    public static final int MAX_DESCRIPTION_LENGTH = FactNames.MAX_DESCRIPTION_LENGTH;

    private FactNamesCopies() {
    }

    /**
     * Shortens text as {@link FactNames#truncate} does.
     *
     * @param text The text
     * @return The text, shortened if it was longer
     */
    public static String truncate(String text) {
        return FactNames.truncate(text);
    }
}
