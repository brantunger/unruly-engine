package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ActionContext;
import org.jspecify.annotations.Nullable;

/**
 * Checks a fact's name, before any language is asked about it: the builder checks each declared fact's, and a run each
 * supplied fact's. Each says what's wrong in its own words, with its own exception.
 */
final class FactNames {

    /** What is wrong with a fact's name. */
    enum Problem {
        /** The name is {@code null}. */
        NULL,
        /**
         * The name is blank, as {@link String#isBlank()} says: empty, or only characters
         * {@link Character#isWhitespace(int)} accepts. So a name of a no-break space ({@code U+00A0}) or a zero-width
         * space ({@code U+200B}) isn't blank, and is each language's to check.
         */
        BLANK,
        /** The name is {@value ActionContext#OUTPUT_NAME}, which actions use for the output object. */
        OUTPUT
    }

    private FactNames() {
    }

    /**
     * Checks a fact's name.
     *
     * @param name The fact's name
     * @return What is wrong with the name, or {@code null} if nothing is
     */
    static @Nullable Problem check(@Nullable String name) {
        if (name == null) {
            return Problem.NULL;
        }
        if (name.isBlank()) {
            return Problem.BLANK;
        }
        return ActionContext.OUTPUT_NAME.equals(name) ? Problem.OUTPUT : null;
    }
}
