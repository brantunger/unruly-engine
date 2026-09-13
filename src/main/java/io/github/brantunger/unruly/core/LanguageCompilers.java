package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The compilers one rule list is compiled with: one for each expression language its rules use, created when a rule
 * first uses it.
 */
final class LanguageCompilers {

    private final Map<String, ExpressionLanguage> languages;
    private final CompileContext context;
    private final Map<String, ExpressionCompiler> compilers = new LinkedHashMap<>();

    /**
     * Creates the compilers for one rule list.
     *
     * @param languages The registered languages by name, as they were when the rule list was loaded
     * @param context   The imports and class loader the rule list is compiled with
     */
    LanguageCompilers(Map<String, ExpressionLanguage> languages, CompileContext context) {
        this.languages = languages;
        this.context = context;
    }

    /**
     * Returns the compiler for a language, creating it the first time.
     *
     * @param name The language's name
     * @return The compiler, or {@code null} if no language with this name is registered
     */
    ExpressionCompiler forLanguage(String name) {
        ExpressionLanguage language = languages.get(name);
        return language != null ? compilers.computeIfAbsent(name, key -> language.newCompiler(context)) : null;
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
     * checked against the default language.
     *
     * @param defaultLanguage The name of the language a rule without one is written in
     * @return The compilers, in the order the rules first used them
     */
    List<ExpressionCompiler> used(String defaultLanguage) {
        if (compilers.isEmpty()) {
            forLanguage(defaultLanguage);
        }
        return List.copyOf(compilers.values());
    }
}
