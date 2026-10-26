package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.internal.Whitespace;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Checks an expression language's name: the builder checks each language it's given, and the engine each one it
 * finds with {@link java.util.ServiceLoader}. <b>Internal:</b> this class may change in any release. It's public only
 * so that {@code api.RulesEngineBuilder}, in another package, and {@link LanguageRegistry} share the rule, while each
 * says what's wrong in its own words, with its own exception.
 */
public final class LanguageNames {

    /** What is wrong with a language's name. */
    public enum Problem {
        /** The name is {@code null} or blank. */
        NULL_OR_BLANK,
        /** Another language has the name already. */
        TAKEN
    }

    private LanguageNames() {
    }

    /**
     * Checks a language's name against the names already taken. The caller reads the name once, checks it here, and
     * uses that name from then on, so a language whose {@code name()} changes between calls can't get past the check.
     *
     * @param name  The name the language returned
     * @param taken The languages already given or found, by name
     * @return What is wrong with the name, or {@code null} if nothing is
     */
    public static @Nullable Problem check(@Nullable String name, Map<String, ?> taken) {
        // Not String.isBlank(), which reads a JDK table the JVM builds on its first use for a name that starts with a
        // character above U+00FF; the builder checks a language before any engine is built (#1292).
        if (name == null || Whitespace.isBlank(name)) {
            return Problem.NULL_OR_BLANK;
        }
        return taken.containsKey(name) ? Problem.TAKEN : null;
    }
}
