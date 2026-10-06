package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Builds an {@link EngineConfiguration} for tests in this package that create an engine without
 * {@link io.github.brantunger.unruly.api.RulesEngineBuilder}. A test sets only the components it cares about; the
 * others keep the defaults below, so a component added to the record needs a default here and no change to the tests.
 *
 * <p>
 * The defaults: no default language, no imports or listeners, no copy limit, no copies at load, no run timeout, the
 * UTC system clock, {@code Object} as the output type, {@link OutputWriter#beansAndMaps()}, no options, no declared
 * facts, not all facts declared and no language imports.
 * </p>
 */
final class TestConfigurations {

    private final Map<String, ExpressionLanguage> languages;
    private String defaultLanguage;
    private List<RuleListener> listeners = List.of();
    private CopyLimit copyLimit = CopyLimit.none();
    private Duration runTimeout;

    private TestConfigurations(Map<String, ExpressionLanguage> languages) {
        this.languages = languages;
    }

    /**
     * Starts a configuration with the given languages and every other component at its default.
     *
     * @param languages The languages, by name
     * @return The builder
     */
    static TestConfigurations engineConfiguration(Map<String, ExpressionLanguage> languages) {
        return new TestConfigurations(languages);
    }

    TestConfigurations withDefaultLanguage(String name) {
        this.defaultLanguage = name;
        return this;
    }

    TestConfigurations withListeners(List<RuleListener> listeners) {
        this.listeners = listeners;
        return this;
    }

    TestConfigurations withCopyLimit(CopyLimit limit) {
        this.copyLimit = limit;
        return this;
    }

    TestConfigurations withRunTimeout(Duration timeout) {
        this.runTimeout = timeout;
        return this;
    }

    <O> EngineConfiguration<O> build() {
        return new EngineConfiguration<>(languages, defaultLanguage, List.of(), listeners, copyLimit, 0, runTimeout,
                Clock.systemUTC(), Object.class, OutputWriter.beansAndMaps(), Map.of(), Map.of(), false, Map.of());
    }
}
