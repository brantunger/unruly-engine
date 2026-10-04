package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks a fact's name, before any language is asked about it: the builder checks each declared fact's, the engine
 * each declared fact's against the names its languages reserve, and a run each supplied fact's, as do the test kit's
 * evaluation and action contexts. Each throws its own exception. A run and the test kit's contexts reject a
 * {@code null} name with {@link #NULL_MESSAGE}, and they and the builder a blank one with {@link #BLANK_MESSAGE}; a
 * reserved name is rejected with {@link #reservedMessage}'s.
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
        /**
         * One of the engine's languages reserves the name, as {@link ExpressionLanguage#reservedFactNames()} says,
         * such as {@value ActionContext#OUTPUT_NAME}, which actions use for the output object by default.
         */
        RESERVED
    }

    /**
     * The message a fact named {@code null} is rejected with, by a run and by the test kit's evaluation and action
     * contexts.
     */
    static final String NULL_MESSAGE = "fact name must not be null";

    /**
     * The message a fact with a blank name is rejected with, by a run, by the builder for a declared fact, and by the
     * test kit's contexts.
     */
    static final String BLANK_MESSAGE = "fact name must not be blank";

    private FactNames() {
    }

    /**
     * Checks a fact's name.
     *
     * @param name     The fact's name
     * @param reserved The names the engine's languages reserve
     * @return What is wrong with the name, or {@code null} if nothing is
     */
    static @Nullable Problem check(@Nullable String name, Set<String> reserved) {
        if (name == null) {
            return Problem.NULL;
        }
        if (name.isBlank()) {
            return Problem.BLANK;
        }
        // Only now: an unmodifiable set throws NullPointerException when asked whether it holds null.
        return reserved.contains(name) ? Problem.RESERVED : null;
    }

    /**
     * Checks the names of the facts the test kit creates a context with, as a run checks the names of its facts, with
     * the run's message. No name is reserved here: the context has no engine, and so no languages to ask.
     *
     * @param facts The facts by name
     * @return {@code facts}
     * @throws IllegalArgumentException if a fact's name is {@code null} or blank
     */
    static Map<String, Object> requireRunNames(Map<String, Object> facts) {
        for (String name : facts.keySet()) {
            Problem problem = check(name, Set.of());
            // No name is reserved, so a name has no other problem.
            if (problem == Problem.NULL) {
                throw new IllegalArgumentException(NULL_MESSAGE);
            }
            if (problem == Problem.BLANK) {
                throw new IllegalArgumentException(BLANK_MESSAGE);
            }
        }
        return facts;
    }

    /**
     * Returns the fact names an engine's languages reserve, each with the language that reserves it, asking each
     * language once. Called when the engine is built, so a language that a rule list may first use only later, one
     * found with {@link java.util.ServiceLoader}, is asked too, without being prepared.
     *
     * @param languages The engine's languages, by name
     * @return The language that reserves each name, by name, the first by name where several do; unmodifiable, so a
     *         later change to the set a language returned changes nothing
     * @throws IllegalStateException if a language returns {@code null}, or a set holding {@code null}
     */
    static Map<String, String> reserved(Map<String, ExpressionLanguage> languages) {
        Map<String, String> reserved = new HashMap<>();
        // Sorted by name, so the language a message names, when several reserve the same name, is always the same.
        for (Map.Entry<String, ExpressionLanguage> language : new TreeMap<>(languages).entrySet()) {
            Set<String> names = language.getValue().reservedFactNames();
            if (names == null) {
                throw new IllegalStateException("The '" + Failures.quote(language.getKey())
                        + "' expression language returned null from reservedFactNames()");
            }
            for (String name : names) {
                if (name == null) {
                    throw new IllegalStateException("The '" + Failures.quote(language.getKey())
                            + "' expression language returned a null name from reservedFactNames()");
                }
                reserved.putIfAbsent(name, language.getKey());
            }
        }
        return Map.copyOf(reserved);
    }

    /**
     * Returns the message a fact named {@value ActionContext#OUTPUT_NAME} is rejected with, in the words the engine
     * used while it reserved that name for every language.
     *
     * @param declared Whether the fact was declared, rather than supplied to a run
     * @return The message
     */
    static String outputMessage(boolean declared) {
        return "'" + ActionContext.OUTPUT_NAME + "' is reserved for the output object and cannot be "
                + (declared ? "declared as a fact" : "used as a fact name");
    }

    /**
     * Returns the message a fact with a reserved name is rejected with: {@link #outputMessage(boolean)}'s for
     * {@value ActionContext#OUTPUT_NAME}, and one naming the language that reserves it for any other name.
     *
     * @param name     The fact's name, which is reserved
     * @param language The name of the language that reserves it
     * @param declared Whether the fact was declared, rather than supplied to a run
     * @return The message
     */
    static String reservedMessage(String name, String language, boolean declared) {
        if (ActionContext.OUTPUT_NAME.equals(name)) {
            return outputMessage(declared);
        }
        return "'" + Failures.quote(name) + "' is reserved by the '" + Failures.quote(language)
                + "' expression language and cannot be " + (declared ? "declared as a fact" : "used as a fact name");
    }
}
