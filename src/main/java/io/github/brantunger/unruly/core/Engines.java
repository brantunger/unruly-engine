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
     * Creates a stateless engine with no limit on compiled copies.
     *
     * @param outputFactory Creates the output object
     * @param <O>           The type of the output object
     * @return The engine
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngine<O> stateless(Supplier<O> outputFactory) {
        return new StatelessRulesEngine<>(outputFactory);
    }

    /**
     * Creates a stateful engine with no limit on compiled copies.
     *
     * @param outputFactory Creates the output object
     * @param <O>           The type of the output object
     * @return The engine
     * @throws NullPointerException if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngine<O> stateful(Supplier<O> outputFactory) {
        return new StatefulRulesEngine<>(outputFactory);
    }

    /**
     * Creates a stateless engine that keeps at most {@code maxCopies} compiled copies of its rules.
     *
     * @param outputFactory Creates the output object
     * @param maxCopies     The most compiled copies, at least 1
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngine<O> stateless(Supplier<O> outputFactory, int maxCopies) {
        return new StatelessRulesEngine<>(outputFactory, maxCopies);
    }

    /**
     * Creates a stateful engine that keeps at most {@code maxCopies} compiled copies of its rules.
     *
     * @param outputFactory Creates the output object
     * @param maxCopies     The most compiled copies, at least 1
     * @param <O>           The type of the output object
     * @return The engine
     * @throws IllegalArgumentException if {@code maxCopies} is less than 1
     * @throws NullPointerException     if {@code outputFactory} is {@code null}
     */
    public static <O> RulesEngine<O> stateful(Supplier<O> outputFactory, int maxCopies) {
        return new StatefulRulesEngine<>(outputFactory, maxCopies);
    }
}
