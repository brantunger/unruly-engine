package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

/**
 * The expression languages an engine compiles rules with, and its default language, fixed when the engine is built:
 * the languages given to the builder, or else those found with {@link ServiceLoader}, such as MVEL. The engine refers
 * to no language directly, and a language creates nothing until rules are loaded.
 *
 * @param languages       The languages by name
 * @param defaultLanguage The name of the language of a rule whose language is {@code null}
 */
record LanguageRegistry(Map<String, ExpressionLanguage> languages, String defaultLanguage) {

    /**
     * Resolves an engine's languages and its default language.
     *
     * @param given       The languages given to the builder, whose names the builder checked, or none
     * @param defaultName The default language named on the builder, or {@code null}
     * @param loader      A class loader that may see languages this library's loader can't: the building thread's
     *                    context class loader
     * @return The languages and the default
     * @throws IllegalStateException if there is no language; if there are several and {@code defaultName} is
     *                               {@code null}; if {@code defaultName} isn't one of them; or if a found language's
     *                               name is {@code null} or blank, or two different found languages have the same name.
     *                               Anything {@link ServiceLoader} or a language throws while it is found, such as a
     *                               {@link java.util.ServiceConfigurationError}, is thrown unchanged.
     */
    static LanguageRegistry resolve(List<ExpressionLanguage> given, String defaultName, ClassLoader loader) {
        return resolve(given, defaultName, loader == ImportResolver.LIBRARY_CLASS_LOADER
                ? List.of(loader)
                : List.of(ImportResolver.LIBRARY_CLASS_LOADER, loader));
    }

    /**
     * Resolves an engine's languages and its default language, finding languages with the given class loaders.
     *
     * @param given       The languages given to the builder, or none
     * @param defaultName The default language named on the builder, or {@code null}
     * @param loaders     The class loaders to find languages with, in order, if none are given
     * @return The languages and the default
     * @throws IllegalStateException as {@link #resolve(List, String, ClassLoader)} describes
     */
    static LanguageRegistry resolve(List<ExpressionLanguage> given, String defaultName, List<ClassLoader> loaders) {
        Map<String, ExpressionLanguage> languages = new HashMap<>();
        if (given.isEmpty()) {
            Set<Class<?>> found = new HashSet<>();
            for (ClassLoader loader : loaders) {
                discover(loader, languages, found);
            }
        } else {
            given.forEach(language -> languages.put(language.name(), language));
        }
        Set<String> names = new TreeSet<>(languages.keySet());
        if (defaultName != null) {
            if (!languages.containsKey(defaultName)) {
                throw new IllegalStateException("The default language '" + Failures.quote(defaultName)
                        + "' isn't one of the engine's expression languages: " + names);
            }
            return new LanguageRegistry(Map.copyOf(languages), defaultName);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("The engine has no expression language: add one with language(), or put "
                    + "a language on the class path or, with a provides clause, on the module path");
        }
        Iterator<String> name = names.iterator();
        String only = name.next();
        if (name.hasNext()) {
            throw new IllegalStateException("The engine has several expression languages, " + names
                    + ", so name the language of rules without one with defaultLanguage()");
        }
        return new LanguageRegistry(Map.copyOf(languages), only);
    }

    private static void discover(ClassLoader loader, Map<String, ExpressionLanguage> languages, Set<Class<?>> found) {
        for (ExpressionLanguage language : ServiceLoader.load(ExpressionLanguage.class, loader)) {
            // A loader that delegates to this library's loader finds the same languages again.
            if (!found.add(language.getClass())) {
                continue;
            }
            String name = language.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("The expression language " + language.getClass().getName()
                        + " found with ServiceLoader has a null or blank name");
            }
            ExpressionLanguage other = languages.putIfAbsent(name, language);
            if (other != null) {
                throw new IllegalStateException("The expression languages " + other.getClass().getName() + " and "
                        + language.getClass().getName() + " found with ServiceLoader are both named '"
                        + Failures.quote(name) + "'");
            }
        }
    }
}
