package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngine;

import java.util.function.Supplier;

/**
 * Creates the engines for {@link io.github.brantunger.unruly.api.RulesEngineBuilder}, which is the only supported way
 * to create one. The engine classes are package-private, so this is the one public entry point into this package.
 *
 * <p>
 * <b>Internal:</b> this class may change in any release. Use {@link io.github.brantunger.unruly.api.RulesEngineBuilder}
 * instead.
 * </p>
 */
public final class Engines {

    private Engines() {
    }

    /**
     * Creates an engine that fires the highest-priority matching rule.
     *
     * @param outputFactory Creates the output object
     * @param configuration The builder's settings
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are
     *                                  given for a language the engine doesn't have
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if an argument is {@code null}
     */
    public static <O> RulesEngine<O> firstMatch(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        return new StatelessRulesEngine<>(outputFactory, configuration);
    }

    /**
     * Creates an engine that fires every matching rule in priority order.
     *
     * @param outputFactory Creates the output object
     * @param configuration The builder's settings
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are
     *                                  given for a language the engine doesn't have
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if an argument is {@code null}
     */
    public static <O> RulesEngine<O> allMatches(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        return new StatefulRulesEngine<>(outputFactory, configuration);
    }

    /**
     * Creates an engine that fires the one matching rule, and fails a run in which more than one rule matches.
     *
     * @param outputFactory Creates the output object
     * @param configuration The builder's settings
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalStateException    if the languages or the default language can't be resolved, or options are
     *                                  given for a language the engine doesn't have
     * @throws IllegalArgumentException if an import can't be resolved
     * @throws NullPointerException     if an argument is {@code null}
     */
    public static <O> RulesEngine<O> uniqueMatch(Supplier<O> outputFactory, EngineConfiguration<O> configuration) {
        return new UniqueMatchRulesEngine<>(outputFactory, configuration);
    }
}
