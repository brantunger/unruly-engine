package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Builds engines with {@link RulesEngineBuilder} for tests in this package that call the engine classes' own
 * package-private methods.
 */
final class TestEngines {

    private TestEngines() {
    }

    static <O> StatelessRulesEngine<O> firstMatch(Supplier<O> outputFactory) {
        return firstMatch(outputFactory, UnaryOperator.identity());
    }

    static <O> StatelessRulesEngine<O> firstMatch(Supplier<O> outputFactory,
                                                  UnaryOperator<RulesEngineBuilder<O>> configuration) {
        return (StatelessRulesEngine<O>) configuration.apply(RulesEngineBuilder.firstMatch(outputFactory)).build();
    }

    static <O> StatefulRulesEngine<O> allMatches(Supplier<O> outputFactory) {
        return allMatches(outputFactory, UnaryOperator.identity());
    }

    static <O> StatefulRulesEngine<O> allMatches(Supplier<O> outputFactory,
                                                 UnaryOperator<RulesEngineBuilder<O>> configuration) {
        return (StatefulRulesEngine<O>) configuration.apply(RulesEngineBuilder.allMatches(outputFactory)).build();
    }
}
