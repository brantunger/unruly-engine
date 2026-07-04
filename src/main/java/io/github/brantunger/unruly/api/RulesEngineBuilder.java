package io.github.brantunger.unruly.api;

import io.github.brantunger.unruly.core.StatefulRulesEngine;
import io.github.brantunger.unruly.core.StatelessRulesEngine;

import java.util.function.Supplier;

/**
 * A builder to construct instances of {@link RulesEngine}. This provides a clean API for consumers
 * without requiring them to directly import core engine implementations.
 */
public final class RulesEngineBuilder {

    private RulesEngineBuilder() {
        // Hide utility class constructor
    }

    /**
     * Creates a new STATELESS rules engine. A stateless engine evaluates all rules but only
     * fires the action of the single highest-priority rule that matched.
     *
     * @param outputFactory A supplier to instantiate the output object
     * @param <O>           The type of the output object
     * @return A new stateless {@link RulesEngine}
     */
    public static <O> RulesEngine<O> stateless(Supplier<O> outputFactory) {
        return new StatelessRulesEngine<>(outputFactory);
    }

    /**
     * Creates a new STATEFUL rules engine. A stateful engine evaluates all rules and fires
     * the actions of all matching rules in priority order, accumulating changes in the output object.
     *
     * @param outputFactory A supplier to instantiate the output object
     * @param <O>           The type of the output object
     * @return A new stateful {@link RulesEngine}
     */
    public static <O> RulesEngine<O> stateful(Supplier<O> outputFactory) {
        return new StatefulRulesEngine<>(outputFactory);
    }
}
