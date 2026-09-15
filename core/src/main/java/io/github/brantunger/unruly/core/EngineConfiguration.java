package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.util.List;

/**
 * The settings an engine is built with, as {@link io.github.brantunger.unruly.api.RulesEngineBuilder} collected them.
 * The engine resolves them when it's created. <b>Internal:</b> this record may change in any release.
 *
 * @param languages       The languages given to the builder, or none to find them with {@link java.util.ServiceLoader}
 * @param defaultLanguage The name of the default language, or {@code null} to use the only language
 * @param imports         The package and class names to import, not yet resolved
 * @param listeners       The listeners, in the order they're called
 * @param maxCopies       The most compiled copies of the rules, or {@link #UNLIMITED_COPIES}
 */
public record EngineConfiguration(List<ExpressionLanguage> languages, String defaultLanguage, List<String> imports,
                                  List<RuleListener> listeners, int maxCopies) {

    /** The {@code maxCopies} of an engine that makes as many copies as its runs need. */
    public static final int UNLIMITED_COPIES = RuleSet.UNLIMITED;

    /**
     * Keeps unmodifiable copies of the lists, so later changes to the builder don't change an engine.
     *
     * @throws NullPointerException if a list, or an element of one, is {@code null}
     */
    public EngineConfiguration {
        languages = List.copyOf(languages);
        imports = List.copyOf(imports);
        listeners = List.copyOf(listeners);
    }
}
