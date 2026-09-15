package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The expression languages an engine can compile rules with: those found with {@link ServiceLoader}, such as MVEL,
 * and those registered on the engine. The engine refers to no language directly, so a language, MVEL included, is only
 * created when a rule list is loaded.
 */
final class LanguageRegistry {

    // Registered with registerLanguage(), by name. They replace a found language of the same name.
    private final Map<String, ExpressionLanguage> registered = new ConcurrentHashMap<>();

    /**
     * Registers a language, replacing a registered or found language with the same name.
     *
     * @param language The language
     * @throws IllegalArgumentException if the language's name is {@code null} or blank
     * @throws NullPointerException     if {@code language} is {@code null}
     */
    void register(ExpressionLanguage language) {
        Objects.requireNonNull(language, "language must not be null");
        String name = language.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An expression language's name must not be null or blank: "
                    + language.getClass().getName());
        }
        registered.put(name, language);
    }

    /**
     * Finds the languages listed in {@code META-INF/services/io.github.brantunger.unruly.api.language.ExpressionLanguage}
     * with this library's class loader and with {@code loader}, creating each one, and adds the registered languages,
     * which replace a found language of the same name.
     *
     * @param loader The class loader rules are compiled with, which may see languages this library's loader can't
     * @return The languages by name
     * @throws IllegalStateException if a found language's name is {@code null} or blank, or two different found
     *                               languages have the same name. Anything {@link ServiceLoader} or a language throws
     *                               while it is found, such as a {@link java.util.ServiceConfigurationError}, is thrown
     *                               unchanged.
     */
    Map<String, ExpressionLanguage> available(ClassLoader loader) {
        Map<String, ExpressionLanguage> languages = new HashMap<>();
        Set<Class<?>> found = new HashSet<>();
        discover(ImportResolver.LIBRARY_CLASS_LOADER, languages, found);
        if (loader != ImportResolver.LIBRARY_CLASS_LOADER) {
            discover(loader, languages, found);
        }
        languages.putAll(registered);
        return Map.copyOf(languages);
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
