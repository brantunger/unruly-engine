package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * The compilers one rule list is compiled with: one for each expression language its rules use, created when a rule
 * first uses it.
 */
final class LanguageCompilers {

    private final Map<String, ExpressionLanguage> languages;
    private final BiFunction<String, ExpressionLanguage, ExpressionCompiler> newCompiler;
    private final Map<String, ExpressionCompiler> compilers = new LinkedHashMap<>();
    // The languages whose compiler couldn't be created. Each is asked once: the rules written in it are skipped, so
    // its failure is reported once rather than once for each of them.
    private final Set<String> failedLanguages = new HashSet<>();

    /**
     * Creates the compilers for one rule list.
     *
     * @param languages   The registered languages by name, as they were when the rule list was loaded
     * @param newCompiler Creates a language's compiler, given the name it is registered under. It throws instead of
     *                    returning {@code null}.
     */
    LanguageCompilers(Map<String, ExpressionLanguage> languages,
                      BiFunction<String, ExpressionLanguage, ExpressionCompiler> newCompiler) {
        this.languages = languages;
        this.newCompiler = newCompiler;
    }

    /**
     * Returns the compiler for a language, creating it the first time. A language that fails to create its compiler
     * is remembered as failed, and the caller doesn't ask for it again: see {@link #failed(String)}.
     *
     * @param name The language's name
     * @return The compiler, or {@code null} if no language with this name is registered
     * @throws RuleCompilationException if the language throws or returns {@code null}
     */
    ExpressionCompiler forLanguage(String name) {
        ExpressionLanguage language = languages.get(name);
        if (language == null) {
            return null;
        }
        try {
            return compilers.computeIfAbsent(name, key -> newCompiler.apply(key, language));
        } catch (RuleCompilationException e) {
            failedLanguages.add(name);
            throw e;
        }
    }

    /**
     * Tells whether a language's compiler couldn't be created, so that a rule written in it is skipped: the language's
     * failure was reported when it happened, and asking again would only repeat it.
     *
     * @param name The language's name
     * @return {@code true} if {@link #forLanguage(String)} threw for this language
     */
    boolean failed(String name) {
        return failedLanguages.contains(name);
    }

    /**
     * Returns the names of the registered languages, for an error message.
     *
     * @return The names, sorted
     */
    Set<String> languageNames() {
        return new TreeSet<>(languages.keySet());
    }

    /**
     * Returns the compilers the rules used, which every fact is checked against. A rule list without rules is
     * checked against the default language. Called only when no rule failed, so the default language hasn't been
     * asked before.
     *
     * @param defaultLanguage The name of the language a rule without one is written in
     * @return The compilers by language name, in the order the rules first used them
     * @throws RuleCompilationException if the list is empty and the default language can't create its compiler
     */
    Map<String, ExpressionCompiler> used(String defaultLanguage) {
        if (compilers.isEmpty()) {
            forLanguage(defaultLanguage);
        }
        return created();
    }

    /**
     * Returns the compilers created so far, which are closed if the rule list fails to load.
     *
     * @return The compilers by language name, in the order they were created
     */
    Map<String, ExpressionCompiler> created() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(compilers));
    }
}
