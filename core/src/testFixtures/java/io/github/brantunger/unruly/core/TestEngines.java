package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.RulesEngineBuilder;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Builds engines with {@link RulesEngineBuilder} for tests in this package that call the engine class's own
 * package-private methods, and first-match engines with a {@link CoreRulesEngine.TestSeam} for tests that put their
 * own reading of the rules, or their own run body, in place of the engine's.
 */
final class TestEngines {

    private TestEngines() {
    }

    static <O> CoreRulesEngine<O> firstMatch(Supplier<O> outputFactory) {
        return firstMatch(outputFactory, UnaryOperator.identity());
    }

    static <O> CoreRulesEngine<O> firstMatch(Supplier<O> outputFactory,
                                             UnaryOperator<RulesEngineBuilder<O>> configuration) {
        return (CoreRulesEngine<O>) configuration.apply(RulesEngineBuilder.firstMatch(outputFactory)).build();
    }

    static <O> CoreRulesEngine<O> allMatches(Supplier<O> outputFactory) {
        return allMatches(outputFactory, UnaryOperator.identity());
    }

    static <O> CoreRulesEngine<O> allMatches(Supplier<O> outputFactory,
                                             UnaryOperator<RulesEngineBuilder<O>> configuration) {
        return (CoreRulesEngine<O>) configuration.apply(RulesEngineBuilder.allMatches(outputFactory)).build();
    }

    /**
     * Creates a first-match engine with {@code seam} in place of its own reading of its rules or of its runs' body,
     * as the test seam's subclass overrides. A run whose seam keeps the engine's own body stops at the first match,
     * where the anonymous subclasses these tests used before #1245 evaluated every condition and fired the first.
     * No test that uses this depends on the difference: those that keep the body load a rule list of one rule.
     *
     * @param outputFactory Creates the output object
     * @param configuration The engine's settings
     * @param seam          What the test puts in place of the engine's own
     * @param <O>           The type of the output object
     * @return The engine
     */
    static <O> CoreRulesEngine<O> withSeam(Supplier<O> outputFactory, EngineConfiguration<O> configuration,
                                           CoreRulesEngine.TestSeam<O> seam) {
        return new CoreRulesEngine<>(MatchPolicy.FIRST_MATCH, outputFactory, configuration, seam);
    }
}
